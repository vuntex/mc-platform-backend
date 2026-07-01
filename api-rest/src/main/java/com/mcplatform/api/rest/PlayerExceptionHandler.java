package com.mcplatform.api.rest;

import com.mcplatform.application.player.PlayerNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps player-read errors to HTTP status codes. Handles ONLY player-owned exceptions —
 * {@code PermissionDeniedException} → 403 and {@code IllegalArgumentException} → 400 (incl. an invalid
 * UUID in the path) are already mapped globally by the punishment/economy advices.
 */
@RestControllerAdvice
public class PlayerExceptionHandler {

    @ExceptionHandler(PlayerNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(PlayerNotFoundException e) {
        return Map.of("error", "player_not_found", "message", e.getMessage());
    }
}
