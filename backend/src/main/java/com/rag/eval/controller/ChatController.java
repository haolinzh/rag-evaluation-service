package com.rag.eval.controller;

import com.rag.eval.model.ChatMessage;
import com.rag.eval.model.ChatRequest;
import com.rag.eval.model.ChatResponse;
import com.rag.eval.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        ChatResponse response = chatService.ask(request.getQuestion(), request.getSessionId(), request.getMode());
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> chatService.streamAsk(
            request.getQuestion(), request.getSessionId(), request.getMode(), emitter));
        return emitter;
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<ChatMessage>> history(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatService.getHistory(sessionId));
    }

    @DeleteMapping("/history/{sessionId}")
    public ResponseEntity<Void> deleteHistory(@PathVariable String sessionId) {
        chatService.deleteHistory(sessionId);
        return ResponseEntity.noContent().build();
    }
}
