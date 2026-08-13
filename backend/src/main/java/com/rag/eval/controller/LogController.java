package com.rag.eval.controller;

import com.rag.eval.model.RequestLog;
import com.rag.eval.repository.RequestLogRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final RequestLogRepo requestLogRepo;

    public LogController(RequestLogRepo requestLogRepo) {
        this.requestLogRepo = requestLogRepo;
    }

    @GetMapping
    public List<RequestLog> list(@RequestParam(defaultValue = "100") int limit) {
        int size = Math.max(1, Math.min(limit, 1000));
        return requestLogRepo.findAll(
            PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "id"))
        ).getContent();
    }

    @DeleteMapping
    public ResponseEntity<Map<String, String>> clear() {
        requestLogRepo.deleteAll();
        return ResponseEntity.ok(Map.of("message", "Logs cleared"));
    }
}
