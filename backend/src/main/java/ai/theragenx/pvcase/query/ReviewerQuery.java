package ai.theragenx.pvcase.query;

/**
 * A question a case processor raised against one specific field.
 *
 * <p>Queries are camelCase on the wire while cases are snake_case. That
 * inconsistency is deliberate rather than an oversight: the case shape is
 * dictated by the extraction pipeline's output format, which this service does
 * not own, and the query shape is specified in the brief as
 * {@code {caseId, fieldPath, question}}. Renaming either to match the other
 * would mean breaking a contract someone else depends on for the sake of
 * internal tidiness. It is documented in the README so it surprises nobody.
 */
public record ReviewerQuery(
        String id,
        String caseId,
        String fieldPath,
        String question,
        String createdAt) {
}
