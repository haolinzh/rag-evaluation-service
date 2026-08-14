package com.rag.eval.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DashScopeService {

    private final ConfigService config;

    public DashScopeService(ConfigService config) {
        this.config = config;
    }

    public record ChatResult(String content, int promptTokens, int completionTokens) {}

    public String getChatModel() {
        return config.get("dashscope.chat-model", "qwen-plus");
    }

    public ChatResult chat(String systemPrompt, String userMessage) {
        try {
            List<Message> messages = List.of(
                Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build(),
                Message.builder().role(Role.USER.getValue()).content(userMessage).build()
            );

            GenerationParam param = GenerationParam.builder()
                .model(getChatModel())
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .temperature(0.3f)
                .build();

            GenerationResult result = new Generation().call(param);
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();

            GenerationUsage usage = result.getUsage();
            int promptTokens = usage != null && usage.getInputTokens() != null ? usage.getInputTokens() : 0;
            int completionTokens = usage != null && usage.getOutputTokens() != null ? usage.getOutputTokens() : 0;

            return new ChatResult(content, promptTokens, completionTokens);
        } catch (NoApiKeyException | InputRequiredException e) {
            throw new RuntimeException("DASHSCOPE_API_KEY not set", e);
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
