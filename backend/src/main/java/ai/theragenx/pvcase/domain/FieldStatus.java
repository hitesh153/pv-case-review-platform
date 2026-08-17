package ai.theragenx.pvcase.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How a field in the current case version came to hold its value, relative to
 * the version immediately before it.
 *
 * <p>The reviewer's whole job is deciding what to trust, so the distinction
 * that matters is <em>why</em> a value is present, not merely what it is. In
 * particular {@link #UNCHANGED} and {@link #CARRIED_FORWARD} look identical in
 * the data but carry very different evidential weight: one was restated by a
 * new source document, the other merely survived because nothing contradicted
 * it.
 */
public enum FieldStatus {

    /** Initial version of the case. There is no prior version to compare against. */
    BASELINE("baseline"),

    /** The path did not exist in the prior version and the follow-up supplied it. */
    NEW("new"),

    /** The follow-up explicitly restated this field and the clinical value matches. */
    UNCHANGED("unchanged"),

    /** The follow-up supplied a different clinical value. {@code previous_value} is populated. */
    OVERRIDDEN("overridden"),

    /**
     * The follow-up said nothing about this field, so the prior value and its
     * provenance were preserved.
     *
     * <p>See {@code docs/DECISIONS.md} D3: omission is not deletion.
     */
    CARRIED_FORWARD("carried_forward");

    private final String wireName;

    FieldStatus(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * Parses the wire form. Declared explicitly rather than relying on Jackson's
     * {@code @JsonValue} inference, because restore reads these values back from
     * a backup file and a silent mismatch there would corrupt a case.
     */
    @JsonCreator
    public static FieldStatus fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("field status is required");
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        for (FieldStatus status : values()) {
            if (status.wireName.equals(normalised)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown field status: '" + value + "'");
    }
}
