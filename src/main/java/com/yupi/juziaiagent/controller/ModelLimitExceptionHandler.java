package com.yupi.juziaiagent.controller;

import com.yupi.juziaiagent.limits.ModelLimitException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;

@RestControllerAdvice(assignableTypes = AiController.class)
public class ModelLimitExceptionHandler {
    @ExceptionHandler(ModelLimitException.class)
    public ResponseEntity<String> handle(ModelLimitException error) {
        var response = ResponseEntity.status(error.getStatus())
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        if (error.getRetryAfterSeconds() > 0) {
            response.header("Retry-After", Integer.toString(error.getRetryAfterSeconds()));
        }
        return response.body(error.getMessage());
    }
}
