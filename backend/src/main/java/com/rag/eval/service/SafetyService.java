package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class SafetyService {

    private final ConfigService config;

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above|the\\s+above)\\s+(instructions|prompts|rules|context)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(disregard|override|forget)\\s+(all\\s+)?(previous\\s+)?(instructions|rules|system\\s+prompt)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(system\\s+prompt|system\\s+message)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(reveal|show|leak|print)\\s+(your\\s+)?(system\\s+prompt|instructions)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("忽略(之前|以上|上述|先前|所有)的?(指令|指示|规则|提示|设定)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(系统提示词|系统提示|system\\s*prompt)", Pattern.CASE_INSENSITIVE)
    );

    public SafetyService(ConfigService config) {
        this.config = config;
    }

    public enum Decision {
        ALLOW(null),
        REFUSE_LOW_CONFIDENCE("抱歉，我在知识库中没有找到足够相关的信息来回答您的问题。"),
        REFUSE_OUT_OF_SCOPE("您的问题超出了知识库的范围，请提出与知识库内容相关的问题。"),
        REFUSE_SAFETY_VIOLATION("抱歉，该问题包含不适当的内容，无法回答。"),
        REFUSE_PROMPT_INJECTION("抱歉，检测到可能试图更改系统指令的内容，请直接提出与知识库相关的问题。");

        public final String message;

        Decision(String message) { this.message = message; }
    }

    public SafetyResult evaluate(String question, List<SearchResult> chunks) {
        double minSimilarity = config.getDouble("safety.min-similarity", 0.4);
        boolean enableOutOfScopeCheck = config.getBool("safety.enable-out-of-scope-check", true);
        double outOfScopeThreshold = config.getDouble("safety.out-of-scope-threshold", 0.55);

        // 0. Minimal prompt-injection defense: reject attempts to override system instructions
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(question).find()) {
                return new SafetyResult(Decision.REFUSE_PROMPT_INJECTION, false);
            }
        }

        // 1. Check forbidden keywords
        for (String kw : config.getList("safety.forbidden-keywords")) {
            if (Pattern.compile(kw).matcher(question.toLowerCase()).find()) {
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
        // Semantic similarity (0..1) when present; RRF/rank score as fallback.
        return c.getConfidenceScore();
    }

    public record SafetyResult(SafetyService.Decision decision, boolean allowed) {}
}
