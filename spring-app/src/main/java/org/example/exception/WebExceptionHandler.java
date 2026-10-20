package org.example.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Locale;

@RestControllerAdvice
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    private final ErrorMessageResolver messages;

    public WebExceptionHandler(ErrorMessageResolver messages) {
        this.messages = messages;
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(BindException exception, Locale locale) {
        return errorResponse(RequestError.VALIDATION_FAILED, locale);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException exception, Locale locale) {
        return errorResponse(RequestError.MISSING_PARAMETER, locale);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(
            MissingRequestHeaderException exception, Locale locale) {
        return errorResponse(RequestError.MISSING_HEADER, locale);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameterType(
            MethodArgumentTypeMismatchException exception, Locale locale) {
        return errorResponse(RequestError.INVALID_PARAMETER_TYPE, locale);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequestBody(
            HttpMessageNotReadableException exception, Locale locale) {
        return errorResponse(RequestError.INVALID_REQUEST_BODY, locale);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception, Locale locale) {
        if (exception.isForReturnValue()) {
            log.error("Controller return value validation failed", exception);
            return errorResponse(CommonError.INTERNAL_SERVER_ERROR, locale);
        }
        return errorResponse(RequestError.VALIDATION_FAILED, locale);
    }

    private ResponseEntity<ErrorResponse> errorResponse(
            ErrorCode error, Locale locale, Object... args) {
        return ResponseEntity.status(error.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(messages.resolve(error, locale, args));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception, Locale locale) {
        return errorResponse(exception.getError(), locale, exception.getArgs());
    }
}
