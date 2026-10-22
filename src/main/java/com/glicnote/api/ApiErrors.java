package com.glicnote.api;

import jakarta.validation.ConstraintViolationException;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> domain(ApiException e) { return problem(e.status,e.code,null); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        var fields=new TreeSet<String>();
        e.getBindingResult().getFieldErrors().forEach(error -> fields.add(error.getField()));
        return problem(HttpStatus.BAD_REQUEST,"validation_failed",fields);
    }
    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> malformed(Exception e) { return problem(HttpStatus.BAD_REQUEST,"invalid_request",null); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException e) {
        return problem(HttpStatus.CONFLICT,"constraint_conflict",null);
    }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String code,Object fields) {
        var detail=ProblemDetail.forStatus(status);
        detail.setTitle(code); detail.setProperty("code",code);
        if(fields!=null) detail.setProperty("fields",fields);
        return ResponseEntity.status(status).body(detail);
    }
}
