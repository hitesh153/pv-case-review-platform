package ai.theragenx.pvcase.query;

import ai.theragenx.pvcase.domain.CaseView;
import ai.theragenx.pvcase.merge.MergeService;
import ai.theragenx.pvcase.store.CaseRepository;
import ai.theragenx.pvcase.web.error.InvalidPayloadException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stores reviewer queries in memory, keyed by case.
 *
 * <p>Queries are validated against the live case rather than accepted blindly.
 * A query is a question a human will have to answer later, so one attached to a
 * field path that does not exist is worse than useless — it is a task nobody can
 * action, discovered long after the reviewer moved on. Rejecting it at write
 * time costs one map lookup.
 */
@Service
public class QueryService {

    private final CaseRepository caseRepository;
    private final Map<String, List<ReviewerQuery>> queriesByCase = new ConcurrentHashMap<>();

    public QueryService(CaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    /**
     * Creates a query. Throws {@code CaseNotFoundException} (404) if the case is
     * unknown, or {@link InvalidPayloadException} (400) if the field path is not
     * a field of that case.
     */
    public ReviewerQuery create(CreateQueryRequest request) {
        CaseView caseView = caseRepository.requireLatest(request.caseId());

        String canonicalPath = MergeService
                .resolveFieldPath(request.fieldPath(), caseView.sections())
                .orElseThrow(() -> new InvalidPayloadException("fieldPath",
                        "'" + request.fieldPath() + "' is not a field of case "
                                + request.caseId()));

        ReviewerQuery query = new ReviewerQuery(
                UUID.randomUUID().toString(),
                caseView.caseId(),
                canonicalPath,
                request.question().trim(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());

        queriesByCase
                .computeIfAbsent(caseView.caseId(), id -> new CopyOnWriteArrayList<>())
                .add(query);

        return query;
    }

    /**
     * Queries for a case, oldest first.
     *
     * <p>A case with no queries yet returns an empty list, not a 404 — the
     * reviewer screen loads queries alongside the case and an empty result is the
     * normal state, not an error. An unknown case id is still a 404.
     */
    public List<ReviewerQuery> findByCaseId(String caseId) {
        if (!caseRepository.exists(caseId)) {
            throw new ai.theragenx.pvcase.web.error.CaseNotFoundException(caseId);
        }
        return List.copyOf(queriesByCase.getOrDefault(caseId, List.of()));
    }
}
