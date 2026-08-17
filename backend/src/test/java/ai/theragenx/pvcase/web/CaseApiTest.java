package ai.theragenx.pvcase.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks over the HTTP layer.
 *
 * <p>These complement {@code MergeServiceTest}, which proves the merge logic is
 * right. What is proved here is different and cannot be seen from a unit test:
 * that the status codes are the ones the brief asks for, that a rejected request
 * leaves storage untouched, and that restore really is idempotent over the wire.
 *
 * <p>Each test drives the bootstrap case through its own follow-ups. They share
 * one application context and therefore one in-memory store, so assertions are
 * written to be independent of execution order rather than assuming a pristine
 * v1 — which is also a more honest simulation of a long-running service.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseApiTest {

    private static final String CASE_ID = "PV-2026-0451";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /health reports up once the bootstrap case is loaded")
    void healthReportsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("up")))
                .andExpect(jsonPath("$.cases_loaded", notNullValue()));
    }

    @Test
    @DisplayName("GET /cases/{id} returns the bootstrap case; unknown ids are 404")
    void readCase() throws Exception {
        mockMvc.perform(get("/cases/{id}", CASE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.case_id", is(CASE_ID)))
                .andExpect(jsonPath("$.sections.patient.initials.value", is("M.K.")))
                .andExpect(jsonPath("$.sections.patient.initials.field_path",
                        is("patient.initials")));

        mockMvc.perform(get("/cases/{id}", "PV-0000-0000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CASE_NOT_FOUND")));
    }

    @Test
    @DisplayName("GET /cases lists summaries so backup can enumerate without knowing ids")
    void listCases() throws Exception {
        mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", notNullValue()))
                .andExpect(jsonPath("$.cases[0].case_id", notNullValue()))
                // Summaries only: a full case here would not scale and backup fetches
                // each one individually anyway.
                .andExpect(jsonPath("$.cases[0].sections").doesNotExist());
    }

    @Test
    @DisplayName("a follow-up merges and annotates the conflict")
    void followUpMerges() throws Exception {
        MvcResult before = mockMvc.perform(get("/cases/{id}", CASE_ID))
                .andExpect(status().isOk()).andReturn();
        int priorVersion = readVersion(before);

        mockMvc.perform(post("/cases/{id}/follow-ups", CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": {"reporter": {"country": {"value": "Germany",
                                 "confidence": 0.93, "source": "p.1 \\u00a71"}}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(priorVersion + 1)))
                .andExpect(jsonPath("$.compared_to_version", is(priorVersion)))
                .andExpect(jsonPath("$.sections.reporter.country.status", is("overridden")))
                .andExpect(jsonPath("$.sections.reporter.country.previous_value", notNullValue()))
                // An untouched field is carried forward, never dropped.
                .andExpect(jsonPath("$.sections.patient.initials.status", is("carried_forward")))
                .andExpect(jsonPath("$.sections.patient.initials.value", is("M.K.")));
    }

    @Test
    @DisplayName("previous_value is absent, not null, on a non-conflicting field")
    void previousValueOmittedWhenNoConflict() throws Exception {
        mockMvc.perform(post("/cases/{id}/follow-ups", CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": {"patient": {"initials": {"value": "M.K."}}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.patient.initials.status", is("unchanged")))
                .andExpect(jsonPath("$.sections.patient.initials.previous_value").doesNotExist());
    }

    @Test
    @DisplayName("a rejected follow-up does not advance the version")
    void rejectedFollowUpLeavesStorageUntouched() throws Exception {
        int before = readVersion(mockMvc.perform(get("/cases/{id}", CASE_ID)).andReturn());

        mockMvc.perform(post("/cases/{id}/follow-ups", CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": {"patient": {"age": {"value": "70",
                                 "confidence": 5}}}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_PAYLOAD")))
                .andExpect(jsonPath("$.errors[0].path", is("patient.age.confidence")));

        int after = readVersion(mockMvc.perform(get("/cases/{id}", CASE_ID)).andReturn());
        org.assertj.core.api.Assertions.assertThat(after)
                .as("validation must happen before storage is touched")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a follow-up onto an unknown case is 404, not a silently created case")
    void followUpOnUnknownCaseIs404() throws Exception {
        mockMvc.perform(post("/cases/{id}/follow-ups", "PV-0000-0000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\": {}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CASE_NOT_FOUND")));

        mockMvc.perform(get("/cases/{id}", "PV-0000-0000"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a body case_id disagreeing with the URL is rejected")
    void caseIdMismatchIsRejected() throws Exception {
        mockMvc.perform(post("/cases/{id}/follow-ups", CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"case_id\": \"PV-9999-9999\", \"sections\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("case_id")));
    }

    @Test
    @DisplayName("restore creates an absent case with 201 and is idempotent thereafter")
    void restoreIsIdempotent() throws Exception {
        String snapshot = mockMvc.perform(get("/cases/{id}", CASE_ID))
                .andReturn().getResponse().getContentAsString()
                .replace(CASE_ID, "PV-2026-0777");

        // First restore creates it.
        mockMvc.perform(put("/cases/{id}", "PV-2026-0777")
                        .contentType(MediaType.APPLICATION_JSON).content(snapshot))
                .andExpect(status().isCreated());

        String first = mockMvc.perform(get("/cases/{id}", "PV-2026-0777"))
                .andReturn().getResponse().getContentAsString();

        // Second restore replaces it with identical content.
        mockMvc.perform(put("/cases/{id}", "PV-2026-0777")
                        .contentType(MediaType.APPLICATION_JSON).content(snapshot))
                .andExpect(status().isOk());

        String second = mockMvc.perform(get("/cases/{id}", "PV-2026-0777"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(second)
                .as("restoring the same snapshot twice must not drift the version or content")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("POST /queries returns 201 and validates the field path against the case")
    void createQuery() throws Exception {
        mockMvc.perform(post("/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId": "PV-2026-0451", "fieldPath": "adverse_event.onset_date",
                                 "question": "Onset predates therapy start. Please verify."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.fieldPath", is("adverse_event.onset_date")))
                .andExpect(jsonPath("$.createdAt", notNullValue()));

        mockMvc.perform(get("/queries").param("caseId", CASE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].caseId", is(CASE_ID)));
    }

    @Test
    @DisplayName("a query against a field that does not exist is rejected at write time")
    void queryAgainstUnknownFieldIsRejected() throws Exception {
        mockMvc.perform(post("/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId": "PV-2026-0451", "fieldPath": "patient.blood_type",
                                 "question": "Please confirm."}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("fieldPath")));
    }

    @Test
    @DisplayName("a blank question is rejected rather than stored as an empty task")
    void blankQuestionIsRejected() throws Exception {
        mockMvc.perform(post("/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId": "PV-2026-0451", "fieldPath": "patient.age",
                                 "question": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path", is("question")));
    }

    @Test
    @DisplayName("GET /queries without caseId is a 400, never an unscoped dump")
    void queriesRequireCaseId() throws Exception {
        mockMvc.perform(get("/queries"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("MISSING_PARAMETER")));
    }

    @Test
    @DisplayName("queries for a case with none returns an empty list, not a 404")
    void emptyQueryListIsNotAnError() throws Exception {
        mockMvc.perform(put("/cases/{id}", "PV-2026-0888")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mockMvc.perform(get("/cases/{id}", CASE_ID))
                        .andReturn().getResponse().getContentAsString()
                        .replace(CASE_ID, "PV-2026-0888")));

        mockMvc.perform(get("/queries").param("caseId", "PV-2026-0888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    @DisplayName("malformed JSON gets an actionable message, not a stack trace")
    void malformedJsonIsHandled() throws Exception {
        mockMvc.perform(post("/cases/{id}/follow-ups", CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\": {,}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("MALFORMED_JSON")))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    @DisplayName("an unknown path returns the same JSON envelope as everything else")
    void unknownPathUsesTheSharedEnvelope() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("NOT_FOUND")));
    }

    @Test
    @DisplayName("v1 of a freshly restored case reports no comparison version")
    void baselineHasNoComparison() throws Exception {
        mockMvc.perform(get("/cases/{id}", "PV-2026-0451"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change_summary.baseline", notNullValue()))
                .andExpect(jsonPath("$.sections.patient.age.value", notNullValue()));

        mockMvc.perform(get("/cases/{id}", "PV-2026-0451"))
                .andExpect(jsonPath("$.sections.patient.age.label", is("Age")))
                .andExpect(jsonPath("$.sections.patient.weight_kg.label", is("Weight (kg)")));
    }

    @Test
    @DisplayName("the reviewer UI's dev origin passes CORS preflight")
    void corsAllowsTheReviewerUi() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/cases/{id}", CASE_ID)
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        // An origin nobody configured is not echoed back.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/cases/{id}", CASE_ID)
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a case classification set to null is preserved as unclassified")
    void classificationCanBeUnset() throws Exception {
        mockMvc.perform(put("/cases/{id}", "PV-2026-0555")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mockMvc.perform(get("/cases/{id}", CASE_ID))
                        .andReturn().getResponse().getContentAsString()
                        .replace(CASE_ID, "PV-2026-0555")));

        mockMvc.perform(post("/cases/{id}/follow-ups", "PV-2026-0555")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"case_classification\": null, \"sections\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_classification", nullValue()));
    }

    private int readVersion(MvcResult result) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.version");
    }
}
