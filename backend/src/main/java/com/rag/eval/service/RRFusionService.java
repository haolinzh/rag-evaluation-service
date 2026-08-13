package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RRFusionService {

    private final int rrfK;

    public RRFusionService(@Value("${retrieval.rrf-k}") int rrfK) {
        this.rrfK = rrfK;
    }

    public List<SearchResult> fuse(List<SearchResult> keywordResults,
                                    List<SearchResult> vectorResults, int topK) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, SearchResult> docLookup = new LinkedHashMap<>();

        // Keyword results: rank 1, 2, 3, ...
        for (int i = 0; i < keywordResults.size(); i++) {
            SearchResult r = keywordResults.get(i);
            double contribution = 1.0 / (rrfK + i + 1);
            rrfScores.merge(r.getChunkId(), contribution, Double::sum);
            docLookup.putIfAbsent(r.getChunkId(), r);
        }

        // Vector results: rank 1, 2, 3, ...
        for (int i = 0; i < vectorResults.size(); i++) {
            SearchResult r = vectorResults.get(i);
            double contribution = 1.0 / (rrfK + i + 1);
            rrfScores.merge(r.getChunkId(), contribution, Double::sum);
            docLookup.putIfAbsent(r.getChunkId(), r);
        }

        // Sort by RRF score descending, take topK
        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(entry -> {
                SearchResult original = docLookup.get(entry.getKey());
                return SearchResult.builder()
                    .chunkId(original.getChunkId())
                    .fileName(original.getFileName())
                    .chapter(original.getChapter())
                    .section(original.getSection())
                    .content(original.getContent())
                    .score(entry.getValue())
                    .source("rrf")
                    .sourceDetails(new SearchResult.SourceDetail(
                        original.getSourceDetails() != null ? original.getSourceDetails().getKeywordScore() : null,
                        original.getSourceDetails() != null ? original.getSourceDetails().getSemanticScore() : null,
                        entry.getValue()))
                    .build();
            })
            .collect(Collectors.toList());
    }
}
