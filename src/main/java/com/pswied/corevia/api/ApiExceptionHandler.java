package com.pswied.corevia.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        String code = ex.getMessage().contains("Idempotency") ? "IDEMPOTENCY_CONFLICT" : "BUSINESS_ERROR";
        return build(HttpStatus.CONFLICT, code, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", errors);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        return build(status, code, message, List.of());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message, List<String> errors) {
        return ResponseEntity.status(status).body(Map.of(
            "code", code,
            "message", message,
            "errors", errors,
            "correlationId", "N/A",
            "timestamp", Instant.now().toString()
        ));
    }
}
