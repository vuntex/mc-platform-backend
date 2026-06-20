package com.mcplatform.api.rest;

import com.mcplatform.application.punishment.port.PunishmentNotFoundException;
import com.mcplatform.application.punishment.port.PunishmentTemplateNotFoundException;
import com.mcplatform.application.security.PermissionDeniedException;
import com.mcplatform.domain.punishment.PunishmentConflictException;
import com.mcplatform.domain.punishment.PunishmentValidationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps punishment domain/application errors to HTTP status codes, consistent with
 * {@code EconomyExceptionHandler} (which still owns the generic {@code IllegalArgumentException} → 400).
 */
@RestControllerAdvice
public class PunishmentExceptionHandler {

    @ExceptionHandler(PermissionDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> permissionDenied(PermissionDeniedException e) {
        return Map.of("error", "permission_denied", "message", e.getMessage());
    }

    @ExceptionHandler(PunishmentConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(PunishmentConflictException e) {
        return Map.of("error", "punishment_conflict", "message", e.getMessage());
    }

    @ExceptionHandler({PunishmentNotFoundException.class, PunishmentTemplateNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(RuntimeException e) {
        return Map.of("error", "not_found", "message", e.getMessage());
    }

    @ExceptionHandler(PunishmentValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> validation(PunishmentValidationException e) {
        return Map.of("error", "punishment_invalid", "message", e.getMessage());
    }
}
