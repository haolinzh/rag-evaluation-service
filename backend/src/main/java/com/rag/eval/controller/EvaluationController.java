package com.rag.eval.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.eval.model.EvaluationQuestion;
import com.rag.eval.model.EvaluationReport;
import com.rag.eval.model.EvaluationRequest;
import com.rag.eval.model.EvaluationRunMeta;
import com.rag.eval.service.EvaluationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public EvaluationController(EvaluationService evaluationService, ObjectMapper objectMapper) {
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/questions")
    public List<EvaluationQuestion> questions() {
        return evaluationService.loadQuestions();
    }

    @GetMapping("/history")
    public List<EvaluationRunMeta> history() {
        return evaluationService.listRuns();
    }

    @GetMapping("/history/{id}")
    public EvaluationReport historyDetail(@PathVariable Long id) {
        return evaluationService.getRun(id);
    }

    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody(required = false) EvaluationRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        List<String> modes = request == null ? null : request.getModes();
        boolean clearCache = request == null || request.isClearCache();
        executor.execute(() -> {
            try {
                evaluationService.runEvaluation(modes, clearCache, event -> send(emitter, event));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, Map<String, Object> event) {
        try {
            emitter.send(objectMapper.writeValueAsString(event));
        } catch (Exception ignored) {
            // client disconnected mid-run
        }
    }
}
