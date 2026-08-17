package ai.theragenx.pvcase.merge;

import ai.theragenx.pvcase.domain.AnnotatedField;
import ai.theragenx.pvcase.domain.CaseView;
import ai.theragenx.pvcase.domain.FieldStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Merges a follow-up extraction onto the stored case and annotates every field
 * with how it got there.
 *
 * <p>The governing rule, argued in {@code docs/DECISIONS.md} D3, is that a
 * follow-up is <em>incremental evidence, not a replacement document</em>.
 * Everything below follows from that:
 *
 * <ul>
 *   <li>A field the follow-up does not mention is preserved and marked
 *       {@code carried_forward}. Silence is not deletion.</li>
 *   <li>A field the follow-up restates identically is {@code unchanged} — a
 *       distinct, stronger signal than carry-forward, because a second source
 *       document corroborated it.</li>
 *   <li>Comparison is on the clinical value alone. A re-scored confidence or a
 *       new page reference is not a conflict for the reviewer to resolve.</li>
 * </ul>
 *
 * <p>This class is deliberately free of Spring plumbing beyond {@code @Service}
 * and touches no storage, so its edge cases are testable as plain functions.
 */
@Service
public class MergeService {

    private final CasePayloadNormalizer normalizer;

    public MergeService(CasePayloadNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public CasePayloadNormalizer normalizer() {
        return normalizer;
    }

    /**
     * Builds version 1 from an initial extraction. Every field is
     * {@link FieldStatus#BASELINE}: there is no predecessor, so calling them
     * "unchanged" would imply a comparison that never happened.
     */
    public CaseView buildBaseline(String caseId, NormalizedCasePayload payload) {
        Map<String, Map<String, AnnotatedField>> sections = new LinkedHashMap<>();

        payload.sections().forEach((sectionName, fields) -> {
            Map<String, AnnotatedField> annotated = new LinkedHashMap<>();
            fields.forEach((fieldName, incoming) -> {
                String path = sectionName + "." + fieldName;
                annotated.put(fieldName, AnnotatedField.baseline(
                        path,
                        FieldLabels.humanize(fieldName),
                        incoming.value(),
                        incoming.confidence(),
                        incoming.source()));
            });
            sections.put(sectionName, annotated);
        });

        CaseView view = new CaseView(
                caseId,
                1,
                null,
                payload.caseClassification(),
                payload.extractedAt(),
                payload.sourceDocument(),
                payload.missingFields(),
                Map.of(),
                sections);

        return flagMissingFields(view).withRecomputedSummary();
    }

    /**
     * Produces the next version of {@code previous} with {@code followUp} merged in.
     *
     * <p>Pure: neither argument is mutated, and the result is a fresh snapshot.
     * That is what lets the repository treat stored versions as immutable.
     */
    public CaseView merge(CaseView previous, NormalizedCasePayload followUp) {
        Map<String, Map<String, AnnotatedField>> merged = new LinkedHashMap<>();

        // Existing sections keep their established order; anything new is appended.
        Set<String> sectionOrder = new LinkedHashSet<>(previous.sections().keySet());
        sectionOrder.addAll(followUp.sections().keySet());

        for (String sectionName : sectionOrder) {
            Map<String, AnnotatedField> priorFields =
                    previous.sections().getOrDefault(sectionName, Map.of());
            Map<String, IncomingField> incomingFields =
                    followUp.sections().getOrDefault(sectionName, Map.of());

            Set<String> fieldOrder = new LinkedHashSet<>(priorFields.keySet());
            fieldOrder.addAll(incomingFields.keySet());

            Map<String, AnnotatedField> mergedFields = new LinkedHashMap<>();
            for (String fieldName : fieldOrder) {
                mergedFields.put(fieldName, mergeField(
                        sectionName + "." + fieldName,
                        fieldName,
                        priorFields.get(fieldName),
                        incomingFields.get(fieldName)));
            }
            merged.put(sectionName, mergedFields);
        }

        CaseView next = new CaseView(
                previous.caseId(),
                previous.version() + 1,
                previous.version(),
                // Absent classification means "this follow-up is silent on it", so the
                // stored value stands. Explicitly null means "unset it", which is a
                // legitimate product state and must not be confused with absence.
                followUp.classificationPresent()
                        ? followUp.caseClassification()
                        : previous.caseClassification(),
                followUp.extractedAt() != null ? followUp.extractedAt() : previous.extractedAt(),
                followUp.sourceDocument() != null
                        ? followUp.sourceDocument()
                        : previous.sourceDocument(),
                // missing_fields describes the latest extraction only. Unioning it with
                // previous versions would leave stale warnings on fields a later
                // document successfully read.
                followUp.missingFields(),
                Map.of(),
                merged);

        return flagMissingFields(next).withRecomputedSummary();
    }

    private AnnotatedField mergeField(
            String path, String fieldName, AnnotatedField prior, IncomingField incoming) {

        if (incoming == null) {
            // The follow-up said nothing. Preserve value and provenance intact.
            return prior.withStatus(FieldStatus.CARRIED_FORWARD).withMissingInFollowUp(false);
        }

        String label = prior != null ? prior.label() : FieldLabels.humanize(fieldName);

        if (prior == null) {
            return new AnnotatedField(
                    path, label, incoming.value(), incoming.confidence(), incoming.source(),
                    FieldStatus.NEW, null, false);
        }

        if (sameClinicalValue(prior.value(), incoming.value())) {
            // The value is corroborated. Take the fresher confidence and source where
            // supplied, but keep the old provenance rather than blanking it if the
            // follow-up sent a bare scalar.
            return new AnnotatedField(
                    path,
                    label,
                    prior.value(),
                    incoming.confidence() != null ? incoming.confidence() : prior.confidence(),
                    incoming.source() != null ? incoming.source() : prior.source(),
                    FieldStatus.UNCHANGED,
                    null,
                    false);
        }

        return new AnnotatedField(
                path, label, incoming.value(), incoming.confidence(), incoming.source(),
                FieldStatus.OVERRIDDEN, prior.value(), false);
    }

    /**
     * Two values are the same clinical fact.
     *
     * <p>Scalars compare on trimmed text, so the string {@code "62"} and the number
     * {@code 62} are one value. An extraction pipeline that changes its mind about
     * JSON typing has not discovered anything new about the patient, and surfacing
     * that as a conflict would waste reviewer attention — which is the scarce
     * resource this whole screen is built to protect.
     *
     * <p>Comparison stays case-sensitive. "Male" becoming "male" is almost
     * certainly noise, but it is cheap for a reviewer to dismiss and expensive to
     * have silently swallowed.
     */
    private boolean sameClinicalValue(JsonNode prior, JsonNode incoming) {
        if (prior == null || incoming == null) {
            return prior == incoming;
        }
        if (prior.isValueNode() && incoming.isValueNode()) {
            return prior.asText().trim().equals(incoming.asText().trim());
        }
        return prior.equals(incoming);
    }

    /**
     * Marks fields the latest extraction reported as unreadable.
     *
     * <p>A {@code missing_fields} entry never erases a stored value; it annotates
     * it, so the reviewer knows the displayed value survived by carry-forward and
     * was not confirmed by the newest document. Where the follow-up both listed a
     * field as missing and supplied a value for it, the supplied value wins and
     * the flag is not set — the payload contradicted itself and the concrete
     * evidence is the more useful of the two.
     */
    private CaseView flagMissingFields(CaseView view) {
        if (view.missingFields().isEmpty()) {
            return view;
        }

        Set<String> flagged = new LinkedHashSet<>();
        for (String entry : view.missingFields()) {
            resolveFieldPath(entry, view.sections()).ifPresent(flagged::add);
        }
        if (flagged.isEmpty()) {
            return view;
        }

        Map<String, Map<String, AnnotatedField>> updated = new LinkedHashMap<>();
        view.sections().forEach((sectionName, fields) -> {
            Map<String, AnnotatedField> updatedFields = new LinkedHashMap<>();
            fields.forEach((fieldName, field) -> {
                boolean missing = flagged.contains(sectionName + "." + fieldName)
                        && field.status() == FieldStatus.CARRIED_FORWARD;
                updatedFields.put(fieldName, field.withMissingInFollowUp(missing));
            });
            updated.put(sectionName, updatedFields);
        });

        return new CaseView(
                view.caseId(), view.version(), view.comparedToVersion(),
                view.caseClassification(), view.extractedAt(), view.sourceDocument(),
                view.missingFields(), view.changeSummary(), updated);
    }

    /**
     * Resolves a {@code missing_fields} entry to a canonical {@code section.field}
     * path.
     *
     * <p>Accepts {@code patient.age}, {@code sections.patient.age} and
     * {@code /sections/patient/age}, plus a bare {@code age} when exactly one field
     * in the case carries that name. A bare name matching several fields is left
     * unresolved rather than arbitrarily attached to one of them — the entry still
     * appears in {@code missing_fields} for the reviewer, which is honest about the
     * ambiguity instead of guessing.
     */
    static Optional<String> resolveFieldPath(
            String entry, Map<String, Map<String, AnnotatedField>> sections) {

        if (entry == null || entry.isBlank()) {
            return Optional.empty();
        }

        String normalised = entry.trim().replace('/', '.');
        while (normalised.startsWith(".")) {
            normalised = normalised.substring(1);
        }
        if (normalised.startsWith("sections.")) {
            normalised = normalised.substring("sections.".length());
        }

        if (normalised.contains(".")) {
            int split = normalised.indexOf('.');
            String section = normalised.substring(0, split);
            String field = normalised.substring(split + 1);
            Map<String, AnnotatedField> fields = sections.get(section);
            if (fields != null && fields.containsKey(field)) {
                return Optional.of(section + "." + field);
            }
            return Optional.empty();
        }

        String bareName = normalised;
        List<String> matches = sections.entrySet().stream()
                .filter(section -> section.getValue().containsKey(bareName))
                .map(section -> section.getKey() + "." + bareName)
                .toList();

        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }
}
