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

    public String lookup(String normalizedQuestion, String mode) {
        if (!enabled) return null;
        String key = cacheKey(normalizedQuestion, mode);
        return redisTemplate.opsForValue().get(key);
    }

    public void store(String normalizedQuestion, String mode, String answer) {
        if (!enabled) return;
        String key = cacheKey(normalizedQuestion, mode);
        redisTemplate.opsForValue().set(key, answer, ttlSeconds, TimeUnit.SECONDS);
    }

    public void clear() {
        Set<String> keys = redisTemplate.keys("cache:qa:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private String cacheKey(String question, String mode) {
        // Simple normalization + hash for exact-match cache, keyed by retrieval mode
        String normalized = question.toLowerCase().strip().replaceAll("\\s+", " ");
        return "cache:qa:" + Integer.toHexString((normalized + "|" + mode).hashCode());
    }
}
