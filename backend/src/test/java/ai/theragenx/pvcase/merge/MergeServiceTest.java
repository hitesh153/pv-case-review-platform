package ai.theragenx.pvcase.merge;

import ai.theragenx.pvcase.domain.AnnotatedField;
import ai.theragenx.pvcase.domain.CaseView;
import ai.theragenx.pvcase.domain.FieldStatus;
import ai.theragenx.pvcase.web.error.InvalidPayloadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edge cases of the follow-up merge.
 *
 * <p>These run against the real normaliser rather than hand-built payload
 * objects, so each test exercises the same path a request takes: raw JSON in,
 * annotated case out. That is deliberate — most of the ways this can go wrong
 * live at the parsing boundary, and tests that skip it would pass while the
 * endpoint broke.
 */
class MergeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MergeService mergeService;
    private CaseView caseV1;

    @BeforeEach
    void setUp() throws IOException {
        mergeService = new MergeService(new CasePayloadNormalizer());
        caseV1 = mergeService.buildBaseline("PV-2026-0451", parseSeed());
    }

    // ---------------------------------------------------------------- helpers

    private NormalizedCasePayload parseSeed() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/case_v1.json")) {
            assertThat(in).as("bootstrap fixture on the test classpath").isNotNull();
            return mergeService.normalizer().normalize(objectMapper.readTree(in), true);
        }
    }

    private NormalizedCasePayload followUp(String json) {
        try {
            return mergeService.normalizer().normalize(objectMapper.readTree(json), false);
        } catch (IOException e) {
            throw new IllegalArgumentException("bad test fixture", e);
        }
    }

    private AnnotatedField field(CaseView view, String section, String name) {
        assertThat(view.sections()).containsKey(section);
        assertThat(view.sections().get(section)).containsKey(name);
        return view.sections().get(section).get(name);
    }

    // ------------------------------------------------------------- baseline

    @Test
    @DisplayName("v1 fields are baseline, not unchanged — there is nothing to compare against")
    void baselineIsNotUnchanged() {
        assertThat(caseV1.version()).isEqualTo(1);
        assertThat(caseV1.comparedToVersion()).isNull();
        assertThat(field(caseV1, "patient", "age").status()).isEqualTo(FieldStatus.BASELINE);
        assertThat(field(caseV1, "patient", "age").value().asText()).isEqualTo("62");
        assertThat(field(caseV1, "patient", "age").label()).isEqualTo("Age");
        assertThat(field(caseV1, "patient", "weight_kg").label()).isEqualTo("Weight (kg)");
        assertThat(caseV1.changeSummary()).containsEntry("baseline", 14);
    }

    // ------------------------------------------------------- the four statuses

    @Test
    @DisplayName("a different value is overridden and carries the previous value")
    void differentValueIsOverridden() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"sections": {"patient": {"age": {"value": "63", "confidence": 0.88,
                 "source": "p.2 \\u00a71"}}}}
                """));

        AnnotatedField age = field(v2, "patient", "age");
        assertThat(age.status()).isEqualTo(FieldStatus.OVERRIDDEN);
        assertThat(age.value().asText()).isEqualTo("63");
        assertThat(age.previousValue().asText()).isEqualTo("62");
        assertThat(age.confidence()).isEqualTo(0.88);
    }

    @Test
    @DisplayName("a restated identical value is unchanged even when confidence moved")
    void sameValueWithNewConfidenceIsUnchanged() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"sections": {"patient": {"age": {"value": "62", "confidence": 0.55,
                 "source": "p.9 \\u00a74"}}}}
                """));

        AnnotatedField age = field(v2, "patient", "age");
        assertThat(age.status()).isEqualTo(FieldStatus.UNCHANGED);
        assertThat(age.previousValue()).as("no conflict, so nothing to show side by side").isNull();
        // The fresher extraction metadata is taken even though the value held.
        assertThat(age.confidence()).isEqualTo(0.55);
        assertThat(age.source()).isEqualTo("p.9 §4");
    }

    @Test
    @DisplayName("a field the follow-up never mentions is carried forward, never deleted")
    void omittedFieldIsCarriedForward() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"sections": {"patient": {"age": {"value": "63"}}}}
                """));

        AnnotatedField sex = field(v2, "patient", "sex");
        assertThat(sex.status()).isEqualTo(FieldStatus.CARRIED_FORWARD);
        assertThat(sex.value().asText()).isEqualTo("Male");
        assertThat(sex.confidence()).as("original provenance survives intact").isEqualTo(0.99);
        assertThat(sex.source()).isEqualTo("p.2 §1");

        // Nothing anywhere in the case was dropped.
        int v1Fields = caseV1.sections().values().stream().mapToInt(java.util.Map::size).sum();
        int v2Fields = v2.sections().values().stream().mapToInt(java.util.Map::size).sum();
        assertThat(v2Fields).isEqualTo(v1Fields);
    }

    @Test
    @DisplayName("a field absent from v1 is new")
    void unseenFieldIsNew() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"sections": {"patient": {"ethnicity": {"value": "Asian", "confidence": 0.7,
                 "source": "p.2 \\u00a73"}}}}
                """));

        AnnotatedField ethnicity = field(v2, "patient", "ethnicity");
        assertThat(ethnicity.status()).isEqualTo(FieldStatus.NEW);
        assertThat(ethnicity.previousValue()).isNull();
        assertThat(ethnicity.label()).isEqualTo("Ethnicity");
    }

    @Test
    @DisplayName("a whole section that did not exist before is accepted and marked new")
    void unseenSectionIsAccepted() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"sections": {"medical_history": {"condition": {"value": "Type 2 diabetes",
                 "confidence": 0.84, "source": "p.6 \\u00a72"}}}}
                """));

        assertThat(v2.sections()).containsKey("medical_history");
        assertThat(field(v2, "medical_history", "condition").status()).isEqualTo(FieldStatus.NEW);
        // Established sections keep their position; the new one lands at the end.
        assertThat(v2.sections().keySet())
                .containsExactly("patient", "suspect_drug", "adverse_event", "reporter",
                        "medical_history");
    }

    // ------------------------------------------------------------ missing_fields

    @Test
    @DisplayName("missing_fields annotates a carried-forward value, it never erases it")
    void missingFieldPreservesPriorValue() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"missing_fields": ["adverse_event.outcome"],
                 "sections": {"patient": {"age": {"value": "63"}}}}
                """));

        AnnotatedField outcome = field(v2, "adverse_event", "outcome");
        assertThat(outcome.value().asText()).as("the value survives").isEqualTo("Recovered");
        assertThat(outcome.status()).isEqualTo(FieldStatus.CARRIED_FORWARD);
        assertThat(outcome.missingInFollowUp())
                .as("but the reviewer is told the newest document did not confirm it")
                .isTrue();
        assertThat(v2.missingFields()).containsExactly("adverse_event.outcome");
    }

    @Test
    @DisplayName("missing_fields replaces the previous version's list rather than accumulating")
    void missingFieldsDoNotAccumulate() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"missing_fields": ["adverse_event.outcome"], "sections": {}}
                """));
        CaseView v3 = mergeService.merge(v2, followUp("""
                {"missing_fields": ["patient.weight_kg"], "sections": {}}
                """));

        assertThat(v3.missingFields()).containsExactly("patient.weight_kg");
        assertThat(field(v3, "adverse_event", "outcome").missingInFollowUp())
                .as("a later extraction that stopped complaining clears the flag")
                .isFalse();
    }

    @Test
    @DisplayName("missing_fields paths are resolved in several notations, ambiguity is not guessed")
    void missingFieldPathNotations() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"missing_fields": ["sections.patient.sex", "/sections/reporter/country",
                 "indication"], "sections": {}}
                """));

        assertThat(field(v2, "patient", "sex").missingInFollowUp()).isTrue();
        assertThat(field(v2, "reporter", "country").missingInFollowUp()).isTrue();
        assertThat(field(v2, "suspect_drug", "indication"))
                .as("a bare name matching exactly one field resolves")
                .satisfies(f -> assertThat(f.missingInFollowUp()).isTrue());
    }

    @Test
    @DisplayName("an unresolvable missing_fields entry stays visible instead of being dropped")
    void unresolvableMissingFieldIsStillReported() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"missing_fields": ["nonexistent.field"], "sections": {}}
                """));

        assertThat(v2.missingFields()).containsExactly("nonexistent.field");
        assertThat(v2.sections().values().stream()
                .flatMap(s -> s.values().stream())
                .filter(AnnotatedField::missingInFollowUp))
                .as("nothing was arbitrarily flagged")
                .isEmpty();
    }

    // --------------------------------------------------------- shape tolerance

    @Test
    @DisplayName("a bare scalar is accepted as a value with no provenance attached")
    void bareScalarIsAcceptedWithoutBorrowedProvenance() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"sections": {"patient": {"age": 63}}}
                """));

        AnnotatedField age = field(v2, "patient", "age");
        assertThat(age.status()).isEqualTo(FieldStatus.OVERRIDDEN);
        assertThat(age.value().asInt()).isEqualTo(63);
        assertThat(age.confidence())
                .as("the old 0.91 belonged to the old value and must not follow the new one")
                .isNull();
        assertThat(age.source()).isNull();
    }

    @Test
    @DisplayName("a JSON type change on an identical value is not a clinical conflict")
    void scalarRetypingIsNotAConflict() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"sections": {"patient": {"age": 62}}}
                """));

        assertThat(field(v2, "patient", "age").status())
                .as("the string 62 and the number 62 are the same fact about the patient")
                .isEqualTo(FieldStatus.UNCHANGED);
    }

    @Test
    @DisplayName("camelCase key aliases are accepted alongside snake_case")
    void camelCaseAliasesAreAccepted() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"caseClassification": "significant", "missingFields": ["patient.sex"],
                 "sourceDocument": "follow_up.pdf", "sections": {}}
                """));

        assertThat(v2.caseClassification()).isEqualTo("significant");
        assertThat(v2.missingFields()).containsExactly("patient.sex");
        assertThat(v2.sourceDocument()).isEqualTo("follow_up.pdf");
    }

    // ------------------------------------------------------------- versioning

    @Test
    @DisplayName("merging bumps the version and records what the diff is against")
    void versionsAdvance() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"sections": {"patient": {"age": {"value": "63"}}}}
                """));
        CaseView v3 = mergeService.merge(v2, followUp("""
                {"sections": {}}
                """));

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.comparedToVersion()).isEqualTo(1);
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.comparedToVersion()).isEqualTo(2);

        assertThat(field(v3, "patient", "age").status())
                .as("status describes the latest transition, not unresolved history")
                .isEqualTo(FieldStatus.CARRIED_FORWARD);
        assertThat(field(v3, "patient", "age").value().asText()).isEqualTo("63");
        assertThat(field(v3, "patient", "age").previousValue())
                .as("v2's conflict must not linger into v3")
                .isNull();
    }

    @Test
    @DisplayName("merging does not mutate the previous version")
    void previousVersionIsUntouched() {
        mergeService.merge(caseV1, followUp("""
                {"sections": {"patient": {"age": {"value": "63"}}}}
                """));

        assertThat(field(caseV1, "patient", "age").value().asText()).isEqualTo("62");
        assertThat(field(caseV1, "patient", "age").status()).isEqualTo(FieldStatus.BASELINE);
    }

    @Test
    @DisplayName("change_summary counts every status, including zeroes")
    void changeSummaryIsComplete() {
        CaseView v2 = mergeService.merge(caseV1, followUp("""
                {"sections": {"patient": {"age": {"value": "63"}, "sex": {"value": "Male"},
                 "ethnicity": {"value": "Asian"}}}}
                """));

        assertThat(v2.changeSummary())
                .containsEntry("overridden", 1)
                .containsEntry("unchanged", 1)
                .containsEntry("new", 1)
                .containsEntry("carried_forward", 12)
                .containsEntry("baseline", 0);
    }

    // ------------------------------------------------------- classification

    @Nested
    @DisplayName("case_classification has three states, not two")
    class Classification {

        @Test
        @DisplayName("absent leaves the stored classification alone")
        void absentKeepsPrevious() {
            CaseView v2 = mergeService.merge(caseV1, followUp("""
                    {"sections": {}}
                    """));
            assertThat(v2.caseClassification()).isEqualTo("non-significant");
        }

        @Test
        @DisplayName("explicit null unsets it, which is a legitimate product state")
        void explicitNullUnsets() {
            CaseView v2 = mergeService.merge(caseV1, followUp("""
                    {"case_classification": null, "sections": {}}
                    """));
            assertThat(v2.caseClassification()).isNull();
        }

        @Test
        @DisplayName("a supplied value replaces it")
        void valueReplaces() {
            CaseView v2 = mergeService.merge(caseV1, followUp("""
                    {"case_classification": "significant", "sections": {}}
                    """));
            assertThat(v2.caseClassification()).isEqualTo("significant");
        }
    }

    // ---------------------------------------------------------- rejections

    @Nested
    @DisplayName("ambiguous input is rejected rather than guessed at")
    class Rejections {

        @Test
        @DisplayName("a field object with no value key could be a sub-section or a typo")
        void objectWithoutValueIsRejected() {
            assertThatThrownBy(() -> followUp("""
                    {"sections": {"patient": {"age": {"confidence": 0.9}}}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class)
                    .satisfies(e -> assertThat(((InvalidPayloadException) e).violations())
                            .singleElement()
                            .satisfies(v -> assertThat(v.path()).isEqualTo("patient.age")));
        }

        @Test
        @DisplayName("confidence outside [0,1] is rejected with its path")
        void outOfRangeConfidenceIsRejected() {
            assertThatThrownBy(() -> followUp("""
                    {"sections": {"patient": {"age": {"value": "63", "confidence": 1.4}}}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class)
                    .satisfies(e -> assertThat(((InvalidPayloadException) e).violations())
                            .singleElement()
                            .satisfies(v -> {
                                assertThat(v.path()).isEqualTo("patient.age.confidence");
                                assertThat(v.message()).contains("between 0 and 1");
                            }));
        }

        @Test
        @DisplayName("a null value is rejected — missing_fields is how you report unreadable")
        void nullValueIsRejected() {
            assertThatThrownBy(() -> followUp("""
                    {"sections": {"patient": {"age": {"value": null}}}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class)
                    .hasMessageContaining("invalid");
        }

        @Test
        @DisplayName("every problem is reported at once, not one per round trip")
        void violationsAreCollected() {
            assertThatThrownBy(() -> followUp("""
                    {"sections": {"patient": {"age": {"value": "63", "confidence": 9},
                     "sex": {"confidence": 0.5}, "weight_kg": {"value": "80",
                     "source": 12}}}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class)
                    .satisfies(e -> assertThat(((InvalidPayloadException) e).violations())
                            .hasSize(3)
                            .extracting(InvalidPayloadException.FieldViolation::path)
                            .containsExactlyInAnyOrder(
                                    "patient.age.confidence",
                                    "patient.sex",
                                    "patient.weight_kg.source"));
        }

        @Test
        @DisplayName("a non-object body is rejected outright")
        void nonObjectBodyIsRejected() {
            assertThatThrownBy(() -> followUp("[1, 2, 3]"))
                    .isInstanceOf(InvalidPayloadException.class);
        }
    }
}
