package com.s2p.core.web;

import com.s2p.contracts.SourceUnavailableException;
import com.s2p.core.governance.UnsupportedCommitmentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ErrorAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler({IllegalStateException.class, UnsupportedCommitmentException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return body(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
        return body(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler(SourceUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(SourceUnavailableException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e);
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus s, RuntimeException e) {
        return ResponseEntity.status(s).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
