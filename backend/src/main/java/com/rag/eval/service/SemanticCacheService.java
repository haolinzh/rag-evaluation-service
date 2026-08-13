package com.rag.eval.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class SemanticCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final boolean enabled;
    private final long ttlSeconds;

    public SemanticCacheService(RedisTemplate<String, String> redisTemplate,
                                 @Value("${cache.semantic.enabled}") boolean enabled,
                                 @Value("${cache.semantic.ttl-seconds}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
    }

    public String lookup(String normalizedQuestion) {
        if (!enabled) return null;
        String key = cacheKey(normalizedQuestion);
        return redisTemplate.opsForValue().get(key);
    }

    public void store(String normalizedQuestion, String answer) {
        if (!enabled) return;
        String key = cacheKey(normalizedQuestion);
        redisTemplate.opsForValue().set(key, answer, ttlSeconds, TimeUnit.SECONDS);
    }

    private String cacheKey(String question) {
        // Simple normalization + hash for exact-match cache
        String normalized = question.toLowerCase().strip().replaceAll("\\s+", " ");
        return "cache:qa:" + Integer.toHexString(normalized.hashCode());
    }
}
