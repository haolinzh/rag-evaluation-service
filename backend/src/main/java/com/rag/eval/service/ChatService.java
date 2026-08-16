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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
        String retrievedChunksJson = null;
        String rerankCandidatesJson = null;
        String promptForLog = null;

        try {
            // 1. Check semantic cache
            String normalized = normalizeQuery(question);
            Instant cacheStart = Instant.now();
            String cached = cacheService.lookup(normalized, effectiveMode, dashScope.getChatModel());
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
                logRequest(metrics, question, cachedResponse.getContent(), "", 0, "success", null, null, null);

                historyRepo.save(createMessage(sessionId, "user", question));
                historyRepo.save(createAssistantMessage(sessionId, cachedResponse.getContent(),
                    cachedResponse.getThinking(), cachedResponse.getRetrievalMode(), cachedResponse.getSources(),
                    cachedResponse.isRefusal()));

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

            retrievedChunksJson = serializeChunks(chunks, true);
            rerankCandidatesJson = (rr.rerankCandidates() != null && !rr.rerankCandidates().isEmpty())
                ? serializeChunks(rr.rerankCandidates(), true) : null;

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
                logRequest(metrics, question, safe.decision().message, hitDocuments, 0, "refused", retrievedChunksJson, rerankCandidatesJson, null);

                historyRepo.save(createMessage(sessionId, "user", question));
                historyRepo.save(createAssistantMessage(sessionId, safe.decision().message, null, effectiveMode, List.of(), true));

                return new ChatResponse(safe.decision().message, null, effectiveMode,
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
                回答请使用 Markdown 排版：关键结论加粗、要点用列表、必要时用小标题分级。

                %s=== 文档内容 ===
                %s
                """.formatted(historyContext, context);

            promptForLog = piiService.redact(systemPrompt + "\n\n[用户问题] " + question);

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
            logRequest(metrics, question, answerText, hitDocuments, llmCallCount, "success", retrievedChunksJson, rerankCandidatesJson, promptForLog);

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

            ChatResponse response = new ChatResponse(answerText, gen.thinking(), effectiveMode, sources, false, null);

            // 9. Cache (store full response so cache hits still return sources)
            cacheService.store(normalized, effectiveMode, dashScope.getChatModel(), serializeCached(response));

            // 10. Save history
            historyRepo.save(createMessage(sessionId, "user", question));
            historyRepo.save(createAssistantMessage(sessionId, answerText, gen.thinking(), effectiveMode, sources, false));

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
            logRequest(metrics, question, null, hitDocuments, llmCallCount, "error", retrievedChunksJson, rerankCandidatesJson, null);

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

    public void streamAsk(String question, String sessionId, String mode, SseEmitter emitter) {
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
        String retrievedChunksJson = null;
        String rerankCandidatesJson = null;
        String promptForLog = null;

        try {
            // 1. Semantic cache
            String normalized = normalizeQuery(question);
            Instant cacheStart = Instant.now();
            String cached = cacheService.lookup(normalized, effectiveMode, dashScope.getChatModel());
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
                logRequest(metrics, question, cachedResponse.getContent(), "", 0, "success", null, null, null);

                historyRepo.save(createMessage(sessionId, "user", question));
                historyRepo.save(createAssistantMessage(sessionId, cachedResponse.getContent(),
                    cachedResponse.getThinking(), cachedResponse.getRetrievalMode(), cachedResponse.getSources(),
                    cachedResponse.isRefusal()));

                emitThinking(emitter, cachedResponse.getThinking());
                emitContent(emitter, cachedResponse.getContent());
                emitDone(emitter, cachedResponse);
                return;
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

            retrievedChunksJson = serializeChunks(chunks, true);
            rerankCandidatesJson = (rr.rerankCandidates() != null && !rr.rerankCandidates().isEmpty())
                ? serializeChunks(rr.rerankCandidates(), true) : null;

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
                logRequest(metrics, question, safe.decision().message, hitDocuments, 0, "refused", retrievedChunksJson, rerankCandidatesJson, null);

                historyRepo.save(createMessage(sessionId, "user", question));
                historyRepo.save(createAssistantMessage(sessionId, safe.decision().message, null, effectiveMode, List.of(), true));

                emitContent(emitter, safe.decision().message);
                emitDone(emitter, new ChatResponse(safe.decision().message, null, effectiveMode,
                    List.of(), true, safe.decision().name()));
                return;
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
                回答请使用 Markdown 排版：关键结论加粗、要点用列表、必要时用小标题分级。

                %s=== 文档内容 ===
                %s
                """.formatted(historyContext, context);

            promptForLog = piiService.redact(systemPrompt + "\n\n[用户问题] " + question);

            // 5. Stream generation
            Instant genStart = Instant.now();
            llmCallCount++;
            StringBuilder answerBuf = new StringBuilder();
            StringBuilder thinkingBuf = new StringBuilder();
            DashScopeService.ChatResult gen = dashScope.chatStream(systemPrompt, question,
                delta -> {
                    thinkingBuf.append(delta);
                    emitThinking(emitter, delta);
                },
                delta -> {
                    answerBuf.append(delta);
                    emitContent(emitter, delta);
                });
            String answerText = gen.content();
            String thinkingText = gen.thinking();
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

            // 6. PII redaction (persisted copy only; streamed text is raw)
            int redactions = piiService.redactCount(answerText);
            metrics.setPiiRedactions(redactions);
            String redacted = piiService.redact(answerText);
            metrics.setAnswerCompliance(complianceScore(redacted, false));

            // 7. Final metrics + log
            metrics.setTotalLatencyMs(Duration.between(start, Instant.now()).toMillis());
            metricsCollector.complete(metrics);
            logRequest(metrics, question, redacted, hitDocuments, llmCallCount, "success", retrievedChunksJson, rerankCandidatesJson, promptForLog);

            // 8. Build sources
            boolean noInfo = NO_INFO_PAT.matcher(redacted).find();
            List<Source> sources = noInfo ? List.of() : chunks.stream()
                .map(c -> {
                    String r = piiService.redact(c.getContent());
                    String snippet = r.length() > 200 ? r.substring(0, 200) : r;
                    return new Source(c.getFileName(), snippet, c.getScore(), c.getSource());
                })
                .collect(Collectors.toMap(Source::getFileName, s -> s, (a, b) -> a))
                .values().stream().toList();

            ChatResponse response = new ChatResponse(redacted, thinkingText, effectiveMode, sources, false, null);

            // 9. Cache (store full response so cache hits still return sources + thinking)
            cacheService.store(normalized, effectiveMode, dashScope.getChatModel(), serializeCached(response));

            // 10. Save history
            historyRepo.save(createMessage(sessionId, "user", question));
            historyRepo.save(createAssistantMessage(sessionId, redacted, thinkingText, effectiveMode, sources, false));

            // 11. Emit final event
            emitDone(emitter, response);

        } catch (RuntimeException e) {
            metrics.setTotalLatencyMs(Duration.between(start, Instant.now()).toMillis());
            metricsCollector.complete(metrics);
            logRequest(metrics, question, null, hitDocuments, llmCallCount, "error", retrievedChunksJson, rerankCandidatesJson, null);

            Map<String, Object> errorFields = new LinkedHashMap<>();
            errorFields.put("event", "error");
            errorFields.put("exception", e.getClass().getName());
            errorFields.put("error_message", e.getMessage() == null ? "" : e.getMessage());
            log.error("Chat stream failed {}", entries(errorFields), e);
            emitError(emitter, e.getMessage() == null ? "未知错误" : e.getMessage());
        } finally {
            MDC.remove("traceId");
            MDC.remove("sessionId");
            MDC.remove("retrievalMode");
        }
    }

    private void emitThinking(SseEmitter emitter, String text) {
        if (text == null || text.isEmpty()) return;
        emit(emitter, "thinking", text);
    }

    private void emitContent(SseEmitter emitter, String text) {
        emit(emitter, "content", text);
    }

    private void emit(SseEmitter emitter, String type, String text) {
        try {
            emitter.send(objectMapper.writeValueAsString(Map.of("type", type, "text", text)));
        } catch (Exception ignored) {
            // client disconnected mid-stream
        }
    }

    private void emitDone(SseEmitter emitter, ChatResponse resp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "done");
        payload.put("content", resp.getContent());
        payload.put("thinking", resp.getThinking() == null ? "" : resp.getThinking());
        payload.put("retrievalMode", resp.getRetrievalMode());
        payload.put("sources", resp.getSources());
        payload.put("refusal", resp.isRefusal());
        payload.put("refusalReason", resp.getRefusalReason() == null ? "" : resp.getRefusalReason());
        try {
            emitter.send(objectMapper.writeValueAsString(payload));
            emitter.complete();
        } catch (Exception ignored) {
            // client disconnected
        }
    }

    private void emitError(SseEmitter emitter, String message) {
        try {
            emitter.send(objectMapper.writeValueAsString(Map.of("type", "error", "text", message)));
            emitter.complete();
        } catch (Exception ignored) {
            // client disconnected
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

    private ChatMessage createAssistantMessage(String sessionId, String content, String thinking,
                                               String retrievalMode, List<Source> sources, boolean refusal) {
        ChatMessage msg = createMessage(sessionId, "assistant", content);
        msg.setThinking(thinking);
        msg.setRetrievalMode(retrievalMode);
        msg.setRefusal(refusal);
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
            return new ChatResponse(cached, null, mode, List.of(), false, null);
        }
    }

    private String serializeChunks(List<SearchResult> chunks, boolean redactContent) {
        try {
            List<Map<String, Object>> arr = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                SearchResult c = chunks.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank", i + 1);
                m.put("fileName", c.getFileName());
                m.put("chunkId", c.getChunkId());
                m.put("score", c.getScore());
                m.put("source", c.getSource());
                if (c.getChapter() != null) m.put("chapter", c.getChapter());
                if (c.getSection() != null) m.put("section", c.getSection());
                if (c.getContent() != null) {
                    String content = redactContent ? piiService.redact(c.getContent()) : c.getContent();
                    m.put("snippet", content.length() > 300 ? content.substring(0, 300) : content);
                }
                SearchResult.SourceDetail sd = c.getSourceDetails();
                if (sd != null) {
                    Map<String, Object> scores = new LinkedHashMap<>();
                    if (sd.getKeywordScore() != null) scores.put("keyword", sd.getKeywordScore());
                    if (sd.getSemanticScore() != null) scores.put("semantic", sd.getSemanticScore());
                    if (sd.getRrfScore() != null) scores.put("rrf", sd.getRrfScore());
                    m.put("sourceDetails", scores);
                }
                arr.add(m);
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            log.warn("Failed to serialize chunks", e);
            return "[]";
        }
    }

    private void logRequest(OpsMetrics m, String question, String answer, String hitDocuments,
                            int llmCallCount, String status,
                            String retrievedChunks, String rerankCandidates, String prompt) {
        RequestLog log = new RequestLog();
        log.setRequestId(m.getRequestId());
        log.setSessionId(m.getSessionId());
        log.setQuestion(piiService.redact(question));
        log.setAnswer(answer);
        log.setModel(dashScope.getChatModel());
        log.setRetrievalMode(m.getRetrievalMode());
        log.setHitDocuments(hitDocuments);
        log.setRetrievedChunks(retrievedChunks);
        log.setRerankCandidates(rerankCandidates);
        log.setPrompt(prompt);
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
