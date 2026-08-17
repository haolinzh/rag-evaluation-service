package com.rag.eval.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class DashScopeService {

    private static final String TEXT_GEN_URL =
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    private final ConfigService config;
    private final ObjectMapper objectMapper;

    public DashScopeService(ConfigService config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public record ChatResult(String content, String thinking, int promptTokens, int completionTokens) {}

    public String getChatModel() {
        return config.get("dashscope.chat-model", "qwen-turbo");
    }

    public ChatResult chat(String systemPrompt, String userMessage) {
        try {
            String apiKey = resolveApiKey();

            List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
            );
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("result_format", "message");
            parameters.put("temperature", 0.3);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", getChatModel());
            body.put("input", Map.of("messages", messages));
            body.put("parameters", parameters);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TEXT_GEN_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("DashScope HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = "";
            String thinking = "";
            JsonNode choices = root.path("output").path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                content = message.path("content").asText("");
                thinking = message.path("reasoning_content").asText("");
            }
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("input_tokens").asInt(0);
            int completionTokens = usage.path("output_tokens").asInt(0);

            return new ChatResult(content, thinking, promptTokens, completionTokens);
        } catch (Exception e) {
            throw new RuntimeException("DashScope chat failed: " + e.getMessage(), e);
        }
    }

    private String resolveApiKey() {
        String apiKey = config.get("dashscope.api-key", "");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("DASHSCOPE_API_KEY not set");
        }
        return apiKey;
    }

    public ChatResult chatStream(String systemPrompt, String userMessage,
                                 Consumer<String> onThinking, Consumer<String> onContent) {
        try {
            String apiKey = resolveApiKey();

            List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
            );
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("result_format", "message");
            parameters.put("incremental_output", true);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", getChatModel());
            body.put("input", Map.of("messages", messages));
            body.put("parameters", parameters);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TEXT_GEN_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-SSE", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("DashScope HTTP " + response.statusCode() + ": " + err);
            }

            StringBuilder thinkingBuf = new StringBuilder();
            StringBuilder contentBuf = new StringBuilder();
            int promptTokens = 0;
            int completionTokens = 0;

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String json = line.substring(5).trim();
                if (json.isEmpty() || "[DONE]".equals(json)) continue;
                JsonNode root = objectMapper.readTree(json);
                JsonNode choices = root.path("output").path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode msg = choices.get(0).path("message");
                    String reasoning = msg.path("reasoning_content").asText("");
                    String content = msg.path("content").asText("");
                    if (!reasoning.isEmpty()) {
                        thinkingBuf.append(reasoning);
                        onThinking.accept(reasoning);
                    }
                    if (!content.isEmpty()) {
                        contentBuf.append(content);
                        onContent.accept(content);
                    }
                }
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    promptTokens = usage.path("input_tokens").asInt(promptTokens);
                    completionTokens = usage.path("output_tokens").asInt(completionTokens);
                }
            }

            return new ChatResult(contentBuf.toString(), thinkingBuf.toString(),
                promptTokens, completionTokens);
        } catch (Exception e) {
            throw new RuntimeException("DashScope stream failed: " + e.getMessage(), e);
        }
    }

    public List<Double> embed(String text) {
        try {
            TextEmbeddingParam param = TextEmbeddingParam.builder()
                .model(config.get("dashscope.embedding-model", "text-embedding-v3"))
                .texts(List.of(text))
                .build();
            TextEmbeddingResult result = new TextEmbedding().call(param);
            var embeddings = result.getOutput().getEmbeddings();
            if (embeddings == null || embeddings.isEmpty()) return List.of();
            List<Double> vec = new ArrayList<>();
            for (Double d : embeddings.get(0).getEmbedding()) vec.add(d);
            return vec;
        } catch (NoApiKeyException e) {
            throw new RuntimeException("DASHSCOPE_API_KEY not set", e);
        }
    }

    public List<List<Double>> embedBatch(List<String> texts) {
        try {
            TextEmbeddingParam param = TextEmbeddingParam.builder()
                .model(config.get("dashscope.embedding-model", "text-embedding-v3"))
                .texts(texts)
                .build();
            TextEmbeddingResult result = new TextEmbedding().call(param);
            var embeddings = result.getOutput().getEmbeddings();
            if (embeddings == null) return List.of();
            return embeddings.stream().map(e -> {
                List<Double> vec = new ArrayList<>();
                for (Double d : e.getEmbedding()) vec.add(d);
                return vec;
            }).toList();
        } catch (NoApiKeyException e) {
            throw new RuntimeException("DASHSCOPE_API_KEY not set", e);
        }
    }

    public static String embeddingToString(List<Double> embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}
