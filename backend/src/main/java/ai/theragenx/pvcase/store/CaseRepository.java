package ai.theragenx.pvcase.store;

import ai.theragenx.pvcase.domain.CaseView;
import ai.theragenx.pvcase.web.error.CaseNotFoundException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * In-memory case storage, versioned.
 *
 * <p>The brief only requires "the most recent version", and only the most recent
 * version is exposed over HTTP. History is still kept internally for two
 * reasons: a diff is meaningless without a well-defined predecessor, and
 * retaining prior versions as immutable snapshots removes any chance of
 * accidentally mutating v1 while constructing v2. Full snapshots cost nothing
 * at this scale.
 *
 * <p>This is not an audit trail. Everything is lost on restart, which is what
 * {@code ops/backup.sh} exists to mitigate.
 */
@Repository
public class CaseRepository {

    /** caseId -> immutable, append-only list of versions, oldest first. */
    private final Map<String, List<CaseView>> versionsByCase = new ConcurrentHashMap<>();

    public Optional<CaseView> findLatest(String caseId) {
        List<CaseView> versions = versionsByCase.get(caseId);
        if (versions == null || versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(versions.get(versions.size() - 1));
    }

    public CaseView requireLatest(String caseId) {
        return findLatest(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
    }

    public boolean exists(String caseId) {
        return versionsByCase.containsKey(caseId);
    }

    /** Latest version of every known case, ordered by id so backups diff cleanly. */
    public List<CaseView.Summary> listSummaries() {
        return versionsByCase.values().stream()
                .filter(versions -> !versions.isEmpty())
                .map(versions -> versions.get(versions.size() - 1))
                .map(CaseView::toSummary)
                .sorted(Comparator.comparing(CaseView.Summary::caseId))
                .toList();
    }

    /** Seeds the initial version. Rejects a second seed rather than silently resetting. */
    public CaseView seed(CaseView initialVersion) {
        List<CaseView> existing = versionsByCase.putIfAbsent(
                initialVersion.caseId(), List.of(initialVersion));
        if (existing != null) {
            throw new IllegalStateException(
                    "case " + initialVersion.caseId() + " has already been seeded");
        }
        return initialVersion;
    }

    /**
     * Applies {@code mergeFn} to the current latest version and appends the result
     * as a new version, atomically.
     *
     * <p>The whole read-merge-append runs inside {@link ConcurrentHashMap#compute},
     * so two concurrent follow-ups on the same case cannot both diff against the
     * same predecessor and lose one another's changes. If {@code mergeFn} throws —
     * which is how validation failures surface — the map entry is left untouched,
     * giving us "validate fully before mutating anything" for free.
     */
    public CaseView appendVersion(String caseId, UnaryOperator<CaseView> mergeFn) {
        List<CaseView> updated = versionsByCase.compute(caseId, (id, existing) -> {
            if (existing == null || existing.isEmpty()) {
                throw new CaseNotFoundException(id);
            }
            CaseView previous = existing.get(existing.size() - 1);
            CaseView merged = mergeFn.apply(previous);

            List<CaseView> next = new ArrayList<>(existing);
            next.add(merged);
            return List.copyOf(next);
        });
        return updated.get(updated.size() - 1);
    }

    /**
     * Restores a case to exactly the supplied snapshot, discarding local history.
     *
     * <p>Used only by {@code PUT /cases/{id}}. Replacing rather than appending is
     * what makes restore idempotent: running the same backup file through twice
     * leaves identical state, because the second run overwrites with the same
     * content instead of stacking another version on top.
     */
    public CaseView replace(CaseView snapshot) {
        versionsByCase.put(snapshot.caseId(), List.of(snapshot));
        return snapshot;
    }
}
