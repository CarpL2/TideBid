package io.github.carpl2.tidebid.web;

import io.github.carpl2.tidebid.core.ApiResponse;
import io.github.carpl2.tidebid.core.BusinessException;
import io.github.carpl2.tidebid.core.CommonErrorCode;
import io.github.carpl2.tidebid.core.ErrorCode;
import io.github.carpl2.tidebid.core.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps expected failures to a stable JSON response without exposing stack traces.
 */
@RestControllerAdvice
public final class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(
            BusinessException exception,
            HttpServletRequest request
    ) {
        return failure(exception.errorCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse(CommonErrorCode.INVALID_ARGUMENT.defaultMessage());
        return failure(CommonErrorCode.INVALID_ARGUMENT, message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        String message = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .findFirst()
                .orElse(CommonErrorCode.INVALID_ARGUMENT.defaultMessage());
        return failure(CommonErrorCode.INVALID_ARGUMENT, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return failure(CommonErrorCode.INVALID_ARGUMENT, "Request body is malformed", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        return failure(
                CommonErrorCode.INVALID_ARGUMENT,
                "Required request header is missing: " + exception.getHeaderName(),
                request
        );
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception exception, HttpServletRequest request) {
        return failure(CommonErrorCode.NOT_FOUND, CommonErrorCode.NOT_FOUND.defaultMessage(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(CommonErrorCode.METHOD_NOT_ALLOWED.httpStatus())
                .headers(exception.getHeaders())
                .body(ApiResponse.failure(CommonErrorCode.METHOD_NOT_ALLOWED, traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        String traceId = traceId(request);
        log.error(
                "Unhandled request failure traceId={} exceptionType={}",
                traceId,
                exception.getClass().getName()
        );
        ApiResponse<Void> body = ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR, traceId);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.httpStatus()).body(body);
    }

    private ResponseEntity<ApiResponse<Void>> failure(
            ErrorCode errorCode,
            String message,
            HttpServletRequest request
    ) {
        String traceId = traceId(request);
        ApiResponse<Void> body = ApiResponse.failure(errorCode, message, traceId);
        return ResponseEntity.status(HttpStatusCode.valueOf(errorCode.httpStatus())).body(body);
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value && TraceIds.isValid(value)) {
            return value;
        }
        return TraceIds.create();
    }
}
