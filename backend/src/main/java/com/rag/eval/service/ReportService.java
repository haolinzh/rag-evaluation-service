package com.rag.eval.service;

import com.rag.eval.model.OpsMetrics;
import com.rag.eval.model.OpsReport;
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

    public OpsReport getSummary() {
        List<OpsMetrics> metrics = collector.snapshot();
        if (metrics.isEmpty()) {
            return new OpsReport(0, 0, 0, 0, 0, 0, 0, 0);
        }

        List<Long> latencies = metrics.stream()
            .map(OpsMetrics::getTotalLatencyMs).sorted().toList();
        List<Long> missLatencies = metrics.stream()
            .filter(m -> !m.isCacheHit())
            .map(OpsMetrics::getTotalLatencyMs).sorted().toList();
        long totalRequests = metrics.size();
        long cacheHits = metrics.stream().filter(OpsMetrics::isCacheHit).count();
        long refusals = metrics.stream().filter(OpsMetrics::isRefusal).count();
        long totalTokens = metrics.stream()
            .mapToLong(m -> m.getPromptTokens() + m.getCompletionTokens()).sum();

        return new OpsReport(
            totalRequests,
            percentile(latencies, 0.50),
            percentile(latencies, 0.95),
            percentile(missLatencies, 0.50),
            percentile(missLatencies, 0.95),
            totalTokens,
            totalRequests > 0 ? (double) cacheHits / totalRequests * 100 : 0,
            totalRequests > 0 ? (double) refusals / totalRequests * 100 : 0
        );
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

    private long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }
}
