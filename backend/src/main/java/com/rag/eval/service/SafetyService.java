package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class SafetyService {

    private final double minSimilarity;
    private final boolean enableOutOfScopeCheck;
    private final double outOfScopeThreshold;
    private final List<Pattern> forbiddenPatterns;

    public SafetyService(@Value("${safety.min-similarity}") double minSimilarity,
                          @Value("${safety.enable-out-of-scope-check:false}") boolean enableOutOfScopeCheck,
                          @Value("${safety.out-of-scope-threshold:0.55}") double outOfScopeThreshold,
                          @Value("${safety.forbidden-keywords}") List<String> forbiddenKeywords) {
        this.minSimilarity = minSimilarity;
        this.enableOutOfScopeCheck = enableOutOfScopeCheck;
        this.outOfScopeThreshold = outOfScopeThreshold;
        this.forbiddenPatterns = forbiddenKeywords.stream()
            .map(Pattern::compile)
            .toList();
    }

    public enum Decision {
        ALLOW(null),
        REFUSE_LOW_CONFIDENCE("抱歉，我在知识库中没有找到足够相关的信息来回答您的问题。"),
        REFUSE_OUT_OF_SCOPE("您的问题超出了知识库的范围，请提出与知识库内容相关的问题。"),
        REFUSE_SAFETY_VIOLATION("抱歉，该问题包含不适当的内容，无法回答。");

        public final String message;

        Decision(String message) { this.message = message; }
    }

    public SafetyResult evaluate(String question, List<SearchResult> chunks) {
        // 1. Check forbidden keywords
        for (Pattern p : forbiddenPatterns) {
            if (p.matcher(question.toLowerCase()).find()) {
                return new SafetyResult(Decision.REFUSE_SAFETY_VIOLATION, false);
            }
        }

        // 2. Check confidence (max semantic similarity across chunks)
        double maxScore = chunks.stream()
            .mapToDouble(this::confidenceScore)
            .max().orElse(0.0);
        if (chunks.isEmpty() || maxScore < minSimilarity) {
            return new SafetyResult(Decision.REFUSE_LOW_CONFIDENCE, false);
        }

        // 3. Check out-of-scope: the question is syntactically fine and passes the
        //    confidence gate, but its best semantic match is only marginal — a strong
        //    signal the question belongs to a different domain than the knowledge base.
        if (enableOutOfScopeCheck && maxScore < outOfScopeThreshold) {
            return new SafetyResult(Decision.REFUSE_OUT_OF_SCOPE, false);
        }

        return new SafetyResult(Decision.ALLOW, true);
    }

    private double confidenceScore(SearchResult c) {
        // Prefer the raw semantic similarity (0..1) over the RRF fusion score,
        // which lives on a different scale (1/(k+rank)).
        var d = c.getSourceDetails();
        if (d != null && d.getSemanticScore() != null) return d.getSemanticScore();
        return c.getScore();
    }

    public record SafetyResult(SafetyService.Decision decision, boolean allowed) {}
}
