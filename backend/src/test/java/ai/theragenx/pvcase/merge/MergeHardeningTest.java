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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regressions for defects found in an adversarial review pass, after the first
 * implementation was already green.
 *
 * <p>Kept separate from {@code MergeServiceTest} deliberately: those tests
 * describe the intended behaviour of the merge, these pin down specific ways it
 * was wrong. Each one below corresponds to a real bug that existed and was
 * fixed, and several of them were cases where the code contradicted this repo's
 * own documentation.
 */
class MergeHardeningTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MergeService mergeService;
    private CaseView caseV1;

    @BeforeEach
    void setUp() throws IOException {
        mergeService = new MergeService(new CasePayloadNormalizer());
        try (InputStream in = getClass().getResourceAsStream("/case_v1.json")) {
            caseV1 = mergeService.buildBaseline("PV-2026-0451",
                    mergeService.normalizer().normalize(objectMapper.readTree(in), true));
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
        return view.sections().get(section).get(name);
    }

    // -------------------------------------------------------------- provenance

    @Nested
    @DisplayName("provenance is never synthesised from two different extractions")
    class Provenance {

        @Test
        @DisplayName("an unchanged value takes the follow-up's provenance as a tuple, nulls included")
        void unchangedTakesProvenanceAsATuple() {
            // v1 had confidence 0.91 and source "p.2 §1". The follow-up restates the
            // same value with a confidence but no source.
            CaseView v2 = mergeService.merge(caseV1, followUp("""
                    {"sections": {"patient": {"age": {"value": "62", "confidence": 0.55}}}}
                    """));

            AnnotatedField age = field(v2, "patient", "age");
            assertThat(age.status()).isEqualTo(FieldStatus.UNCHANGED);
            assertThat(age.confidence()).isEqualTo(0.55);
            assertThat(age.source())
                    .as("keeping v1's 'p.2 §1' here would present (0.55, p.2 §1) as one "
                            + "coherent extraction; no extraction ever produced that pair")
                    .isNull();
        }

        @Test
        @DisplayName("a bare scalar restatement carries no borrowed confidence")
        void bareScalarRestatementIsUnscored() {
            CaseView v2 = mergeService.merge(caseV1, followUp("""
                    {"sections": {"patient": {"sex": "Male"}}}
                    """));

            AnnotatedField sex = field(v2, "patient", "sex");
            assertThat(sex.status()).isEqualTo(FieldStatus.UNCHANGED);
            assertThat(sex.confidence()).isNull();
            assertThat(sex.source()).isNull();
        }

        @Test
        @DisplayName("a carried-forward field does keep its own provenance")
        void carriedForwardKeepsItsOwnProvenance() {
            CaseView v2 = mergeService.merge(caseV1, followUp("""
                    {"sections": {"patient": {"age": {"value": "63"}}}}
                    """));

            AnnotatedField sex = field(v2, "patient", "sex");
            assertThat(sex.status()).isEqualTo(FieldStatus.CARRIED_FORWARD);
            assertThat(sex.confidence())
                    .as("nothing was restated, so v1's provenance still describes this value")
                    .isEqualTo(0.99);
            assertThat(sex.source()).isEqualTo("p.2 §1");
        }

        @Test
        @DisplayName("version provenance is not inherited from the previous version")
        void versionProvenanceIsNotInherited() {
            CaseView v2 = mergeService.merge(caseV1, followUp("""
                    {"sections": {"patient": {"age": {"value": "63"}}}}
                    """));

            assertThat(v2.sourceDocument())
                    .as("inheriting would have v2 claim it came from the initial report PDF")
                    .isNull();
            assertThat(v2.extractedAt()).isNull();
            assertThat(caseV1.sourceDocument())
                    .as("v1 still records its own")
                    .isEqualTo("initial_report_PV-2026-0451.pdf");
        }
    }

    // ---------------------------------------------------------- value equality

    @Nested
    @DisplayName("numeric values compare numerically, not lexically")
    class NumericEquality {

        private CaseView caseWithNumericDose() {
            return mergeService.merge(caseV1, followUp("""
                    {"sections": {"suspect_drug": {"dose_mg": 20}}}
                    """));
        }

        @Test
        @DisplayName("1 and 1.0 are the same number, not a conflict")
        void integerAndDecimalAreEqual() {
            CaseView v2 = caseWithNumericDose();
            CaseView v3 = mergeService.merge(v2, followUp("""
                    {"sections": {"suspect_drug": {"dose_mg": 20.0}}}
                    """));

            assertThat(field(v3, "suspect_drug", "dose_mg").status())
                    .as("20 and 20.0 are one dose; JSON formatting is not clinical news")
                    .isEqualTo(FieldStatus.UNCHANGED);
        }

        @Test
        @DisplayName("a genuinely different number is still a conflict")
        void differentNumberIsStillOverridden() {
            CaseView v2 = caseWithNumericDose();
            CaseView v3 = mergeService.merge(v2, followUp("""
                    {"sections": {"suspect_drug": {"dose_mg": 40}}}
                    """));

            assertThat(field(v3, "suspect_drug", "dose_mg").status())
                    .isEqualTo(FieldStatus.OVERRIDDEN);
        }
    }

    // ------------------------------------------------------- name constraints

    @Nested
    @DisplayName("names that would make field paths ambiguous are rejected")
    class NameConstraints {

        @Test
        @DisplayName("a dot in a field name is refused rather than producing a colliding path")
        void dottedFieldNameIsRejected() {
            // Section "a" field "b.c" and section "a.b" field "c" would both emit
            // field_path "a.b.c", and one missing_fields entry would flag both.
            assertThatThrownBy(() -> followUp("""
                    {"sections": {"a": {"b.c": "X"}}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class)
                    .satisfies(e -> assertThat(((InvalidPayloadException) e).violations())
                            .singleElement()
                            .satisfies(v -> assertThat(v.message()).contains("must not contain")));
        }

        @Test
        @DisplayName("a dot in a section name is refused too")
        void dottedSectionNameIsRejected() {
            assertThatThrownBy(() -> followUp("""
                    {"sections": {"a.b": {"c": "X"}}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class);
        }

        @Test
        @DisplayName("a section literally named 'sections' is refused, it collides with the path prefix")
        void reservedSectionNameIsRejected() {
            assertThatThrownBy(() -> followUp("""
                    {"sections": {"sections": {"age": "1"}}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class)
                    .satisfies(e -> assertThat(((InvalidPayloadException) e).violations())
                            .singleElement()
                            .satisfies(v -> assertThat(v.message()).contains("reserved")));
        }

        @Test
        @DisplayName("surrounding whitespace is refused, since path resolution trims")
        void paddedNameIsRejected() {
            assertThatThrownBy(() -> followUp("""
                    {"sections": {"patient": {" age ": "63"}}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class);
        }

        @Test
        @DisplayName("ordinary snake_case names are still fine")
        void normalNamesAreAccepted() {
            assertThatCode(() -> followUp("""
                    {"sections": {"medical_history": {"concomitant_medication": "Metformin"}}}
                    """)).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------ value constraints

    @Nested
    @DisplayName("values that are not really values are rejected")
    class ValueConstraints {

        @Test
        @DisplayName("a blank string would silently overwrite a real clinical value")
        void blankValueIsRejected() {
            assertThatThrownBy(() -> followUp("""
                    {"sections": {"patient": {"age": {"value": "   "}}}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class)
                    .satisfies(e -> assertThat(((InvalidPayloadException) e).violations())
                            .singleElement()
                            .satisfies(v -> assertThat(v.message()).contains("missing_fields")));
        }

        @Test
        @DisplayName("an unrecognised case_classification is refused, a typo must not read as valid")
        void unknownClassificationIsRejected() {
            assertThatThrownBy(() -> followUp("""
                    {"case_classification": "signficant", "sections": {}}
                    """))
                    .isInstanceOf(InvalidPayloadException.class)
                    .satisfies(e -> assertThat(((InvalidPayloadException) e).violations())
                            .singleElement()
                            .satisfies(v -> assertThat(v.path()).isEqualTo("case_classification")));
        }

        @Test
        @DisplayName("a payload with nothing recognisable is refused, not treated as a no-op")
        void unrecognisablePayloadIsRejected() {
            // Previously returned 200 and appended a version recording nothing, which
            // tells a caller their submission landed when it was discarded entirely.
            assertThatThrownBy(() -> followUp("{\"patient_data\": {\"age\": 63}, \"drug\": \"X\"}"))
                    .isInstanceOf(InvalidPayloadException.class)
                    .satisfies(e -> assertThat(((InvalidPayloadException) e).violations())
                            .anySatisfy(v -> assertThat(v.message())
                                    .contains("no recognised case data")));
        }

        @Test
        @DisplayName("unknown keys alongside recognised ones are still tolerated")
        void unknownKeysBesideRecognisedOnesAreFine() {
            // An extraction pipeline attaching its own run metadata must not be
            // punished for it.
            assertThatCode(() -> followUp(
                    "{\"pipeline_run_id\": \"abc-123\", \"reviewer\": \"someone\","
                            + " \"sections\": {\"patient\": {\"age\": {\"value\": \"63\"}}}}"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a classification-only follow-up is still legitimate")
        void classificationOnlyPayloadIsAccepted() {
            assertThatCode(() -> followUp("{\"case_classification\": \"significant\"}"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("both permitted classifications and explicit null still work")
        void validClassificationsAreAccepted() {
            assertThat(mergeService.merge(caseV1, followUp("""
                    {"case_classification": "significant", "sections": {}}
                    """)).caseClassification()).isEqualTo("significant");

            assertThat(mergeService.merge(caseV1, followUp("""
                    {"case_classification": null, "sections": {}}
                    """)).caseClassification()).isNull();
        }
    }
}
