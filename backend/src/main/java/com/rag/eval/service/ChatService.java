package com.rag.eval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.eval.model.*;
import com.rag.eval.repository.ChatHistoryRepo;
import com.rag.eval.repository.RequestLogRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.entries;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final Pattern CITATION_PAT = Pattern.compile(
        "《[^》]{1,80}》|【[^】]{1,120}】|（[^（）]{0,200}）|\\[[^\\[\\]]{0,120}\\]");

    private static final Pattern FILENAME_PAT = Pattern.compile(
        "[\\p{L}\\p{N}_.·\\-]{1,80}\\.(?:pdf|docx?|txt|md|pptx|csv|xlsx)", Pattern.CASE_INSENSITIVE);

    private static final Pattern NO_INFO_PAT = Pattern.compile(
        "该知识库中暂无相关信息|知识库中暂无相关信息|暂无相关|没有找到相关|未找到相关|找不到相关|没有相关|无相关信息|未检索到|未能找到");

    private final DashScopeService dashScope;
    private final RetrievalService retrievalService;
    private final SafetyService safetyService;
    private final PIIRedactionService piiService;
    private final SemanticCacheService cacheService;
    private final MetricsCollector metricsCollector;
    private final ChatHistoryRepo historyRepo;
    private final RequestLogRepo requestLogRepo;
    private final ObjectMapper objectMapper;

    public ChatService(DashScopeService dashScope,
                       RetrievalService retrievalService,
                       SafetyService safetyService,
                       PIIRedactionService piiService,
                       SemanticCacheService cacheService,
                       MetricsCollector metricsCollector,
                       ChatHistoryRepo historyRepo,
                       RequestLogRepo requestLogRepo,
                       ObjectMapper objectMapper) {
        this.dashScope = dashScope;
        this.retrievalService = retrievalService;
        this.safetyService = safetyService;
        this.piiService = piiService;
        this.cacheService = cacheService;
        this.metricsCollector = metricsCollector;
        this.historyRepo = historyRepo;
        this.requestLogRepo = requestLogRepo;
        this.objectMapper = objectMapper;
    }

    public ChatResponse ask(String question, String sessionId, String mode) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        String effectiveMode = retrievalService.resolveMode(mode);

        OpsMetrics metrics = metricsCollector.startRequest(sessionId, effectiveMode);
        MDC.put("traceId", metrics.getRequestId());
        MDC.put("sessionId", sessionId);
        MDC.put("retrievalMode", effectiveMode);

        Instant start = Instant.now();
        int llmCallCount = 0;
        String hitDocuments = "";

        try {
            // 1. Check semantic cache
            String normalized = normalizeQuery(question);
            Instant cacheStart = Instant.now();
            String cached = cacheService.lookup(normalized, effectiveMode);
            boolean cacheHit = cached != null;
            long cacheLookupLatencyMs = Duration.between(cacheStart, Instant.now()).toMillis();
            metrics.setCacheLookupLatencyMs(cacheLookupLatencyMs);
            Map<String, Object> cacheFields = new LinkedHashMap<>();
            cacheFields.put("event", "cache");
            cacheFields.put("hit", cacheHit);
            cacheFields.put("lookup_latency_ms", cacheLookupLatencyMs);
            cacheFields.put("mode", effectiveMode);
            log.info("Cache lookup {}", entries(cacheFields));
            if (cacheHit) {
                metrics.setCacheHit(true);
                metrics.setTotalLatencyMs(0);
                ChatResponse cachedResponse = deserializeCached(cached, effectiveMode);
                metrics.setAnswerCompliance(complianceScore(cachedResponse.getContent(), false));
                metricsCollector.complete(metrics);
                logRequest(metrics, question, cachedResponse.getContent(), "", 0, "success");

                historyRepo.save(createMessage(sessionId, "user", question));
                historyRepo.save(createAssistantMessage(sessionId, cachedResponse.getContent(),
                    cachedResponse.getRetrievalMode(), cachedResponse.getSources()));

                return cachedResponse;
            }

            // 2. Retrieve
            Instant retrievalStart = Instant.now();
            RetrievalService.RetrievalResult rr = retrievalService.retrieve(question, effectiveMode);
            List<SearchResult> chunks = rr.results();
            hitDocuments = chunks.stream().map(SearchResult::getFileName).distinct()
                .collect(Collectors.joining(", "));
            metrics.setRetrievalLatencyMs(Duration.between(retrievalStart, Instant.now()).toMillis());
            metrics.setChunksRetrieved(chunks.size());
            metrics.setMaxChunkScore(chunks.stream().mapToDouble(SearchResult::getScore).max().orElse(0.0));
            metrics.setKeywordCount(rr.keywordCount());
            metrics.setVectorCount(rr.vectorCount());
            metrics.setOverlapCount(rr.overlapCount());
            metrics.setEmbeddingLatencyMs(rr.embeddingLatencyMs());
            metrics.setKeywordLatencyMs(rr.keywordLatencyMs());
            metrics.setVectorLatencyMs(rr.vectorLatencyMs());
            metrics.setRerankLatencyMs(rr.rerankLatencyMs());

            // 3. Safety check
            SafetyService.SafetyResult safe = safetyService.evaluate(question, chunks);
            Map<String, Object> safetyFields = new LinkedHashMap<>();
            safetyFields.put("event", "safety");
            safetyFields.put("decision", safe.decision().name());
            safetyFields.put("allowed", safe.allowed());
            safetyFields.put("max_chunk_score", metrics.getMaxChunkScore());
            log.info("Safety evaluation {}", entries(safetyFields));
            if (!safe.allowed()) {
                metrics.setRefusal(true);
                metrics.setRefusalReason(safe.decision().name());
                metrics.setAnswerCompliance(1.0);
                metrics.setTotalLatencyMs(Duration.between(start, Instant.now()).toMillis());
                metricsCollector.complete(metrics);
                logRequest(metrics, question, safe.decision().message, hitDocuments, 0, "refused");

                historyRepo.save(createMessage(sessionId, "user", question));
                historyRepo.save(createMessage(sessionId, "assistant", safe.decision().message));

                return new ChatResponse(safe.decision().message, effectiveMode,
                    List.of(), true, safe.decision().name());
            }

            // 4. Build context + history
            String context = chunks.stream()
                .map(doc -> "【来源: " + doc.getFileName() + "】\n" + doc.getContent())
                .collect(Collectors.joining("\n\n"));

            List<ChatMessage> history = historyRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
            String historyContext = !history.isEmpty()
                ? "=== 对话历史 ===\n" + history.stream()
                    .limit(10)
                    .map(m -> {
                        String content = m.getRole().equals("assistant")
                            ? scrubCitations(m.getContent()) : m.getContent();
                        return (m.getRole().equals("user") ? "用户: " : "助手: ") + content;
                    })
                    .collect(Collectors.joining("\n"))
                    + "\n\n"
                : "";

            String systemPrompt = """
                你是一个专业的知识库助手。请严格基于下方【文档内容】回答用户问题。
                如果文档内容不足以回答问题，请明确说明"该知识库中暂无相关信息"。
                引用来源时，只能引用【文档内容】中出现的文件名，禁止引用对话历史、记忆或其他外部来源中的文件名。

                %s=== 文档内容 ===
                %s
                """.formatted(historyContext, context);

            // 5. Generate via DashScope
            Instant genStart = Instant.now();
            llmCallCount++;
            DashScopeService.ChatResult gen = dashScope.chat(systemPrompt, question);
            String answerText = gen.content();
            metrics.setGenerationLatencyMs(Duration.between(genStart, Instant.now()).toMillis());
            metrics.setPromptTokens(gen.promptTokens());
            metrics.setCompletionTokens(gen.completionTokens());

            Map<String, Object> genFields = new LinkedHashMap<>();
            genFields.put("event", "generation");
            genFields.put("model", dashScope.getChatModel());
            genFields.put("prompt_tokens", gen.promptTokens());
            genFields.put("completion_tokens", gen.completionTokens());
            genFields.put("generation_latency_ms", metrics.getGenerationLatencyMs());
            log.info("Generation completed {}", entries(genFields));

            // 6. PII redaction
            int redactions = piiService.redactCount(answerText);
            metrics.setPiiRedactions(redactions);
            answerText = piiService.redact(answerText);
            metrics.setAnswerCompliance(complianceScore(answerText, false));

            // 7. Final metrics
            metrics.setTotalLatencyMs(Duration.between(start, Instant.now()).toMillis());
            metricsCollector.complete(metrics);
            logRequest(metrics, question, answerText, hitDocuments, llmCallCount, "success");

            // 8. Build sources
            boolean noInfo = NO_INFO_PAT.matcher(answerText).find();
            List<Source> sources = noInfo ? List.of() : chunks.stream()
                .map(c -> {
                    String redacted = piiService.redact(c.getContent());
                    String snippet = redacted.length() > 200 ? redacted.substring(0, 200) : redacted;
                    return new Source(c.getFileName(), snippet, c.getScore(), c.getSource());
                })
                .collect(Collectors.toMap(Source::getFileName, s -> s, (a, b) -> a))
                .values().stream().toList();

            ChatResponse response = new ChatResponse(answerText, effectiveMode, sources, false, null);

            // 9. Cache (store full response so cache hits still return sources)
            cacheService.store(normalized, effectiveMode, serializeCached(response));

            // 10. Save history
            historyRepo.save(createMessage(sessionId, "user", question));
            historyRepo.save(createAssistantMessage(sessionId, answerText, effectiveMode, sources));

            Map<String, Object> completeFields = new LinkedHashMap<>();
            completeFields.put("event", "chat_completed");
            completeFields.put("status", "success");
            completeFields.put("model", dashScope.getChatModel());
            completeFields.put("latency_total_ms", metrics.getTotalLatencyMs());
            completeFields.put("latency_retrieval_ms", metrics.getRetrievalLatencyMs());
            completeFields.put("latency_generation_ms", metrics.getGenerationLatencyMs());
            completeFields.put("tokens_prompt", metrics.getPromptTokens());
            completeFields.put("tokens_completion", metrics.getCompletionTokens());
            completeFields.put("tokens_total", metrics.getPromptTokens() + metrics.getCompletionTokens());
            completeFields.put("chunks_retrieved", metrics.getChunksRetrieved());
            completeFields.put("max_chunk_score", metrics.getMaxChunkScore());
            completeFields.put("cache_hit", metrics.isCacheHit());
            completeFields.put("refusal", metrics.isRefusal());
            completeFields.put("pii_redactions", metrics.getPiiRedactions());
            completeFields.put("answer_compliance", metrics.getAnswerCompliance());
            completeFields.put("llm_call_count", llmCallCount);
            completeFields.put("hit_documents", hitDocuments);
            log.info("Chat completed {}", entries(completeFields));

            return response;

        } catch (RuntimeException e) {
            metrics.setTotalLatencyMs(Duration.between(start, Instant.now()).toMillis());
            metricsCollector.complete(metrics);
            logRequest(metrics, question, null, hitDocuments, llmCallCount, "error");

            Map<String, Object> errorFields = new LinkedHashMap<>();
            errorFields.put("event", "error");
            errorFields.put("exception", e.getClass().getName());
            errorFields.put("error_message", e.getMessage() == null ? "" : e.getMessage());
            errorFields.put("latency_total_ms", metrics.getTotalLatencyMs());
            errorFields.put("llm_call_count", llmCallCount);
            log.error("Chat failed {}", entries(errorFields), e);
            throw e;
        } finally {
            MDC.remove("traceId");
            MDC.remove("sessionId");
            MDC.remove("retrievalMode");
        }
    }

    public List<ChatMessage> getHistory(String sessionId) {
        return historyRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional
    public void deleteHistory(String sessionId) {
        historyRepo.deleteBySessionId(sessionId);
    }

    private String normalizeQuery(String query) {
        return query.toLowerCase().strip().replaceAll("\\s+", " ");
    }

    private String scrubCitations(String text) {
        String scrubbed = CITATION_PAT.matcher(text).replaceAll("");
        return FILENAME_PAT.matcher(scrubbed).replaceAll("").trim();
    }

    private double complianceScore(String answer, boolean refusal) {
        if (refusal) return 1.0;
        if (answer == null || answer.isBlank()) return 0.0;
        double score = 0.0;
        if (answer.length() > 10) score += 0.3;
        if (answer.length() > 50) score += 0.3;
        String lower = answer.toLowerCase();
        if (answer.contains("来源") || answer.contains("根据") || answer.contains("文档")
                || lower.contains("knowledge")) {
            score += 0.2;
        }
        return Math.min(1.0, score);
    }

    private ChatMessage createMessage(String sessionId, String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }

    private ChatMessage createAssistantMessage(String sessionId, String content, String retrievalMode, List<Source> sources) {
        ChatMessage msg = createMessage(sessionId, "assistant", content);
        msg.setRetrievalMode(retrievalMode);
        msg.setSources(serializeSources(sources));
        return msg;
    }

    private String serializeSources(List<Source> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            log.warn("Failed to serialize sources", e);
            return "[]";
        }
    }

    private String serializeCached(ChatResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("Failed to serialize cached response, storing content only", e);
            return response.getContent();
        }
    }

    private ChatResponse deserializeCached(String cached, String mode) {
        try {
            return objectMapper.readValue(cached, ChatResponse.class);
        } catch (Exception e) {
            // Legacy cache entry: plain text answer without sources
            return new ChatResponse(cached, mode, List.of(), false, null);
        }
    }

    private void logRequest(OpsMetrics m, String question, String answer, String hitDocuments,
                            int llmCallCount, String status) {
        RequestLog log = new RequestLog();
        log.setRequestId(m.getRequestId());
        log.setSessionId(m.getSessionId());
        log.setQuestion(piiService.redact(question));
        log.setAnswer(answer);
        log.setModel(dashScope.getChatModel());
        log.setRetrievalMode(m.getRetrievalMode());
        log.setHitDocuments(hitDocuments);
        log.setResponseTimeMs(m.getTotalLatencyMs());
        log.setLlmCallCount(llmCallCount);
        log.setCacheHit(m.isCacheHit());
        log.setRefusal(m.isRefusal());
        log.setRefusalReason(m.getRefusalReason());
        log.setRetrievalLatencyMs(m.getRetrievalLatencyMs());
        log.setGenerationLatencyMs(m.getGenerationLatencyMs());
        log.setPromptTokens(m.getPromptTokens());
        log.setCompletionTokens(m.getCompletionTokens());
        log.setChunksRetrieved(m.getChunksRetrieved());
        log.setMaxChunkScore(m.getMaxChunkScore());
        log.setPiiRedactions(m.getPiiRedactions());
        log.setKeywordCount(m.getKeywordCount());
        log.setVectorCount(m.getVectorCount());
        log.setOverlapCount(m.getOverlapCount());
        log.setEmbeddingLatencyMs(m.getEmbeddingLatencyMs());
        log.setKeywordLatencyMs(m.getKeywordLatencyMs());
        log.setVectorLatencyMs(m.getVectorLatencyMs());
        log.setRerankLatencyMs(m.getRerankLatencyMs());
        log.setCacheLookupLatencyMs(m.getCacheLookupLatencyMs());
        log.setStatus(status);
        requestLogRepo.save(log);
    }
}
