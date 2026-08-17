package com.rag.eval.controller;

import com.rag.eval.model.SystemConfigDto;
import com.rag.eval.service.ConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final String K_MODE = "retrieval.mode";
    private static final String K_TOP_K = "retrieval.top-k";
    private static final String K_RECALL = "retrieval.recall-size-multiplier";
    private static final String K_RRF_K = "retrieval.rrf-k";
    private static final String K_RERANK_CAND = "retrieval.rerank-candidates";
    private static final String K_SIM_THRESHOLD = "retrieval.similarity-threshold";
    private static final String K_CHAT_MODEL = "dashscope.chat-model";
    private static final String K_EMB_MODEL = "dashscope.embedding-model";
    private static final String K_RERANK_MODEL = "dashscope.rerank-model";
    private static final String K_MIN_SIM = "safety.min-similarity";
    private static final String K_OUT_OF_SCOPE_ENABLED = "safety.enable-out-of-scope-check";
    private static final String K_OUT_OF_SCOPE_THRESHOLD = "safety.out-of-scope-threshold";
    private static final String K_FORBIDDEN = "safety.forbidden-keywords";
    private static final String K_CACHE_ENABLED = "cache.semantic.enabled";
    private static final String K_CACHE_TTL = "cache.semantic.ttl-seconds";
    private static final String K_API_KEY = "dashscope.api-key";

    private static final int EMBEDDING_DIMENSION = 1024;

    private static final List<SystemConfigDto.ModelOption> MODEL_OPTIONS = List.of(
        new SystemConfigDto.ModelOption("chat", "qwen-turbo", "qwen-turbo", null),
        new SystemConfigDto.ModelOption("chat", "qwen-plus", "qwen-plus", null),
        new SystemConfigDto.ModelOption("chat", "qwen-max", "qwen-max", null),
        new SystemConfigDto.ModelOption("chat", "qwen-max-longcontext", "qwen-max-longcontext", null),
        new SystemConfigDto.ModelOption("chat", "qwen2.5-72b-instruct", "qwen2.5-72b-instruct", null),
        new SystemConfigDto.ModelOption("chat", "deepseek-r1", "DeepSeek R1 (深度思考)", null),
        new SystemConfigDto.ModelOption("chat", "qwen3-235b-a22b-thinking-2507", "Qwen3 235B Thinking (深度思考)", null),
        new SystemConfigDto.ModelOption("embedding", "text-embedding-v1", "text-embedding-v1", 1024),
        new SystemConfigDto.ModelOption("embedding", "text-embedding-v2", "text-embedding-v2", 1536),
        new SystemConfigDto.ModelOption("embedding", "text-embedding-v3", "text-embedding-v3", 1024),
        new SystemConfigDto.ModelOption("embedding", "text-embedding-v4", "text-embedding-v4", 2048),
        new SystemConfigDto.ModelOption("rerank", "qwen3-rerank", "qwen3-rerank", null),
        new SystemConfigDto.ModelOption("rerank", "gte-rerank", "gte-rerank", null)
    );

    private static final Set<String> MODES = Set.of("vector", "hybrid", "hybrid-rerank");

    private final ConfigService config;

    public ConfigController(ConfigService config) {
        this.config = config;
    }

    @GetMapping
    public SystemConfigDto get() {
        return buildDto();
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody SystemConfigDto dto) {
        String error = validate(dto);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }

        SystemConfigDto.Retrieval r = dto.retrieval();
        SystemConfigDto.Models m = dto.models();
        SystemConfigDto.Safety s = dto.safety();
        SystemConfigDto.Cache c = dto.cache();

        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(K_MODE, r.mode());
        changes.put(K_TOP_K, String.valueOf(r.topK()));
        changes.put(K_RECALL, String.valueOf(r.recallSizeMultiplier()));
        changes.put(K_RRF_K, String.valueOf(r.rrfK()));
        changes.put(K_RERANK_CAND, String.valueOf(r.rerankCandidates()));
        changes.put(K_SIM_THRESHOLD, String.valueOf(r.similarityThreshold()));
        changes.put(K_CHAT_MODEL, m.chat());
        changes.put(K_EMB_MODEL, m.embedding());
        changes.put(K_RERANK_MODEL, m.rerank());
        changes.put(K_MIN_SIM, String.valueOf(s.minSimilarity()));
        changes.put(K_OUT_OF_SCOPE_ENABLED, String.valueOf(s.enableOutOfScopeCheck()));
        changes.put(K_OUT_OF_SCOPE_THRESHOLD, String.valueOf(s.outOfScopeThreshold()));
        changes.put(K_FORBIDDEN, s.forbiddenKeywords());
        changes.put(K_CACHE_ENABLED, String.valueOf(c.enabled()));
        changes.put(K_CACHE_TTL, String.valueOf(c.ttlSeconds()));

        config.putAll(changes);
        return ResponseEntity.ok(buildDto());
    }

    @PutMapping("/mode")
    public ResponseEntity<?> updateMode(@RequestBody Map<String, String> body) {
        String mode = body == null ? null : body.get("mode");
        if (mode == null || !MODES.contains(mode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "非法检索模式: " + mode));
        }
        config.put(K_MODE, mode);
        return ResponseEntity.ok(buildDto());
    }

    @PutMapping("/apikey")
    public ResponseEntity<?> updateApiKey(@RequestBody(required = false) Map<String, String> body) {
        String apiKey = body == null ? null : body.get("apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            config.reset(K_API_KEY);
        } else {
            config.put(K_API_KEY, apiKey.trim());
        }
        return ResponseEntity.ok(buildDto());
    }

    private SystemConfigDto buildDto() {
        return new SystemConfigDto(
            new SystemConfigDto.Retrieval(
                config.get(K_MODE, "hybrid"),
                config.getInt(K_TOP_K, 5),
                config.getInt(K_RECALL, 3),
                config.getInt(K_RRF_K, 60),
                config.getInt(K_RERANK_CAND, 20),
                config.getDouble(K_SIM_THRESHOLD, 0.4)),
            new SystemConfigDto.Models(
                config.get(K_CHAT_MODEL, "qwen-turbo"),
                config.get(K_EMB_MODEL, "text-embedding-v3"),
                config.get(K_RERANK_MODEL, "qwen3-rerank")),
            new SystemConfigDto.Safety(
                config.getDouble(K_MIN_SIM, 0.4),
                config.getBool(K_OUT_OF_SCOPE_ENABLED, true),
                config.getDouble(K_OUT_OF_SCOPE_THRESHOLD, 0.55),
                config.get(K_FORBIDDEN, "")),
            new SystemConfigDto.Cache(
                config.getBool(K_CACHE_ENABLED, true),
                config.getInt(K_CACHE_TTL, 3600)),
            MODEL_OPTIONS,
            EMBEDDING_DIMENSION,
            maskApiKey(config.get(K_API_KEY, "")));
    }

    private String maskApiKey(String key) {
        if (key == null || key.isBlank()) return null;
        if (key.length() <= 8) return key.substring(0, 1) + "****";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    private String validate(SystemConfigDto dto) {
        if (dto == null || dto.retrieval() == null || dto.models() == null
                || dto.safety() == null || dto.cache() == null) {
            return "配置不完整";
        }
        SystemConfigDto.Retrieval r = dto.retrieval();
        if (!MODES.contains(r.mode())) return "非法检索模式: " + r.mode();
        if (r.topK() <= 0 || r.recallSizeMultiplier() <= 0 || r.rrfK() <= 0 || r.rerankCandidates() <= 0) {
            return "topK / recallMultiplier / rrfK / rerankCandidates 必须为正数";
        }
        if (!inRange(r.similarityThreshold())) return "similarityThreshold 必须在 [0,1]";

        SystemConfigDto.Safety s = dto.safety();
        if (!inRange(s.minSimilarity()) || !inRange(s.outOfScopeThreshold())) {
            return "安全阈值必须在 [0,1]";
        }

        SystemConfigDto.Cache c = dto.cache();
        if (c.ttlSeconds() <= 0) return "缓存 TTL 必须为正数";

        if (!isAllowedModel("chat", dto.models().chat())) return "不支持的对话模型: " + dto.models().chat();
        if (!isAllowedModel("embedding", dto.models().embedding())) return "不支持的向量模型: " + dto.models().embedding();
        if (!isAllowedModel("rerank", dto.models().rerank())) return "不支持的重排模型: " + dto.models().rerank();
        return null;
    }

    private boolean inRange(double v) {
        return v >= 0.0 && v <= 1.0;
    }

    private boolean isAllowedModel(String group, String id) {
        return MODEL_OPTIONS.stream()
            .anyMatch(o -> o.group().equals(group) && o.id().equals(id));
    }
}
