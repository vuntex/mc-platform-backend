package com.mcplatform.api.rest;

import com.mcplatform.application.report.port.ReportConflictException;
import com.mcplatform.application.report.port.ReportCooldownException;
import com.mcplatform.application.report.port.ReportNotFoundException;
import com.mcplatform.domain.report.InvalidStatusTransitionException;
import com.mcplatform.domain.report.ReportValidationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps report-specific errors to HTTP status codes. Deliberately handles ONLY report-owned exceptions —
 * {@code PermissionDeniedException} → 403 and {@code IllegalArgumentException} → 400 are already mapped
 * globally (by the punishment/economy advices); re-declaring them here would be an ambiguous-mapping
 * conflict.
 */
@RestControllerAdvice
public class ReportExceptionHandler {

    @ExceptionHandler(ReportValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> validation(ReportValidationException e) {
        return Map.of("error", "report_invalid", "message", e.getMessage());
    }

    @ExceptionHandler(ReportCooldownException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, String> cooldown(ReportCooldownException e) {
        return Map.of("error", "report_cooldown", "message", e.getMessage());
    }

    @ExceptionHandler(ReportNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(ReportNotFoundException e) {
        return Map.of("error", "report_not_found", "message", e.getMessage());
    }

    @ExceptionHandler({InvalidStatusTransitionException.class, ReportConflictException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(RuntimeException e) {
        return Map.of("error", "report_conflict", "message", e.getMessage());
    }
}
