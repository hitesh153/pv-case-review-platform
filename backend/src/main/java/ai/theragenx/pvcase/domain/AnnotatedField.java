package ai.theragenx.pvcase.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One extracted field as the reviewer sees it: the value, how much the
 * extraction model trusted it, where in the source document it came from, and
 * how it got here relative to the previous case version.
 *
 * <p>{@code confidence} and {@code source} are deliberately nullable and are
 * serialised even when null. A follow-up may supply a bare scalar with no
 * provenance, and the UI needs to render that as "unscored" rather than
 * silently inheriting the previous extraction's confidence — attaching old
 * provenance to a new value would be a quiet lie about where the value
 * came from.
 */
public record AnnotatedField(

        @JsonProperty("field_path") String fieldPath,

        @JsonProperty("label") String label,

        @JsonProperty("value") JsonNode value,

        @JsonProperty("confidence") Double confidence,

        @JsonProperty("source") String source,

        @JsonProperty("status") FieldStatus status,

        /** Populated only for {@link FieldStatus#OVERRIDDEN}; omitted otherwise. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("previous_value") JsonNode previousValue,

        /**
         * True when the latest extraction explicitly reported this field in its
         * {@code missing_fields} array. The value shown is therefore the prior
         * one, preserved by carry-forward, and the reviewer should treat it as
         * unconfirmed by the newest document.
         */
        @JsonProperty("missing_in_follow_up") boolean missingInFollowUp) {

    /** The initial extraction: everything is baseline, nothing to compare against. */
    public static AnnotatedField baseline(
            String fieldPath, String label, JsonNode value, Double confidence, String source) {
        return new AnnotatedField(
                fieldPath, label, value, confidence, source, FieldStatus.BASELINE, null, false);
    }

    /** Same field, re-stamped with a new status and no conflict recorded. */
    public AnnotatedField withStatus(FieldStatus newStatus) {
        return new AnnotatedField(
                fieldPath, label, value, confidence, source, newStatus, null, missingInFollowUp);
    }

    public AnnotatedField withMissingInFollowUp(boolean missing) {
        return new AnnotatedField(
                fieldPath, label, value, confidence, source, status, previousValue, missing);
    }
}
