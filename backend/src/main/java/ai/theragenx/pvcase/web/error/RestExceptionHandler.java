package ai.theragenx.pvcase.web.error;

import ai.theragenx.pvcase.web.error.InvalidPayloadException.FieldViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;

/**
 * Translates exceptions into the single {@link ApiError} envelope.
 *
 * <p>The rule applied throughout: tell the caller what they can act on, and no
 * more. Validation problems name the exact path at fault, because the caller can
 * fix those. Unexpected failures return a generic message and log the stack
 * trace server-side, because leaking an internal exception string to a browser
 * helps an attacker and not the reviewer.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(CaseNotFoundException.class)
    public ResponseEntity<ApiError> handleCaseNotFound(CaseNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("CASE_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(InvalidPayloadException.class)
    public ResponseEntity<ApiError> handleInvalidPayload(InvalidPayloadException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_PAYLOAD", e.getMessage(), e.violations()));
    }

    /** Bean Validation failures on annotated request bodies, e.g. a blank question. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException e) {
        List<FieldViolation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_PAYLOAD", "Request payload is invalid", violations));
    }

    /** Body was not parseable as JSON at all. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.debug("Unreadable request body", e);
        return ResponseEntity.badRequest().body(ApiError.of(
                "MALFORMED_JSON",
                "Request body could not be parsed as JSON. Check for a trailing comma, "
                        + "an unquoted key, or a missing Content-Type: application/json header."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(
            MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(ApiError.of(
                "MISSING_PARAMETER",
                "Required query parameter is missing",
                List.of(new FieldViolation(e.getParameterName(), "is required"))));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNoHandler(NoHandlerFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(
                "NOT_FOUND", "No endpoint " + e.getHttpMethod() + " " + e.getRequestURL()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        // Logged in full here precisely because it is not returned to the caller.
        log.error("Unhandled exception serving request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(
                "INTERNAL_ERROR",
                "The service failed to handle this request. Check the service logs "
                        + "for the corresponding stack trace."));
    }
}
