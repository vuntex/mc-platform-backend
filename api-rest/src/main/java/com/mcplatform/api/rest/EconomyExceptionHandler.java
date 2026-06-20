package com.mcplatform.api.rest;

import com.mcplatform.application.economy.port.ConcurrencyConflictException;
import com.mcplatform.domain.economy.InsufficientFundsException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps economy domain/application errors to HTTP status codes. */
@RestControllerAdvice
public class EconomyExceptionHandler {

    @ExceptionHandler(InsufficientFundsException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> insufficientFunds(InsufficientFundsException e) {
        return Map.of("error", "insufficient_funds", "message", e.getMessage());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> concurrencyConflict(ConcurrencyConflictException e) {
        return Map.of("error", "concurrency_conflict", "message", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", "bad_request", "message", e.getMessage());
    }
}
