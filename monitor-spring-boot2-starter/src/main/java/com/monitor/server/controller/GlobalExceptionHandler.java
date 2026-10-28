package com.monitor.server.controller;

import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.error("❌ [HTTP 方法不匹配] Method={}, URI={}, Query={}, ClientIP={}, SupportedMethods={}",
                request.getMethod(), request.getRequestURI(), request.getQueryString(), request.getRemoteAddr(),
                ex.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("code", 405, "message", ex.getMessage(), "supported", String.valueOf(ex.getSupportedHttpMethods())));
    }
}
