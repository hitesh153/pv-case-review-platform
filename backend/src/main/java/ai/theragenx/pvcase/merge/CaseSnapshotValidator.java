package ai.theragenx.pvcase.merge;

import ai.theragenx.pvcase.domain.AnnotatedField;
import ai.theragenx.pvcase.domain.CaseView;
import ai.theragenx.pvcase.domain.FieldStatus;
import ai.theragenx.pvcase.web.error.InvalidPayloadException;
import ai.theragenx.pvcase.web.error.InvalidPayloadException.FieldViolation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a snapshot arriving at {@code PUT /cases/{id}}.
 *
 * <p>Follow-ups are validated by {@link CasePayloadNormalizer}, but restore takes
 * an already-annotated case and so bypasses it entirely. That is the more
 * dangerous of the two paths: a follow-up can only ever add a version, whereas a
 * restore replaces a case wholesale, and the input is a file that has been
 * sitting on disk where anyone could have edited it.
 *
 * <p>Without this, three things went wrong. A snapshot with no {@code version}
 * bound to {@code 0} because the record component is a primitive int. A snapshot
 * with {@code version: 2147483647} was accepted, and the next follow-up computed
 * {@code version + 1} straight into overflow. And a field with a missing
 * {@code status} produced a {@link NullPointerException} deep in the summary
 * recomputation, surfacing as a 500 — an operator restoring a backup at 2am
 * deserves to be told which field is malformed, not handed a stack trace.
 */
@Component
public class CaseSnapshotValidator {

    /** Leaves comfortable headroom for future merges rather than allowing overflow. */
    private static final int MAX_VERSION = 1_000_000;

    public void validate(CaseView snapshot) {
        List<FieldViolation> violations = new ArrayList<>();

        if (snapshot == null) {
            throw new InvalidPayloadException("$", "a case snapshot is required");
        }

        if (snapshot.version() < 1 || snapshot.version() > MAX_VERSION) {
            violations.add(new FieldViolation("version", String.format(
                    "must be between 1 and %d; got %d. A snapshot with no version "
                            + "reads as 0, and an extreme value would overflow on the "
                            + "next follow-up.", MAX_VERSION, snapshot.version())));
        }

        Integer comparedTo = snapshot.comparedToVersion();
        if (comparedTo != null && comparedTo >= snapshot.version()) {
            violations.add(new FieldViolation("compared_to_version", String.format(
                    "must be earlier than version (%d); got %d",
                    snapshot.version(), comparedTo)));
        }

        if (snapshot.caseClassification() != null
                && !CasePayloadNormalizer.ALLOWED_CLASSIFICATIONS
                        .contains(snapshot.caseClassification())) {
            violations.add(new FieldViolation("case_classification", String.format(
                    "must be one of %s, or null",
                    CasePayloadNormalizer.ALLOWED_CLASSIFICATIONS)));
        }

        if (snapshot.sections() == null) {
            violations.add(new FieldViolation("sections", "is required"));
            throw new InvalidPayloadException("Case snapshot is invalid", violations);
        }

        validateSections(snapshot, violations);

        if (!violations.isEmpty()) {
            throw new InvalidPayloadException("Case snapshot is invalid", violations);
        }
    }

    private void validateSections(CaseView snapshot, List<FieldViolation> violations) {
        for (Map.Entry<String, Map<String, AnnotatedField>> section
                : snapshot.sections().entrySet()) {

            String sectionName = section.getKey();
            if (section.getValue() == null) {
                violations.add(new FieldViolation(
                        "sections." + sectionName, "must be an object of fields, not null"));
                continue;
            }

            for (Map.Entry<String, AnnotatedField> entry : section.getValue().entrySet()) {
                String path = sectionName + "." + entry.getKey();
                AnnotatedField field = entry.getValue();

                if (field == null) {
                    violations.add(new FieldViolation(path, "must be an object, not null"));
                    continue;
                }
                if (field.status() == null) {
                    violations.add(new FieldViolation(path + ".status", "is required"));
                }
                if (field.value() == null || field.value().isNull()) {
                    violations.add(new FieldViolation(path + ".value", "is required"));
                }
                if (field.confidence() != null
                        && (field.confidence() < 0.0 || field.confidence() > 1.0)) {
                    violations.add(new FieldViolation(
                            path + ".confidence", "must be between 0 and 1 inclusive"));
                }

                // field_path is what the UI sorts by and /queries refers to. A
                // snapshot whose stated path disagrees with its position in the
                // section map would send queries to the wrong field.
                if (field.fieldPath() != null && !path.equals(field.fieldPath())) {
                    violations.add(new FieldViolation(path + ".field_path", String.format(
                            "says '%s' but the field sits at '%s'", field.fieldPath(), path)));
                }

                // previous_value is the conflict view's whole input. Present without
                // an overridden status renders a conflict that never happened;
                // absent on an overridden field renders a conflict with nothing to
                // compare against.
                if (field.status() == FieldStatus.OVERRIDDEN && field.previousValue() == null) {
                    violations.add(new FieldViolation(path + ".previous_value",
                            "is required when status is 'overridden'"));
                } else if (field.status() != null
                        && field.status() != FieldStatus.OVERRIDDEN
                        && field.previousValue() != null) {
                    violations.add(new FieldViolation(path + ".previous_value", String.format(
                            "must be absent when status is '%s'", field.status().wireName())));
                }
            }
        }
    }
}
