package com.glicnote.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    final HttpStatus status;
    final String code;
    public ApiException(HttpStatus status, String code) { super(code); this.status=status; this.code=code; }
    static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "not_found"); }
    static ApiException invalid(String code) { return new ApiException(HttpStatus.BAD_REQUEST, code); }
    static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT, code); }
}
