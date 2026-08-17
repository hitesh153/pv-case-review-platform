package ai.theragenx.pvcase.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one and only representation of a case on the wire.
 *
 * <p>{@code GET /cases/{id}}, {@code POST /cases/{id}/follow-ups} and
 * {@code PUT /cases/{id}} all speak this exact shape. That is a deliberate
 * design constraint rather than an accident: the reviewer UI should not need to
 * know whether the case it is rendering arrived from a fresh load or from a
 * merge it just triggered, and the backup file should be restorable byte-for-
 * byte without translation.
 *
 * <p>Sections stay nested rather than flattened into one field array, because
 * grouping by section is a primary concept in the reviewer's screen. Each field
 * still carries its own {@code field_path}, so sorting, filtering and raising a
 * query never require the client to reassemble a key from map positions.
 *
 * <p>Iteration order is significant and preserved via {@link LinkedHashMap}:
 * clinically conventional section order reads better than hash order, and a
 * stable order keeps diffs between backup files meaningful.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseView(

        @JsonProperty("case_id") String caseId,

        @JsonProperty("version") int version,

        /** Null for the initial version; otherwise the version this diff is against. */
        @JsonProperty("compared_to_version") Integer comparedToVersion,

        /** {@code significant}, {@code non-significant}, or null for unclassified. */
        @JsonProperty("case_classification") String caseClassification,

        @JsonProperty("extracted_at") String extractedAt,

        @JsonProperty("source_document") String sourceDocument,

        /** Fields the latest extraction could not read. Belongs to this version only. */
        @JsonProperty("missing_fields") List<String> missingFields,

        /** Counts per status, so the UI can show "3 conflicts" without walking every field. */
        @JsonProperty("change_summary") Map<String, Integer> changeSummary,

        @JsonProperty("sections") Map<String, Map<String, AnnotatedField>> sections) {

    /**
     * Rebuilds the derived {@code change_summary} from the sections.
     *
     * <p>Derived data is recomputed rather than trusted from input, so a
     * hand-edited or stale backup file cannot restore a case whose summary
     * disagrees with its own fields.
     */
    public CaseView withRecomputedSummary() {
        Map<FieldStatus, Integer> counts = new EnumMap<>(FieldStatus.class);
        for (Map<String, AnnotatedField> section : sections.values()) {
            for (AnnotatedField field : section.values()) {
                counts.merge(field.status(), 1, Integer::sum);
            }
        }

        // Every status appears, including zeroes, so the UI can bind to fixed keys.
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (FieldStatus status : FieldStatus.values()) {
            summary.put(status.wireName(), counts.getOrDefault(status, 0));
        }
        return new CaseView(
                caseId, version, comparedToVersion, caseClassification, extractedAt,
                sourceDocument, missingFields, summary, sections);
    }

    /** Condensed form for {@code GET /cases}; the backup script pages through these. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(
            @JsonProperty("case_id") String caseId,
            @JsonProperty("version") int version,
            @JsonProperty("case_classification") String caseClassification,
            @JsonProperty("extracted_at") String extractedAt) {
    }

    public Summary toSummary() {
        return new Summary(caseId, version, caseClassification, extractedAt);
    }
}
