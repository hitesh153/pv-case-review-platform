package ai.theragenx.pvcase.web.error;

import java.util.List;

/**
 * The request body was structurally readable but semantically wrong — a
 * confidence outside [0,1], a field object with no {@code value}, a body case id
 * that disagrees with the URL. Surfaces as HTTP 400.
 *
 * <p>Carries a list of {@link FieldViolation} rather than a single message
 * because a reviewer re-submitting a corrected payload wants every problem at
 * once, not one per round trip.
 */
public class InvalidPayloadException extends RuntimeException {

    /** One specific problem, located by the path it occurred at. */
    public record FieldViolation(String path, String message) {
    }

    private final List<FieldViolation> violations;

    public InvalidPayloadException(String message, List<FieldViolation> violations) {
        super(message);
        this.violations = List.copyOf(violations);
    }

    public InvalidPayloadException(String path, String message) {
        this("Request payload is invalid", List.of(new FieldViolation(path, message)));
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
