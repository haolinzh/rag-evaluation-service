package com.rag.eval.service;

import com.rag.eval.model.OpsReport;
import com.rag.eval.model.RequestLog;
import com.rag.eval.repository.RequestLogRepo;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.util.List;

@Service
public class ReportService {

    private final RequestLogRepo requestLogRepo;

    public ReportService(RequestLogRepo requestLogRepo) {
        this.requestLogRepo = requestLogRepo;
    }

    public OpsReport getSummary() {
        List<RequestLog> logs = requestLogRepo.findAll();
        if (logs.isEmpty()) {
            return new OpsReport(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        List<Long> latencies = logs.stream()
            .map(RequestLog::getResponseTimeMs).sorted().toList();
        List<Long> missLatencies = logs.stream()
            .filter(l -> !l.isCacheHit())
            .map(RequestLog::getResponseTimeMs).sorted().toList();
        long totalRequests = logs.size();
        long cacheHits = logs.stream().filter(RequestLog::isCacheHit).count();
        long refusals = logs.stream().filter(RequestLog::isRefusal).count();
        long totalTokens = logs.stream()
            .mapToLong(l -> l.getPromptTokens() + l.getCompletionTokens()).sum();
        double complianceRate = logs.stream()
            .mapToDouble(l -> complianceScore(l.getAnswer(), l.isRefusal()))
            .average().orElse(0.0) * 100;

        return new OpsReport(
            totalRequests,
            percentile(latencies, 0.50),
            percentile(latencies, 0.95),
            percentile(missLatencies, 0.50),
            percentile(missLatencies, 0.95),
            totalTokens,
            totalRequests > 0 ? (double) cacheHits / totalRequests * 100 : 0,
            totalRequests > 0 ? (double) refusals / totalRequests * 100 : 0,
            complianceRate
        );
    }

    public String generateCsv() {
        List<RequestLog> logs = requestLogRepo.findAll();
        if (logs.isEmpty()) {
            return "No data available.";
        }

        try {
            StringWriter sw = new StringWriter();
            CSVPrinter csv = new CSVPrinter(sw, CSVFormat.DEFAULT.builder()
                .setHeader("requestId", "sessionId", "createdAt", "retrievalMode",
                    "retrievalLatencyMs", "generationLatencyMs", "totalLatencyMs",
                    "promptTokens", "completionTokens", "cacheHit", "refusal",
                    "refusalReason", "piiRedactions", "chunksRetrieved", "maxChunkScore",
                    "answerCompliance", "status")
                .build());

            for (RequestLog l : logs) {
                csv.printRecord(
                    l.getRequestId(), l.getSessionId(), l.getCreatedAt(), l.getRetrievalMode(),
                    l.getRetrievalLatencyMs(), l.getGenerationLatencyMs(), l.getResponseTimeMs(),
                    l.getPromptTokens(), l.getCompletionTokens(), l.isCacheHit(), l.isRefusal(),
                    l.getRefusalReason() != null ? l.getRefusalReason() : "",
                    l.getPiiRedactions(), l.getChunksRetrieved(), l.getMaxChunkScore(),
                    complianceScore(l.getAnswer(), l.isRefusal()), l.getStatus()
                );
            }

            csv.flush();
            sw.append("\n");

            List<Long> latencies = logs.stream()
                .map(RequestLog::getResponseTimeMs).sorted().toList();
            long totalRequests = logs.size();
            long cacheHits = logs.stream().filter(RequestLog::isCacheHit).count();
            long refusals = logs.stream().filter(RequestLog::isRefusal).count();
            long totalTokens = logs.stream().mapToLong(l -> l.getPromptTokens() + l.getCompletionTokens()).sum();
            double complianceRate = logs.stream()
                .mapToDouble(l -> complianceScore(l.getAnswer(), l.isRefusal()))
                .average().orElse(0.0) * 100;

            sw.append("\n# Summary\n");
            sw.append("# totalRequests,p50LatencyMs,p95LatencyMs,totalTokens,cacheHitRate,refusalRate,answerComplianceRate\n");
            sw.append(String.format("# %d,%d,%d,%d,%.2f,%.2f,%.2f\n",
                totalRequests,
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                totalTokens,
                totalRequests > 0 ? (double) cacheHits / totalRequests : 0,
                totalRequests > 0 ? (double) refusals / totalRequests : 0,
                complianceRate));

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

    private static double complianceScore(String answer, boolean refusal) {
        if (refusal) return 1.0;
        if (answer == null || answer.isBlank()) return 0.0;
        double score = 0.0;
        if (answer.length() > 10) score += 0.3;
        if (answer.length() > 50) score += 0.3;
        String lower = answer.toLowerCase();
        if (answer.contains("来源") || answer.contains("根据") || answer.contains("文档")
                || lower.contains("knowledge")) {
            score += 0.2;
        }
        return Math.min(1.0, score);
    }
}
