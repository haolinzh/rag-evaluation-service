package com.rag.eval.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DashScopeService {

    private final String chatModel;
    private final String embeddingModel;

    public DashScopeService(@Value("${dashscope.chat-model}") String chatModel,
                             @Value("${dashscope.embedding-model}") String embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    public String chat(String systemPrompt, String userMessage) {
        try {
            List<Message> messages = List.of(
                Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build(),
                Message.builder().role(Role.USER.getValue()).content(userMessage).build()
            );

            GenerationParam param = GenerationParam.builder()
                .model(chatModel)
                .messages(messages)
                .temperature(0.3f)
                .build();

            GenerationResult result = new Generation().call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent();
        } catch (NoApiKeyException | InputRequiredException e) {
            throw new RuntimeException("DASHSCOPE_API_KEY not set", e);
        }
    }

    public List<Double> embed(String text) {
        try {
            TextEmbeddingParam param = TextEmbeddingParam.builder()
                .model(embeddingModel)
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
                .model(embeddingModel)
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
