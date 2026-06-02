package com.fachat.agent.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
        ResponseStatusException exception,
        HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String message = status.is5xxServerError() ? "Internal server error" : safeReason(exception.getReason());
        if (status.is5xxServerError()) {
            logger.error("request_failed requestId={} status={} path={}", requestId(request), status.value(), request.getRequestURI());
        } else {
            logger.warn("request_rejected requestId={} status={} path={} reason={}",
                requestId(request), status.value(), request.getRequestURI(), safeReason(exception.getReason()));
        }
        return ResponseEntity.status(status).body(Map.of(
            "error", message,
            "requestId", requestId(request)
        ));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleValidation(Exception exception, HttpServletRequest request) {
        String details = "";
        if (exception instanceof MethodArgumentNotValidException methodException) {
            details = methodException.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
        }

        logger.warn(
            "validation_failed requestId={} path={} fields={}",
            requestId(request),
            request.getRequestURI(),
            details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "error", "Invalid request payload",
            "requestId", requestId(request)
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception exception, HttpServletRequest request) {
        logger.error(
            "internal_error requestId={} path={} message={}",
            requestId(request),
            request.getRequestURI(),
            exception.getMessage(),
            exception
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "error", "Internal server error",
            "requestId", requestId(request)
        ));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestContextKeys.REQUEST_ID);
        return value == null ? "" : String.valueOf(value);
    }

    private String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Request rejected";
        }
        return reason.length() > 180 ? reason.substring(0, 180) : reason;
    }
}
