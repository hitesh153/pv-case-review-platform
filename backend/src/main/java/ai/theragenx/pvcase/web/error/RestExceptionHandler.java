package ai.theragenx.pvcase.web.error;

import ai.theragenx.pvcase.web.error.InvalidPayloadException.FieldViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;
import java.util.stream.Collectors;

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

    /**
     * Body could not be turned into the expected object.
     *
     * <p>Two cases, and they need different messages. When Jackson knows which
     * property it was reading, that location is far more useful than any sentence
     * this handler could write, so it is reported and the message stays neutral.
     * When it does not, the caller gets the checklist of usual suspects instead.
     *
     * <p>The message is deliberately endpoint-agnostic. This advice serves cases,
     * queries and restores alike, so wording it around any one of them would be
     * wrong everywhere else — as it was when it said "could not be read into a
     * case" in response to a malformed query.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.debug("Unreadable request body", e);

        Throwable cause = e.getCause();
        if (cause instanceof JsonMappingException mappingException) {
            String path = mappingException.getPath().stream()
                    .map(ref -> ref.getFieldName() != null
                            ? ref.getFieldName()
                            : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            String detail = mappingException.getOriginalMessage();
            return ResponseEntity.badRequest().body(ApiError.of(
                    "INVALID_PAYLOAD",
                    "Request body could not be read. See errors for the exact location.",
                    List.of(new FieldViolation(path.isEmpty() ? "$" : path, detail))));
        }

        return ResponseEntity.badRequest().body(ApiError.of(
                "MALFORMED_JSON",
                "Request body could not be parsed as JSON. Check for a trailing comma, "
                        + "an unquoted key, or a missing Content-Type: application/json header."));
    }

    /** Right path, wrong verb. 405 rather than the catch-all's 500. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException e) {
        String supported = e.getSupportedHttpMethods() == null
                ? "" : e.getSupportedHttpMethods().toString();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError.of(
                "METHOD_NOT_ALLOWED",
                "Method " + e.getMethod() + " is not supported here. Supported: " + supported));
    }

    /** Almost always a forgotten `-H 'Content-Type: application/json'`. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiError.of(
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type " + e.getContentType() + " is not supported. "
                        + "Send application/json."));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(ApiError.of(
                "NOT_ACCEPTABLE", "This endpoint only produces application/json."));
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
