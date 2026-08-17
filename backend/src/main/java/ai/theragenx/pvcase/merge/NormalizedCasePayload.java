package ai.theragenx.pvcase.merge;

import java.util.List;
import java.util.Map;

/**
 * A case-shaped payload after normalisation: one predictable structure the
 * merge engine can work against, whatever surface variations the incoming JSON
 * had.
 *
 * <p>{@code classificationPresent} exists because {@code case_classification}
 * has three meaningful states on the wire — absent, explicitly null, or a
 * value — and a plain nullable field can only express two. Absent means "this
 * follow-up says nothing about classification, keep what we had"; explicit null
 * means "the reviewer's classification is now unset", which the product treats
 * as a legitimate state.
 */
public record NormalizedCasePayload(

        /** From the body; may be null. The URL is authoritative where they disagree. */
        String caseId,

        boolean classificationPresent,
        String caseClassification,

        String extractedAt,
        String sourceDocument,

        /** Verbatim strings as submitted, so nothing is lost if a path is unrecognised. */
        List<String> missingFields,

        /** sectionName -> fieldName -> field. Insertion-ordered. */
        Map<String, Map<String, IncomingField>> sections) {
}
