package com.example.translationlayer.controller;

import com.example.translationlayer.model.LoginRequest;
import com.example.translationlayer.model.LoginResponse;
import com.example.translationlayer.model.LoginResponse.UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication controller matching OpenSubtitles API.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    // Simple in-memory token storage (for demo purposes)
    private final Map<String, String> activeTokens = new ConcurrentHashMap<>();

    /**
     * POST /api/v1/login - Authenticate user and return JWT-like token.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest request,
            @RequestHeader(value = "Api-Key", required = false) String apiKey,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {

        log.info("Login attempt for user: {}", request.username());

        // For this translation service, we accept any credentials
        // In production, implement proper authentication
        String token = UUID.randomUUID().toString();
        activeTokens.put(token, request.username());

        UserInfo userInfo = new UserInfo(
                1000, // allowed_downloads
                1000, // allowed_translations
                "translator", // level
                1, // user_id
                false, // ext_installed
                true // vip
        );

        LoginResponse response = new LoginResponse(
                userInfo,
                "http://localhost:8080", // base_url
                token,
                200);

        log.info("Login successful for user: {}", request.username());
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/v1/logout - Log out user.
     */
    @DeleteMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            String username = activeTokens.remove(token);
            if (username != null) {
                log.info("Logged out user: {}", username);
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Logged out successfully",
                "status", 200));
    }

    /**
     * Validates a token and returns the associated username.
     */
    public String validateToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            return activeTokens.get(token);
        }
        return null;
    }
}
