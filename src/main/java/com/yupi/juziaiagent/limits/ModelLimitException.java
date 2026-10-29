package com.yupi.juziaiagent.limits;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 只携带可公开的固定提示，不包含用户输入或上游异常。 */
@Getter
public class ModelLimitException extends RuntimeException {
    private final HttpStatus status;
    private final int retryAfterSeconds;

    public ModelLimitException(HttpStatus status, String message) {
        this(status, message, 0);
    }

    public ModelLimitException(HttpStatus status, String message, int retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
