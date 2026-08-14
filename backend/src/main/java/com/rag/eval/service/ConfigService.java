package com.rag.eval.service;

import com.rag.eval.model.SystemConfig;
import com.rag.eval.repository.SystemConfigRepo;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConfigService {

    private final Environment env;
    private final SystemConfigRepo repo;
    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    public ConfigService(Environment env, SystemConfigRepo repo) {
        this.env = env;
        this.repo = repo;
    }

    @PostConstruct
    public void load() {
        for (SystemConfig c : repo.findAll()) {
            overrides.put(c.getKey(), c.getValue());
        }
    }

    public String get(String key, String defaultValue) {
        String v = overrides.get(key);
        if (v != null) return v;
        if (env != null) {
            String p = env.getProperty(key);
            if (p != null) return p;
        }
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key, null);
        return v == null ? defaultValue : Integer.parseInt(v.trim());
    }

    public double getDouble(String key, double defaultValue) {
        String v = get(key, null);
        return v == null ? defaultValue : Double.parseDouble(v.trim());
    }

    public boolean getBool(String key, boolean defaultValue) {
        String v = get(key, null);
        return v == null ? defaultValue : Boolean.parseBoolean(v.trim());
    }

    public List<String> getList(String key) {
        String v = get(key, "");
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    public void put(String key, String value) {
        overrides.put(key, value);
        if (repo != null) {
            SystemConfig c = repo.findById(key).orElseGet(SystemConfig::new);
            c.setKey(key);
            c.setValue(value);
            repo.save(c);
        }
    }

    public void putAll(Map<String, String> changes) {
        changes.forEach(this::put);
    }

    public void reset(String key) {
        overrides.remove(key);
        if (repo != null) {
            repo.deleteById(key);
        }
    }

    public Map<String, String> overrides() {
        return new HashMap<>(overrides);
    }
}
