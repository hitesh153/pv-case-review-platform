package ai.theragenx.pvcase.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * One error shape for every failure this service returns.
 *
 * <p>A single envelope means the reviewer UI writes one error renderer instead
 * of one per endpoint, and the {@code errors} array lets a form highlight the
 * specific field that was rejected rather than showing a sentence.
 *
 * <p>{@code code} is a stable machine-readable token; {@code message} is for
 * humans and may be reworded without breaking a client.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(

        @JsonProperty("code") String code,

        @JsonProperty("message") String message,

        /** Present when specific locations in the request were at fault. */
        @JsonProperty("errors") List<InvalidPayloadException.FieldViolation> errors,

        @JsonProperty("timestamp") String timestamp) {

    public static ApiError of(String code, String message) {
        return of(code, message, null);
    }

    public static ApiError of(
            String code, String message, List<InvalidPayloadException.FieldViolation> errors) {
        return new ApiError(
                code,
                message,
                errors == null || errors.isEmpty() ? null : errors,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    }
}
