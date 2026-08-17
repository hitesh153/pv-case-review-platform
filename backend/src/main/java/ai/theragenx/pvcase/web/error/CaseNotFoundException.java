package ai.theragenx.pvcase.web.error;

/** No case with the requested id is in storage. Surfaces as HTTP 404. */
public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(String caseId) {
        super("No case found with id '" + caseId + "'");
    }
}
