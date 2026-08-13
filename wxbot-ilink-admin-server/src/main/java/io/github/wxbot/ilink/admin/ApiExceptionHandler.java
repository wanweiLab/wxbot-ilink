/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import io.github.wxbot.ilink.manager.BotConflictException;
import io.github.wxbot.ilink.manager.BotNotFoundException;
import io.github.wxbot.ilink.manager.BotOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletionException;

/** 将管理异常映射为稳定且不泄露凭证的错误响应。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BotNotFoundException.class)
    ResponseEntity<ApiError> notFound(BotNotFoundException failure) {
        return response(HttpStatus.NOT_FOUND, "BOT_NOT_FOUND", failure.getMessage());
    }

    @ExceptionHandler(BotConflictException.class)
    ResponseEntity<ApiError> conflict(BotConflictException failure) {
        return response(HttpStatus.CONFLICT, "BOT_CONFLICT", failure.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException failure) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", failure.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException failure) {
        return response(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", failure.getMessage());
    }

    @ExceptionHandler({BotOperationException.class, CompletionException.class})
    ResponseEntity<ApiError> operationFailed(RuntimeException failure) {
        Throwable cause = unwrap(failure);
        String safeMessage = cause instanceof BotOperationException
                ? cause.getMessage() : "Bot 操作失败";
        LOGGER.error("Bot 管理接口执行失败", failure);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "BOT_OPERATION_FAILED", safeMessage);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ResponseEntity<ApiError> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }

    /** @param code 稳定错误码 @param message 安全错误说明 */
    public record ApiError(String code, String message) { }
}
