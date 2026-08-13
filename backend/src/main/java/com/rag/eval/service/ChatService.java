package com.rag.eval.service;

import com.rag.eval.model.*;
import com.rag.eval.repository.ChatHistoryRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final Pattern CITATION_PAT = Pattern.compile(
        "《[^》]{1,80}》|【[^】]{1,120}】|（[^（）]{0,200}）|\\[[^\\[\\]]{0,120}\\]");

    private static final Pattern FILENAME_PAT = Pattern.compile(
        "[\\p{L}\\p{N}_.·\\-]{1,80}\\.(?:pdf|docx?|txt|md|pptx|csv|xlsx)", Pattern.CASE_INSENSITIVE);

    private final DashScopeService dashScope;
    private final RetrievalService retrievalService;
    private final SafetyService safetyService;
    private final PIIRedactionService piiService;
    private final SemanticCacheService cacheService;
    private final MetricsCollector metricsCollector;
    private final ChatHistoryRepo historyRepo;

    public ChatService(DashScopeService dashScope,
                       RetrievalService retrievalService,
                       SafetyService safetyService,
                       PIIRedactionService piiService,
                       SemanticCacheService cacheService,
                       MetricsCollector metricsCollector,
                       ChatHistoryRepo historyRepo) {
        this.dashScope = dashScope;
        this.retrievalService = retrievalService;
        this.safetyService = safetyService;
        this.piiService = piiService;
        this.cacheService = cacheService;
        this.metricsCollector = metricsCollector;
        this.historyRepo = historyRepo;
    }

    public ChatResponse ask(String question, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        OpsMetrics metrics = metricsCollector.startRequest(sessionId, retrievalService.getMode());
        MDC.put("traceId", metrics.getRequestId());
        MDC.put("sessionId", sessionId);
        MDC.put("retrievalMode", retrievalService.getMode());

        Instant start = Instant.now();

        try {
            // 1. Check semantic cache
            String normalized = normalizeQuery(question);
            String cached = cacheService.lookup(normalized);
            if (cached != null) {
                metrics.setCacheHit(true);
                metrics.setTotalLatencyMs(0);
                metricsCollector.complete(metrics);
                return new ChatResponse(cached, retrievalService.getMode(), List.of(), false, null);
            }

            // 2. Retrieve
            Instant retrievalStart = Instant.now();
            List<SearchResult> chunks = retrievalService.retrieve(question);
            metrics.setRetrievalLatencyMs(Duration.between(retrievalStart, Instant.now()).toMillis());
            metrics.setChunksRetrieved(chunks.size());
            metrics.setMaxChunkScore(chunks.stream().mapToDouble(SearchResult::getScore).max().orElse(0.0));

            // 3. Safety check
            SafetyService.SafetyResult safe = safetyService.evaluate(question, chunks);
            if (!safe.allowed()) {
                metrics.setRefusal(true);
                metrics.setRefusalReason(safe.decision().name());
                metrics.setTotalLatencyMs(Duration.between(start, Instant.now()).toMillis());
                metricsCollector.complete(metrics);

                historyRepo.save(createMessage(sessionId, "user", question));
                historyRepo.save(createMessage(sessionId, "assistant", safe.decision().message));

                return new ChatResponse(safe.decision().message, retrievalService.getMode(),
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
            String answerText = dashScope.chat(systemPrompt, question);
            metrics.setGenerationLatencyMs(Duration.between(genStart, Instant.now()).toMillis());
            metrics.setPromptTokens(countTokens(systemPrompt + question));
            metrics.setCompletionTokens(countTokens(answerText));

            // 6. PII redaction
            int redactions = piiService.redactCount(answerText);
            metrics.setPiiRedactions(redactions);
            answerText = piiService.redact(answerText);

            // 7. Final metrics
            metrics.setTotalLatencyMs(Duration.between(start, Instant.now()).toMillis());
            metricsCollector.complete(metrics);

            // 8. Save history
            historyRepo.save(createMessage(sessionId, "user", question));
            historyRepo.save(createMessage(sessionId, "assistant", answerText));

            // 9. Cache
            cacheService.store(normalized, answerText);

            // 10. Build sources
            List<Source> sources = chunks.stream()
                .map(c -> new Source(c.getFileName(),
                    c.getContent().length() > 200 ? c.getContent().substring(0, 200) : c.getContent(),
                    c.getScore(), c.getSource()))
                .collect(Collectors.toMap(Source::getFileName, s -> s, (a, b) -> a))
                .values().stream().toList();

            log.info("Chat completed: retrievalMode={}, latency={}ms, chunks={}, cache={}, refusal={}",
                retrievalService.getMode(), metrics.getTotalLatencyMs(),
                chunks.size(), metrics.isCacheHit(), metrics.isRefusal());

            return new ChatResponse(answerText, retrievalService.getMode(), sources, false, null);

        } finally {
            MDC.remove("traceId");
            MDC.remove("sessionId");
            MDC.remove("retrievalMode");
        }
    }

    public List<ChatMessage> getHistory(String sessionId) {
        return historyRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    private String normalizeQuery(String query) {
        return query.toLowerCase().strip().replaceAll("\\s+", " ");
    }

    private String scrubCitations(String text) {
        String scrubbed = CITATION_PAT.matcher(text).replaceAll("");
        return FILENAME_PAT.matcher(scrubbed).replaceAll("").trim();
    }

    private int countTokens(String text) {
        return (int) (text.length() / 1.5);
    }

    private ChatMessage createMessage(String sessionId, String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }
}
