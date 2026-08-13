package com.rag.eval.service;

import com.rag.eval.model.OpsMetrics;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class MetricsCollector {

    private final ConcurrentMap<String, OpsMetrics> metrics = new ConcurrentHashMap<>();

    public OpsMetrics startRequest(String sessionId, String retrievalMode) {
        OpsMetrics m = new OpsMetrics();
        m.setRequestId(UUID.randomUUID().toString());
        m.setSessionId(sessionId);
        m.setTimestamp(Instant.now());
        m.setRetrievalMode(retrievalMode);
        metrics.put(m.getRequestId(), m);
        return m;
    }

    public void complete(OpsMetrics m) {
        metrics.put(m.getRequestId(), m);
    }

    public List<OpsMetrics> snapshot() {
        return new ArrayList<>(metrics.values());
    }

    public void clear() {
        metrics.clear();
    }
}
