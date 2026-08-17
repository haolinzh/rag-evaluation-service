package com.rag.eval.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.eval.model.*;
import com.rag.eval.repository.EvaluationRunRepo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class EvaluationService {

    private static final double RELEVANCE_THRESHOLD = 0.45;
    private static final double PARAPHRASE_CEILING = 0.80;

    private final ChatService chatService;
    private final DashScopeService dashScope;
    private final SemanticCacheService cacheService;
    private final CorpusService corpusService;
    private final EvaluationRunRepo runRepo;
    private final ObjectMapper objectMapper;

    public EvaluationService(ChatService chatService,
                             DashScopeService dashScope,
                             SemanticCacheService cacheService,
                             CorpusService corpusService,
                             EvaluationRunRepo runRepo,
                             ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.dashScope = dashScope;
        this.cacheService = cacheService;
        this.corpusService = corpusService;
        this.runRepo = runRepo;
        this.objectMapper = objectMapper;
    }

    public List<EvaluationQuestion> loadQuestions() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("evaluation-questions.json")) {
            if (in == null) {
                throw new IllegalStateException("evaluation-questions.json not found on classpath");
            }
            return objectMapper.readValue(in, new TypeReference<List<EvaluationQuestion>>() {});
        } catch (IOException e) {
            throw new RuntimeException("Failed to load evaluation questions", e);
        }
    }

    public void runEvaluation(List<String> modes, boolean clearCache, Consumer<Map<String, Object>> onEvent) {
        corpusService.ensureIngested(onEvent);

        List<String> effectiveModes = (modes == null || modes.isEmpty())
            ? List.of("hybrid", "vector", "hybrid-rerank")
            : modes.stream().map(this::normalizeMode).distinct().toList();

        if (clearCache) {
            cacheService.clear();
        }

        List<EvaluationQuestion> questions = loadQuestions();
        Embedder embedder = new Embedder();

        Map<String, List<EvaluationQuestionResult>> allResults = new LinkedHashMap<>();
        List<EvaluationSummary> summaries = new ArrayList<>();

        emit(onEvent, Map.of("type", "start", "modes", effectiveModes, "totalQuestions", questions.size()));

        int modeIndex = 0;
        for (String mode : effectiveModes) {
            emit(onEvent, Map.of("type", "mode_start", "mode", mode,
                "index", modeIndex, "totalModes", effectiveModes.size()));

            List<EvaluationQuestionResult> modeResults = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) {
                EvaluationQuestion q = questions.get(i);
                emit(onEvent, Map.of("type", "question_start", "mode", mode,
                    "questionId", q.getId(), "index", i, "total", questions.size(),
                    "question", q.getQuestion()));

                EvaluationQuestionResult result;
                try {
                    result = evaluateOne(q, mode, embedder);
                } catch (Exception e) {
                    result = new EvaluationQuestionResult();
                    result.setQuestionId(q.getId());
                    result.setQuestion(q.getQuestion());
                    result.setLanguage(q.getLanguage());
                    result.setExpectedType(q.getExpectedType());
                    result.setError(e.getMessage() == null ? "unknown" : e.getMessage());
                    emit(onEvent, Map.of("type", "question_error", "mode", mode,
                        "questionId", q.getId(), "index", i, "total", questions.size(),
                        "question", q.getQuestion(),
                        "message", e.getMessage() == null ? "unknown" : e.getMessage()));
                }
                modeResults.add(result);
                emit(onEvent, Map.of("type", "question_done", "mode", mode,
                    "questionId", q.getId(), "index", i, "total", questions.size(),
                    "result", result));
            }

            EvaluationSummary summary = summarize(mode, modeResults);
            summaries.add(summary);
            allResults.put(mode, modeResults);
            emit(onEvent, Map.of("type", "mode_done", "mode", mode, "summary", summary));
            modeIndex++;
        }

        EvaluationReport report = new EvaluationReport();
        report.setModes(effectiveModes);
        report.setSummaries(summaries);
        report.setResults(allResults);
        saveReport(report);
        emit(onEvent, Map.of("type", "done", "report", report));
    }

    public List<EvaluationRunMeta> listRuns() {
        return runRepo.findAllByOrderByIdDesc().stream()
            .map(r -> {
                try {
                    List<String> modes = objectMapper.readValue(r.getModes(), new TypeReference<List<String>>() {});
                    return new EvaluationRunMeta(r.getId(), r.getCreatedAt(), modes);
                } catch (Exception e) {
                    return new EvaluationRunMeta(r.getId(), r.getCreatedAt(), List.of());
                }
            })
            .toList();
    }

    public EvaluationReport getRun(Long id) {
        return runRepo.findById(id)
            .map(r -> {
                try {
                    return objectMapper.readValue(r.getReportJson(), EvaluationReport.class);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load evaluation report " + id, e);
                }
            })
            .orElse(null);
    }

    private void saveReport(EvaluationReport report) {
        try {
            EvaluationRun run = new EvaluationRun();
            run.setModes(objectMapper.writeValueAsString(report.getModes()));
            run.setReportJson(objectMapper.writeValueAsString(report));
            runRepo.save(run);
        } catch (Exception e) {
            System.err.println("Failed to persist evaluation report: " + e.getMessage());
        }
    }

    private String normalizeMode(String m) {
        if ("rerank".equalsIgnoreCase(m)) return "hybrid-rerank";
        return m.toLowerCase();
    }

    private EvaluationQuestionResult evaluateOne(EvaluationQuestion q, String mode, Embedder embedder) {
        long start = System.currentTimeMillis();
        ChatResponse resp = chatService.ask(q.getQuestion(), "eval-" + mode + "-" + q.getId(), mode);
        double latencyMs = System.currentTimeMillis() - start;

        List<String> snippets = resp.getSources() == null ? List.of()
            : resp.getSources().stream().map(Source::getSnippet)
                .filter(s -> s != null && !s.isBlank()).toList();

        // Pre-warm the embedding cache for everything this question needs.
        List<String> toEmbed = new ArrayList<>();
        if (resp.getContent() != null && !resp.getContent().isBlank()) toEmbed.add(resp.getContent());
        toEmbed.add(q.getQuestion());
        toEmbed.addAll(snippets);
        embedder.embedAll(toEmbed);

        EvaluationQuestionResult r = new EvaluationQuestionResult();
        r.setQuestionId(q.getId());
        r.setQuestion(q.getQuestion());
        r.setLanguage(q.getLanguage());
        r.setExpectedType(q.getExpectedType());
        r.setAnswer(resp.getContent());
        r.setRetrievalMode(resp.getRetrievalMode());
        r.setRefusal(resp.isRefusal());
        r.setSources(resp.getSources());
        r.setLatencyMs(round(latencyMs, 1));

        r.setFaithfulness(round(faithfulness(resp.getContent(), resp.isRefusal(), snippets, embedder), 3));
        r.setContextPrecision(round(contextPrecision(snippets, q.getQuestion(), embedder), 3));
        r.setAnswerCompliance(round(compliance(resp.getContent(), resp.isRefusal()), 3));
        r.setRefusalAppropriate(round(refusalAppropriate(resp.isRefusal(), q.getExpectedType()), 3));
        r.setStyleConsistent(round(style(resp.getContent()), 3));
        return r;
    }

    // ---- Metrics (ported from evaluation/evaluate.py) ----

    private double faithfulness(String content, boolean refusal, List<String> snippets, Embedder embedder) {
        if (content == null || content.isBlank() || refusal || snippets.isEmpty()) return 0.0;

        double sem;
        List<Double> av = embedder.embed(content);
        if (av != null) {
            double best = -1;
            for (String sn : snippets) {
                List<Double> sv = embedder.embed(sn);
                if (sv != null) best = Math.max(best, cosine(av, sv));
            }
            sem = best < 0 ? maxLexSimilarity(content, snippets) : best;
        } else {
            sem = maxLexSimilarity(content, snippets);
        }
        double lex = maxLexContainment(content, snippets);
        double grounding = Math.max(sem, lex);
        return Math.min(1.0, grounding / PARAPHRASE_CEILING);
    }

    private double contextPrecision(List<String> snippets, String question, Embedder embedder) {
        if (snippets.isEmpty()) return 0.0;

        List<Double> qv = embedder.embed(question);
        double relevant = 0;
        List<Double> precisions = new ArrayList<>();
        List<Double> flags = new ArrayList<>();
        for (int i = 0; i < snippets.size(); i++) {
            double rel;
            if (qv != null) {
                List<Double> sv = embedder.embed(snippets.get(i));
                rel = (sv != null && cosine(qv, sv) >= RELEVANCE_THRESHOLD) ? 1.0 : 0.0;
            } else {
                rel = lexicalSimilarity(question, snippets.get(i)) >= RELEVANCE_THRESHOLD ? 1.0 : 0.0;
            }
            relevant += rel;
            flags.add(rel);
            precisions.add(relevant / (i + 1));
        }
        if (relevant == 0) return 0.0;
        double ap = 0;
        for (int k = 0; k < snippets.size(); k++) {
            if (flags.get(k) > 0) ap += precisions.get(k);
        }
        return ap / relevant;
    }

    private double compliance(String content, boolean refusal) {
        if (content == null) return 0.0;
        content = content.trim();
        if (refusal || content.contains("无法") || content.toLowerCase().contains("cannot")) return 1.0;
        double score = 0.0;
        if (content.length() > 20) score += 0.3;
        if (content.length() > 60) score += 0.3;
        String lower = content.toLowerCase();
        boolean cited = content.contains("来源") || content.contains("根据") || content.contains("文档")
            || content.contains("《") || lower.contains("according") || lower.contains("based on");
        boolean markdown = content.contains("**") || content.contains("- ") || content.contains("##")
            || content.contains("\n1.") || content.contains("\n- ");
        if (cited) score += 0.2;
        if (markdown) score += 0.2;
        return Math.min(1.0, score);
    }

    private double refusalAppropriate(boolean refused, String expectedType) {
        boolean expectedRefusal = "safety_refusal".equals(expectedType);
        if (expectedRefusal) return refused ? 1.0 : 0.0;
        return refused ? 0.0 : 1.0;
    }

    private double style(String content) {
        if (content == null || content.length() < 20) return 0.5;
        if (content.contains("<") || content.contains("```")) return 0.7;
        return 0.9;
    }

    private EvaluationSummary summarize(String mode, List<EvaluationQuestionResult> results) {
        List<EvaluationQuestionResult> valid = results.stream().filter(r -> r.getError() == null).toList();
        EvaluationSummary s = new EvaluationSummary();
        s.setMode(mode);
        if (valid.isEmpty()) return s;

        List<EvaluationQuestionResult> answered = valid.stream().filter(r -> !r.isRefusal()).toList();
        List<Double> latencies = valid.stream().map(EvaluationQuestionResult::getLatencyMs).sorted().toList();
        int n = latencies.size();
        double p50 = latencies.get((int) (n * 0.5));
        double p95 = n > 1 ? latencies.get((int) (n * 0.95)) : latencies.get(0);
        int na = answered.size();

        s.setTotalQuestions(valid.size());
        s.setAnsweredQuestions(na);
        s.setAvgFaithfulness(na > 0 ? round(answered.stream().mapToDouble(EvaluationQuestionResult::getFaithfulness).sum() / na, 3) : 0.0);
        s.setAvgContextPrecision(na > 0 ? round(answered.stream().mapToDouble(EvaluationQuestionResult::getContextPrecision).sum() / na, 3) : 0.0);
        s.setAvgAnswerCompliance(round(valid.stream().mapToDouble(EvaluationQuestionResult::getAnswerCompliance).sum() / n, 3));
        s.setAvgRefusalAppropriate(round(valid.stream().mapToDouble(EvaluationQuestionResult::getRefusalAppropriate).sum() / n, 3));
        s.setAvgStyleConsistent(round(valid.stream().mapToDouble(EvaluationQuestionResult::getStyleConsistent).sum() / n, 3));
        s.setP50LatencyMs(round(p50, 1));
        s.setP95LatencyMs(round(p95, 1));
        s.setAvgLatencyMs(round(latencies.stream().mapToDouble(Double::doubleValue).sum() / n, 1));
        return s;
    }

    // ---- Embedding + similarity primitives ----

    private class Embedder {
        private final Map<String, List<Double>> cache = new HashMap<>();
        private boolean available = true;

        void embedAll(List<String> texts) {
            if (!available) return;
            List<String> missing = texts.stream()
                .filter(t -> t != null && !t.isBlank() && !cache.containsKey(t))
                .distinct().toList();
            for (int i = 0; i < missing.size(); i += 10) {
                List<String> chunk = missing.subList(i, Math.min(i + 10, missing.size()));
                try {
                    List<List<Double>> vecs = dashScope.embedBatch(chunk);
                    if (vecs == null || vecs.size() != chunk.size()) {
                        throw new IllegalStateException("embedBatch returned mismatched size");
                    }
                    for (int j = 0; j < chunk.size(); j++) cache.put(chunk.get(j), vecs.get(j));
                } catch (Exception e) {
                    available = false;
                    return;
                }
            }
        }

        List<Double> embed(String text) {
            if (text == null || text.isBlank()) return null;
            if (cache.containsKey(text)) return cache.get(text);
            if (!available) return null;
            try {
                List<Double> v = dashScope.embed(text);
                if (v == null || v.isEmpty()) return null;
                cache.put(text, v);
                return v;
            } catch (Exception e) {
                available = false;
                return null;
            }
        }
    }

    private static double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            double x = a.get(i), y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static Set<String> charBigrams(String text) {
        String t = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> bigrams = new HashSet<>();
        for (int i = 0; i < t.length() - 1; i++) {
            bigrams.add(t.substring(i, i + 2));
        }
        return bigrams;
    }

    private static double lexicalSimilarity(String a, String b) {
        Set<String> A = charBigrams(a), B = charBigrams(b);
        if (A.isEmpty() || B.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(A);
        inter.retainAll(B);
        return (double) inter.size() / Math.max(A.size(), B.size());
    }

    private static double lexicalContainment(String text, String context) {
        Set<String> T = charBigrams(text);
        if (T.isEmpty()) return 0.0;
        Set<String> C = charBigrams(context);
        Set<String> inter = new HashSet<>(T);
        inter.retainAll(C);
        return (double) inter.size() / T.size();
    }

    private static double maxLexSimilarity(String content, List<String> snippets) {
        return snippets.stream().mapToDouble(sn -> lexicalSimilarity(content, sn)).max().orElse(0.0);
    }

    private static double maxLexContainment(String content, List<String> snippets) {
        return snippets.stream().mapToDouble(sn -> lexicalContainment(content, sn)).max().orElse(0.0);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private void emit(Consumer<Map<String, Object>> onEvent, Map<String, Object> event) {
        try {
            onEvent.accept(event);
        } catch (Exception ignored) {
            // client disconnected mid-stream
        }
    }
}
