package com.rag.eval.service;

import com.rag.eval.model.OpsMetrics;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class MetricsCollector {

    public OpsMetrics startRequest(String sessionId, String retrievalMode) {
        OpsMetrics m = new OpsMetrics();
        m.setRequestId(UUID.randomUUID().toString());
        m.setSessionId(sessionId);
        m.setTimestamp(Instant.now());
        m.setRetrievalMode(retrievalMode);
        return m;
    }

    public void complete(OpsMetrics m) {
        // Metrics are persisted to request_log by ChatService.logRequest; nothing in-memory to update.
    }
}
