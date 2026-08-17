package ai.theragenx.pvcase.web;

import ai.theragenx.pvcase.domain.CaseView;
import ai.theragenx.pvcase.merge.CasePayloadNormalizer;
import ai.theragenx.pvcase.merge.CaseSnapshotValidator;
import ai.theragenx.pvcase.merge.MergeService;
import ai.theragenx.pvcase.merge.NormalizedCasePayload;
import ai.theragenx.pvcase.store.CaseRepository;
import ai.theragenx.pvcase.web.error.InvalidPayloadException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Case read, follow-up merge, and the two operational endpoints backup and
 * restore need.
 *
 * <p>The follow-up body is taken as a raw {@link JsonNode} rather than a typed
 * DTO on purpose. The follow-up shape is produced by an AI extraction pipeline
 * and is not fully known at build time; binding it to a fixed class would turn
 * every unanticipated key into a framework-level 400 with an unhelpful message.
 * Routing it through {@link CasePayloadNormalizer} instead gives control over
 * exactly what is tolerated and produces errors that name the offending path.
 */
@RestController
@RequestMapping("/cases")
public class CaseController {

    private static final Logger log = LoggerFactory.getLogger(CaseController.class);

    private final CaseRepository repository;
    private final MergeService mergeService;
    private final CaseSnapshotValidator snapshotValidator;

    public CaseController(
            CaseRepository repository,
            MergeService mergeService,
            CaseSnapshotValidator snapshotValidator) {
        this.repository = repository;
        this.mergeService = mergeService;
        this.snapshotValidator = snapshotValidator;
    }

    /** Most recent version of a case. 404 when unknown. */
    @GetMapping("/{caseId}")
    public CaseView getCase(@PathVariable String caseId) {
        return repository.requireLatest(caseId);
    }

    /**
     * Every known case, in summary.
     *
     * <p>Not in the brief's endpoint list, but {@code ops/backup.sh} is required
     * to "fetch all known cases", which is impossible when the only read path
     * needs an id you already have to know. Summaries rather than full cases, so
     * enumerating does not transfer every case body; the backup script iterates
     * them and fetches each one in full. Deliberately unpaginated — that would be
     * speculative at this scale.
     */
    @GetMapping
    public Map<String, Object> listCases() {
        List<CaseView.Summary> cases = repository.listSummaries();
        return Map.of("count", cases.size(), "cases", cases);
    }

    /**
     * Merges a follow-up onto the stored case and returns the annotated result.
     *
     * <p>The merge runs inside the repository's atomic append, so validation
     * failures leave storage untouched and two concurrent follow-ups cannot diff
     * against the same predecessor.
     */
    @PostMapping("/{caseId}/follow-ups")
    public CaseView submitFollowUp(@PathVariable String caseId, @RequestBody JsonNode body) {
        NormalizedCasePayload payload = mergeService.normalizer().normalize(body, false);
        requireMatchingCaseId(caseId, payload.caseId());

        // Fail fast on an unknown case before doing any merge work.
        if (!repository.exists(caseId)) {
            throw new ai.theragenx.pvcase.web.error.CaseNotFoundException(caseId);
        }

        CaseView merged = repository.appendVersion(
                caseId, previous -> mergeService.merge(previous, payload));

        log.info("Merged follow-up onto {} -> v{} ({})",
                caseId, merged.version(), merged.changeSummary());
        return merged;
    }

    /**
     * Replaces a case with a snapshot from a backup file.
     *
     * <p>Also not in the brief. {@code ops/restore.sh} has to put cases back, and
     * routing a restore through the follow-up endpoint would be wrong twice over:
     * it would bump versions on every run instead of being idempotent, and it
     * cannot recreate a case that is missing entirely, which is the situation a
     * restore exists for.
     *
     * <p>PUT rather than POST because the semantics really are "make the case be
     * exactly this". That is what makes running the same backup file through
     * twice a no-op rather than a second stacked version.
     */
    @PutMapping("/{caseId}")
    public ResponseEntity<CaseView> restoreCase(
            @PathVariable String caseId, @RequestBody CaseView snapshot) {

        requireMatchingCaseId(caseId, snapshot == null ? null : snapshot.caseId());
        // Restore bypasses the follow-up normaliser entirely, so this is the only
        // thing standing between a hand-edited backup file and corrupt storage.
        snapshotValidator.validate(snapshot);

        boolean existed = repository.exists(caseId);
        CaseView restored = repository.replace(
                new CaseView(
                        caseId,
                        snapshot.version(),
                        snapshot.comparedToVersion(),
                        snapshot.caseClassification(),
                        snapshot.extractedAt(),
                        snapshot.sourceDocument(),
                        snapshot.missingFields() == null ? List.of() : snapshot.missingFields(),
                        Map.of(),
                        snapshot.sections())
                        .withRecomputedSummary());

        log.info("Restored case {} at v{} ({})",
                caseId, restored.version(), existed ? "replaced" : "created");

        return existed
                ? ResponseEntity.ok(restored)
                : ResponseEntity.status(201).body(restored);
    }

    /**
     * The URL is authoritative. A body id that disagrees is rejected rather than
     * silently preferred either way — restoring or merging into the wrong case is
     * exactly the kind of quiet data corruption that is expensive to find later.
     */
    private void requireMatchingCaseId(String pathCaseId, String bodyCaseId) {
        if (bodyCaseId != null && !bodyCaseId.equals(pathCaseId)) {
            throw new InvalidPayloadException("case_id", String.format(
                    "body declares '%s' but the URL targets '%s'", bodyCaseId, pathCaseId));
        }
    }
}
