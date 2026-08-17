package com.rag.eval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.eval.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private static final String RERANK_URL =
        "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final ConfigService config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RerankService(ConfigService config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();

        int limit = Math.min(topK, candidates.size());
        List<String> documents = candidates.stream().map(SearchResult::getContent).toList();

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                "model", config.get("dashscope.rerank-model", "qwen3-rerank"),
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("top_n", limit, "return_documents", false)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RERANK_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + resolveApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Rerank API returned {}: {}", response.statusCode(), response.body());
                return candidates.subList(0, limit);
            }

            JsonNode results = objectMapper.readTree(response.body()).path("output").path("results");
            List<SearchResult> reranked = new ArrayList<>();
            for (JsonNode r : results) {
                int index = r.path("index").asInt(-1);
                double score = r.path("relevance_score").asDouble(0.0);
                if (index < 0 || index >= candidates.size()) continue;

                SearchResult original = candidates.get(index);
                reranked.add(SearchResult.builder()
                    .chunkId(original.getChunkId())
                    .fileName(original.getFileName())
                    .chapter(original.getChapter())
                    .section(original.getSection())
                    .content(original.getContent())
                    .score(score)
                    .source("rerank")
                    .sourceDetails(new SearchResult.SourceDetail(
                        original.getSourceDetails() != null ? original.getSourceDetails().getKeywordScore() : null,
                        original.getSourceDetails() != null ? original.getSourceDetails().getSemanticScore() : null,
                        score))
                    .build());
                if (reranked.size() >= limit) break;
            }
            return reranked;
        } catch (Exception e) {
            log.warn("Rerank failed, falling back to RRF order: {}", e.getMessage());
            return candidates.subList(0, limit);
        }
    }

    private String resolveApiKey() {
        String key = config.get("dashscope.api-key", "");
        if (key == null || key.isBlank()) {
            key = System.getenv("DASHSCOPE_API_KEY");
        }
        if (key == null || key.isBlank()) {
            throw new RuntimeException("DASHSCOPE_API_KEY not set");
        }
        return key;
    }
}
