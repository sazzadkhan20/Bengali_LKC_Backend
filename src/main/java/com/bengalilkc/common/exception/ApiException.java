package com.bengalilkc.common.exception;

import java.util.Collections;
import java.util.Map;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final Map<String, String> errors;

    public ApiException(HttpStatus status, String error) {
        super(error);
        this.status = status;
        this.error = error;
        this.errors = null;
    }

    public ApiException(HttpStatus status, Map<String, String> errors) {
        super("Validation failed");
        this.status = status;
        this.error = null;
        this.errors = errors == null ? null : Collections.unmodifiableMap(errors);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException conflict(Map<String, String> errors) {
        return new ApiException(HttpStatus.CONFLICT, errors);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    public static ApiException unprocessable(Map<String, String> errors) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errors);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
