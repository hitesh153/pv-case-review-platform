package ai.theragenx.pvcase.bootstrap;

import ai.theragenx.pvcase.domain.CaseView;
import ai.theragenx.pvcase.merge.MergeService;
import ai.theragenx.pvcase.merge.NormalizedCasePayload;
import ai.theragenx.pvcase.store.CaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the bootstrap case into storage at startup.
 *
 * <p>Runs on {@link ApplicationReadyEvent} rather than in a constructor so that a
 * bad seed file produces a clear, logged failure after the context is otherwise
 * up, instead of an opaque bean-creation stack trace. That matters for the
 * runbook: "service started but has no cases" is a far easier thing to diagnose
 * at 2am than "context failed to initialise".
 *
 * <p>The seed path is configurable so the container image can be started against
 * a different bootstrap file without a rebuild.
 */
@Component
public class CaseBootstrapLoader {

    private static final Logger log = LoggerFactory.getLogger(CaseBootstrapLoader.class);

    private final CaseRepository repository;
    private final MergeService mergeService;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final String seedLocation;
    private final String seedCaseId;

    public CaseBootstrapLoader(
            CaseRepository repository,
            MergeService mergeService,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${pvcase.bootstrap.location:classpath:case_v1.json}") String seedLocation,
            @Value("${pvcase.bootstrap.case-id:PV-2026-0451}") String seedCaseId) {
        this.repository = repository;
        this.mergeService = mergeService;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.seedLocation = seedLocation;
        this.seedCaseId = seedCaseId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadBootstrapCase() {
        if (repository.exists(seedCaseId)) {
            log.info("Bootstrap case {} already present; skipping seed", seedCaseId);
            return;
        }

        Resource resource = resourceLoader.getResource(seedLocation);
        if (!resource.exists()) {
            log.error("Bootstrap file {} not found. Service is up but has no cases; "
                    + "GET /cases will return an empty list.", seedLocation);
            return;
        }

        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            NormalizedCasePayload payload = mergeService.normalizer().normalize(root, true);

            // The configured id wins over the file's, so a mislabelled seed cannot
            // quietly create a case nobody is looking for.
            String caseId = payload.caseId() != null ? payload.caseId() : seedCaseId;
            if (!caseId.equals(seedCaseId)) {
                log.warn("Bootstrap file declares case_id '{}' but configured id is '{}'; "
                        + "using the configured id", caseId, seedCaseId);
                caseId = seedCaseId;
            }

            CaseView baseline = mergeService.buildBaseline(caseId, payload);
            repository.seed(baseline);

            log.info("Seeded case {} v{} from {} ({} sections, {} fields)",
                    baseline.caseId(),
                    baseline.version(),
                    seedLocation,
                    baseline.sections().size(),
                    baseline.sections().values().stream().mapToInt(java.util.Map::size).sum());

        } catch (IOException e) {
            log.error("Failed to read bootstrap file {}: {}", seedLocation, e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Bootstrap file {} is not a valid case payload: {}",
                    seedLocation, e.getMessage(), e);
        }
    }
}
