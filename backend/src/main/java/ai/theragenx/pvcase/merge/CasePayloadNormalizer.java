package ai.theragenx.pvcase.merge;

import ai.theragenx.pvcase.web.error.InvalidPayloadException;
import ai.theragenx.pvcase.web.error.InvalidPayloadException.FieldViolation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns incoming case-shaped JSON into a {@link NormalizedCasePayload}.
 *
 * <p>This class exists to be the <em>only</em> place that copes with input
 * variation. Everything downstream — the merge engine, the store, the response —
 * works against one predictable structure. When the real follow-up fixture
 * arrives and turns out to differ, this is the single file that changes.
 *
 * <p>The tolerance here is bounded on purpose. It accepts variations that are
 * plausible from an AI extraction pipeline (snake_case vs camelCase keys, a bare
 * scalar where an envelope was expected, sections and fields nobody has seen
 * before) and rejects anything genuinely ambiguous. A field-depth object with no
 * {@code value} key is the clearest example: it could be a malformed envelope or
 * an intended sub-section, and guessing wrong would either lose data or invent
 * it. Refusing with a precise 400 is better than either.
 *
 * <p>All violations are collected before any is thrown, so a caller fixing a
 * payload sees every problem in one response.
 */
@Component
public class CasePayloadNormalizer {

    private static final int MAX_VIOLATIONS_REPORTED = 25;

    /** Closed set; see the violation message where this is used. */
    static final List<String> ALLOWED_CLASSIFICATIONS =
            List.of("significant", "non-significant");

    /**
     * Top-level keys this service understands. Extra keys alongside them are
     * tolerated — an extraction pipeline may legitimately attach its own metadata,
     * and rejecting a payload for carrying a run id would be hostile. But a payload
     * containing *none* of these is not a follow-up at all.
     */
    private static final List<String> RECOGNISED_KEYS = List.of(
            "sections", "case_classification", "caseClassification",
            "missing_fields", "missingFields", "case_id", "caseId",
            "extracted_at", "extractedAt", "source_document", "sourceDocument");

    private static boolean hasRecognisableContent(JsonNode root) {
        for (String key : RECOGNISED_KEYS) {
            if (root.has(key)) {
                return true;
            }
        }
        return false;
    }

    private static final String NAME_RULE =
            "section and field names must be non-blank and must not contain '.', '/' "
                    + "or whitespace, because field paths are built as 'section.field'";

    /**
     * Field paths are the flat key the UI sorts by and {@code /queries} refers to,
     * and they are built by joining a section and field name with a dot. A name
     * containing a dot therefore makes the path ambiguous: section {@code a} with
     * field {@code b.c} and section {@code a.b} with field {@code c} both produce
     * {@code a.b.c}, and a {@code missing_fields} entry naming that path would flag
     * both. Slashes and surrounding whitespace break the same way, since path
     * resolution normalises both.
     *
     * <p>Rejecting the name is better than escaping it. Escaping would make every
     * path in the API harder to read for a case of extraction output that has never
     * been seen, and a precise 400 tells the pipeline owner exactly what to change.
     */
    private static boolean isUsableName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (!name.equals(name.trim())) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.' || c == '/' || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param root the parsed request body
     * @param requireSections true for a bootstrap/restore payload, false for a
     *     follow-up — a follow-up that only changes the classification is legitimate
     */
    public NormalizedCasePayload normalize(JsonNode root, boolean requireSections) {
        List<FieldViolation> violations = new ArrayList<>();

        if (root == null || !root.isObject()) {
            throw new InvalidPayloadException("$", "Request body must be a JSON object");
        }

        String caseId = readText(root, violations, "case_id", "caseId");
        String extractedAt = readText(root, violations, "extracted_at", "extractedAt");
        String sourceDocument = readText(root, violations, "source_document", "sourceDocument");

        JsonNode classificationNode = firstPresent(root, "case_classification", "caseClassification");
        boolean classificationPresent = classificationNode != null;
        String caseClassification = null;
        if (classificationPresent && !classificationNode.isNull()) {
            if (!classificationNode.isTextual()) {
                violations.add(new FieldViolation(
                        "case_classification", "must be a string or null"));
            } else if (!ALLOWED_CLASSIFICATIONS.contains(classificationNode.asText())) {
                // A closed set, because this drives a regulatory decision. Accepting
                // a free-form string would let a typo like "signficant" through, and
                // it would read as a valid classification to everything downstream.
                violations.add(new FieldViolation("case_classification", String.format(
                        "must be one of %s, or null; got '%s'",
                        ALLOWED_CLASSIFICATIONS, classificationNode.asText())));
            } else {
                caseClassification = classificationNode.asText();
            }
        }

        List<String> missingFields = readMissingFields(root, violations);

        JsonNode sectionsNode = firstPresent(root, "sections");
        Map<String, Map<String, IncomingField>> sections = new LinkedHashMap<>();
        if (sectionsNode == null || sectionsNode.isNull()) {
            if (requireSections) {
                violations.add(new FieldViolation("sections", "is required"));
            }
        } else if (!sectionsNode.isObject()) {
            violations.add(new FieldViolation("sections", "must be a JSON object"));
        } else {
            readSections(sectionsNode, sections, violations);
        }

        // A payload in which nothing at all was recognised is refused rather than
        // treated as a no-op follow-up. Accepting it returns 200, appends a version
        // that records nothing, and tells the caller their submission landed — so
        // if the extraction pipeline ever changes its output format, every follow-up
        // "succeeds" while importing nothing. Silently discarding submitted safety
        // data is the worst available outcome; a 400 naming the keys we understand
        // is the cheapest possible fix.
        if (!hasRecognisableContent(root)) {
            violations.add(new FieldViolation("$", String.format(
                    "no recognised case data; expected at least one of %s",
                    String.join(", ", RECOGNISED_KEYS))));
        }

        if (!violations.isEmpty()) {
            throw new InvalidPayloadException("Case payload is invalid", trim(violations));
        }

        return new NormalizedCasePayload(
                caseId, classificationPresent, caseClassification,
                extractedAt, sourceDocument, missingFields, sections);
    }

    private void readSections(
            JsonNode sectionsNode,
            Map<String, Map<String, IncomingField>> target,
            List<FieldViolation> violations) {

        Iterator<Map.Entry<String, JsonNode>> sectionEntries = sectionsNode.fields();
        while (sectionEntries.hasNext()) {
            Map.Entry<String, JsonNode> sectionEntry = sectionEntries.next();
            String sectionName = sectionEntry.getKey();
            JsonNode sectionNode = sectionEntry.getValue();

            if (!isUsableName(sectionName)) {
                violations.add(new FieldViolation("sections." + sectionName, NAME_RULE));
                continue;
            }
            if ("sections".equals(sectionName)) {
                violations.add(new FieldViolation("sections.sections",
                        "'sections' is reserved as a section name because it collides "
                                + "with the optional 'sections.' prefix on field paths"));
                continue;
            }

            if (!sectionNode.isObject()) {
                violations.add(new FieldViolation(
                        "sections." + sectionName, "must be a JSON object of fields"));
                continue;
            }

            Map<String, IncomingField> fields = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fieldEntries = sectionNode.fields();
            while (fieldEntries.hasNext()) {
                Map.Entry<String, JsonNode> fieldEntry = fieldEntries.next();
                String fieldName = fieldEntry.getKey();
                String path = sectionName + "." + fieldName;

                if (!isUsableName(fieldName)) {
                    violations.add(new FieldViolation(path, NAME_RULE));
                    continue;
                }

                IncomingField field = readField(path, fieldEntry.getValue(), violations);
                if (field != null) {
                    fields.put(fieldName, field);
                }
            }
            target.put(sectionName, fields);
        }
    }

    /** Returns null when the field could not be read; the violation is already recorded. */
    private IncomingField readField(String path, JsonNode node, List<FieldViolation> violations) {
        // Bare scalar or array: a value with no provenance attached.
        if (node.isValueNode() || node.isArray()) {
            if (node.isNull()) {
                violations.add(new FieldViolation(path,
                        "is null; report an unreadable field in 'missing_fields' rather than "
                                + "sending a null value"));
                return null;
            }
            return new IncomingField(node, null, null);
        }

        if (!node.isObject()) {
            violations.add(new FieldViolation(path, "must be an object or a scalar value"));
            return null;
        }

        if (!node.has("value")) {
            violations.add(new FieldViolation(path,
                    "is an object without a 'value' key; expected either "
                            + "{value, confidence, source} or a bare scalar"));
            return null;
        }

        JsonNode valueNode = node.get("value");
        if (valueNode.isNull()) {
            violations.add(new FieldViolation(path + ".value",
                    "is null; report an unreadable field in 'missing_fields' rather than "
                            + "sending a null value"));
            return null;
        }
        if (valueNode.isObject()) {
            violations.add(new FieldViolation(path + ".value",
                    "must be a scalar or array, not an object"));
            return null;
        }
        if (valueNode.isTextual() && valueNode.asText().isBlank()) {
            // A blank string is not a value; it is an extraction that found nothing
            // dressed up as one. Accepting it would silently overwrite a real
            // clinical value with whitespace.
            violations.add(new FieldViolation(path + ".value",
                    "is blank; report an unreadable field in 'missing_fields' rather "
                            + "than sending an empty string"));
            return null;
        }

        Double confidence = null;
        JsonNode confidenceNode = node.get("confidence");
        if (confidenceNode != null && !confidenceNode.isNull()) {
            if (!confidenceNode.isNumber()) {
                violations.add(new FieldViolation(path + ".confidence", "must be a number"));
            } else {
                double value = confidenceNode.doubleValue();
                if (value < 0.0 || value > 1.0) {
                    violations.add(new FieldViolation(
                            path + ".confidence", "must be between 0 and 1 inclusive"));
                } else {
                    confidence = value;
                }
            }
        }

        String source = null;
        JsonNode sourceNode = node.get("source");
        if (sourceNode != null && !sourceNode.isNull()) {
            if (!sourceNode.isTextual()) {
                violations.add(new FieldViolation(path + ".source", "must be a string"));
            } else {
                source = sourceNode.asText();
            }
        }

        return new IncomingField(valueNode, confidence, source);
    }

    private List<String> readMissingFields(JsonNode root, List<FieldViolation> violations) {
        JsonNode node = firstPresent(root, "missing_fields", "missingFields");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            violations.add(new FieldViolation("missing_fields", "must be an array of strings"));
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            if (!entry.isTextual() || entry.asText().isBlank()) {
                violations.add(new FieldViolation(
                        "missing_fields[" + i + "]", "must be a non-blank string"));
                continue;
            }
            values.add(entry.asText().trim());
        }
        return List.copyOf(values);
    }

    private String readText(JsonNode root, List<FieldViolation> violations, String... aliases) {
        JsonNode node = firstPresent(root, aliases);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            violations.add(new FieldViolation(aliases[0], "must be a string"));
            return null;
        }
        return node.asText();
    }

    /** First alias actually present on the object, or null. Distinguishes absent from null. */
    private JsonNode firstPresent(JsonNode root, String... aliases) {
        for (String alias : aliases) {
            if (root.has(alias)) {
                return root.get(alias);
            }
        }
        return null;
    }

    /** Caps the reported list so a wildly wrong payload cannot produce an enormous 400. */
    private List<FieldViolation> trim(List<FieldViolation> violations) {
        if (violations.size() <= MAX_VIOLATIONS_REPORTED) {
            return violations;
        }
        List<FieldViolation> trimmed =
                new ArrayList<>(violations.subList(0, MAX_VIOLATIONS_REPORTED));
        trimmed.add(new FieldViolation("$", String.format(
                "%d further problems not shown", violations.size() - MAX_VIOLATIONS_REPORTED)));
        return trimmed;
    }
}
