package com.rag.eval.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.rag.eval.model.SearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ElasticsearchService {

    private final ElasticsearchClient esClient;
    private final String esIndexName;

    public ElasticsearchService(ElasticsearchClient esClient,
                                @Value("${elasticsearch.index-name}") String esIndexName) {
        this.esClient = esClient;
        this.esIndexName = esIndexName;
    }

    public void deleteByFileName(String fileName) {
        try {
            esClient.deleteByQuery(d -> d
                .index(esIndexName)
                .query(q -> q.term(t -> t.field("file_name.keyword").value(fileName))));
        } catch (Exception e) {
            System.err.println("ES delete failed: " + e.getMessage());
        }
    }

    public List<SearchResult> keywordSearch(String query, int topK) {
        try {
            SearchRequest request = SearchRequest.of(s -> s
                .index(esIndexName)
                .query(q -> q
                    .match(m -> m.field("content").query(query)))
                .size(topK));

            SearchResponse<Map> response = esClient.search(request, Map.class);
            List<SearchResult> results = new ArrayList<>();

            for (var hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null) continue;

                double score = hit.score() != null ? hit.score() : 0.0;
                results.add(SearchResult.builder()
                    .chunkId((String) source.get("chunk_id"))
                    .fileName((String) source.get("file_name"))
                    .chapter((String) source.get("chapter"))
                    .section((String) source.get("section"))
                    .content((String) source.get("content"))
                    .score(score)
                    .source("keyword")
                    .sourceDetails(new SearchResult.SourceDetail(score, null, null))
                    .build());
            }
            return results;
        } catch (Exception e) {
            System.err.println("ES search failed: " + e.getMessage());
            return List.of();
        }
    }
}
