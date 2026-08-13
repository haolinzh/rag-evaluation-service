package com.rag.eval.controller;

import com.rag.eval.model.ChatMessage;
import com.rag.eval.model.ChatRequest;
import com.rag.eval.model.ChatResponse;
import com.rag.eval.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        ChatResponse response = chatService.ask(request.getQuestion(), request.getSessionId(), request.getMode());
        return ResponseEntity.ok(response);
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
