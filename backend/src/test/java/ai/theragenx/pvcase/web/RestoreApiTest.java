package ai.theragenx.pvcase.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Restore is the most dangerous endpoint in the service: it replaces a case
 * wholesale, has no auth, and its input is a file that has been sitting on disk
 * where anyone could have edited it. Unlike a follow-up it bypasses the payload
 * normaliser entirely, so it needs its own validation and its own tests.
 *
 * <p>Every case below produced either silent corruption or a 500 before the
 * validator existed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestoreApiTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String VALID_FIELD = """
            {"field_path": "patient.age", "label": "Age", "value": "62",
             "confidence": 0.91, "source": "p.2 §1", "status": "baseline",
             "missing_in_follow_up": false}
            """;

    private String snapshot(String extraTopLevel, String field) {
        return """
                {"case_id": "PV-REST-0001", %s
                 "sections": {"patient": {"age": %s}}}
                """.formatted(extraTopLevel, field);
    }

    private org.springframework.test.web.servlet.ResultActions restore(String body)
            throws Exception {
        return mockMvc.perform(put("/cases/{id}", "PV-REST-0001")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("a well-formed snapshot restores and is readable")
    void validSnapshotRestores() throws Exception {
        // Its own case id: the whole class shares one in-memory store, and JUnit
        // makes no ordering promise, so reusing PV-REST-0001 would make the
        // created-vs-replaced assertion depend on which test happened to run first.
        String body = snapshot("\"version\": 3,", VALID_FIELD)
                .replace("PV-REST-0001", "PV-REST-CREATE");

        mockMvc.perform(put("/cases/{id}", "PV-REST-CREATE")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(3)));

        mockMvc.perform(get("/cases/{id}", "PV-REST-CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.patient.age.value", is("62")));
    }

    @Test
    @DisplayName("a snapshot with no version is rejected — a primitive int reads it as 0")
    void missingVersionIsRejected() throws Exception {
        restore(snapshot("", VALID_FIELD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("version")));
    }

    @Test
    @DisplayName("an extreme version is rejected before it can overflow the next merge")
    void overflowVersionIsRejected() throws Exception {
        restore(snapshot("\"version\": 2147483647,", VALID_FIELD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("version")));
    }

    @Test
    @DisplayName("a field with no status is a 400 naming the field, not a 500")
    void missingStatusIsRejected() throws Exception {
        restore(snapshot("\"version\": 1,", """
                {"field_path": "patient.age", "value": "62"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("patient.age.status")));
    }

    @Test
    @DisplayName("a field with no value is rejected")
    void missingValueIsRejected() throws Exception {
        restore(snapshot("\"version\": 1,", """
                {"field_path": "patient.age", "status": "baseline"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("patient.age.value")));
    }

    @Test
    @DisplayName("a field_path disagreeing with its position would misroute queries")
    void mismatchedFieldPathIsRejected() throws Exception {
        restore(snapshot("\"version\": 1,", """
                {"field_path": "patient.weight_kg", "value": "62", "status": "baseline"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("patient.age.field_path")));
    }

    @Test
    @DisplayName("overridden without previous_value would render a conflict with nothing to compare")
    void overriddenWithoutPreviousValueIsRejected() throws Exception {
        restore(snapshot("\"version\": 2,", """
                {"field_path": "patient.age", "value": "63", "status": "overridden"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("patient.age.previous_value")));
    }

    @Test
    @DisplayName("previous_value on a non-overridden field would render a conflict that never happened")
    void previousValueOnUnchangedIsRejected() throws Exception {
        restore(snapshot("\"version\": 2,", """
                {"field_path": "patient.age", "value": "63", "status": "unchanged",
                 "previous_value": "62"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("patient.age.previous_value")));
    }

    @Test
    @DisplayName("confidence outside [0,1] is rejected on restore too")
    void outOfRangeConfidenceIsRejected() throws Exception {
        restore(snapshot("\"version\": 1,", """
                {"field_path": "patient.age", "value": "62", "status": "baseline",
                 "confidence": 99}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("patient.age.confidence")));
    }

    @Test
    @DisplayName("compared_to_version must precede version")
    void impossibleComparisonIsRejected() throws Exception {
        restore(snapshot("\"version\": 2, \"compared_to_version\": 5,", VALID_FIELD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("compared_to_version")));
    }

    @Test
    @DisplayName("an unknown status is valid JSON, so it must not be called malformed JSON")
    void unknownStatusIsReportedAsInvalidNotMalformed() throws Exception {
        restore(snapshot("\"version\": 1,", """
                {"field_path": "patient.age", "value": "62", "status": "definitely-not-a-status"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_PAYLOAD")));
    }

    @Test
    @DisplayName("a rejected restore leaves the existing case untouched")
    void rejectedRestoreDoesNotMutate() throws Exception {
        restore(snapshot("\"version\": 4,", VALID_FIELD)).andExpect(status().is2xxSuccessful());

        restore(snapshot("\"version\": 0,", VALID_FIELD)).andExpect(status().isBadRequest());

        mockMvc.perform(get("/cases/{id}", "PV-REST-0001"))
                .andExpect(jsonPath("$.version", is(4)));
    }

    // ------------------------------------------------------------ protocol

    @Test
    @DisplayName("wrong verb is 405, not 500")
    void wrongMethodIs405() throws Exception {
        mockMvc.perform(delete("/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code", is("METHOD_NOT_ALLOWED")));
    }

    @Test
    @DisplayName("a forgotten Content-Type is 415 with an actionable message, not 500")
    void wrongContentTypeIs415() throws Exception {
        mockMvc.perform(post("/cases/{id}/follow-ups", "PV-2026-0451")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"sections\":{}}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_MEDIA_TYPE")));
    }
}
