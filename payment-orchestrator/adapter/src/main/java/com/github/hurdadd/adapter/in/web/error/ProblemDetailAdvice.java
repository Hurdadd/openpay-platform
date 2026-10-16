package com.github.hurdadd.adapter.in.web.error;

import com.github.hurdadd.domain.PaymentNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;


@RestControllerAdvice
public class ProblemDetailAdvice {

    private static final URI BASE = URI.create("https://openpay.dev/problems/");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleBodyValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.UNPROCESSABLE_CONTENT, "request-body-validation-failed", request);
        problem.setTitle("Unprocessable content");
        problem.setDetail("One or more fields of the request body are invalid.");

        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe ->
                errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors().forEach(ge ->
                errors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        problem.setProperty("errors", errors);

        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "malformed-request-body", request);
        problem.setTitle("Bad request");
        problem.setDetail("The request body is not valid JSON for this endpoint.");
        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleMissingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "missing-required-header", request);
        problem.setTitle("Bad request");
        problem.setDetail("Required header '" + e.getHeaderName() + "' is missing.");
        problem.setProperty("header", e.getHeaderName());
        return problem;
    }

    /**
     * Malformed request syntax — an unparseable identifier or field. This is a
     * "could not understand it" 400, not a "content is invalid" 422, so it wins
     * over the broad {@link IllegalArgumentException} handler below.
     */
    @ExceptionHandler(MalformedRequestException.class)
    ProblemDetail handleMalformedRequest(MalformedRequestException e, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "malformed-request", request);
        problem.setTitle("Bad request");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.UNPROCESSABLE_CONTENT, "domain-rule-violated", request);
        problem.setTitle("Unprocessable content");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail handleNotFound(PaymentNotFoundException e, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.NOT_FOUND, "payment-not-found", request);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /**
     * The database is slow or unreachable (pool wait, statement or transaction timeout). Nothing was
     * committed by this request, so the client may retry with the same Idempotency-Key.
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> handleStorageUnavailable(Exception e, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable", request);
        problem.setTitle("Service unavailable");
        problem.setDetail("The service could not complete the request in time. Retry with the same Idempotency-Key.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
    }

    private ProblemDetail base(HttpStatusCode status, String slug, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "");
        problem.setType(BASE.resolve(slug));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
