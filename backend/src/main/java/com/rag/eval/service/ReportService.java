package com.rag.eval.service;

import com.rag.eval.model.OpsMetrics;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.util.Collections;
import java.util.List;

@Service
public class ReportService {

    private final MetricsCollector collector;

    public ReportService(MetricsCollector collector) {
        this.collector = collector;
    }

    public String generateCsv() {
        List<OpsMetrics> metrics = collector.snapshot();
        if (metrics.isEmpty()) {
            return "No data available.";
        }

        try {
            StringWriter sw = new StringWriter();
            CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT.builder()
                .setHeader("requestId", "sessionId", "timestamp", "retrievalMode",
                    "retrievalLatencyMs", "generationLatencyMs", "totalLatencyMs",
                    "promptTokens", "completionTokens", "cacheHit", "refusal",
                    "refusalReason", "piiRedactions", "chunksRetrieved", "maxChunkScore")
                .build());

            for (OpsMetrics m : metrics) {
                csv.printRecord(
                    m.getRequestId(), m.getSessionId(), m.getTimestamp(), m.getRetrievalMode(),
                    m.getRetrievalLatencyMs(), m.getGenerationLatencyMs(), m.getTotalLatencyMs(),
                    m.getPromptTokens(), m.getCompletionTokens(), m.isCacheHit(), m.isRefusal(),
                    m.getRefusalReason() != null ? m.getRefusalReason() : "",
                    m.getPiiRedactions(), m.getChunksRetrieved(), m.getMaxChunkScore()
                );
            }

            csv.flush();
            sw.append("\n");

            // Summary
            List<Long> latencies = metrics.stream()
                .map(OpsMetrics::getTotalLatencyMs).sorted().toList();
            long p50 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.5));
            long p95 = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.95));
            long totalRequests = metrics.size();
            long cacheHits = metrics.stream().filter(OpsMetrics::isCacheHit).count();
            long refusals = metrics.stream().filter(OpsMetrics::isRefusal).count();
            long totalTokens = metrics.stream().mapToLong(m -> m.getPromptTokens() + m.getCompletionTokens()).sum();

            sw.append("\n# Summary\n");
            sw.append("# totalRequests,p50LatencyMs,p95LatencyMs,totalTokens,cacheHitRate,refusalRate\n");
            sw.append(String.format("# %d,%d,%d,%d,%.2f,%.2f\n",
                totalRequests, p50, p95, totalTokens,
                totalRequests > 0 ? (double) cacheHits / totalRequests : 0,
                totalRequests > 0 ? (double) refusals / totalRequests : 0));

            return sw.toString();
        } catch (Exception e) {
            return "Error generating report: " + e.getMessage();
        }
    }
}
