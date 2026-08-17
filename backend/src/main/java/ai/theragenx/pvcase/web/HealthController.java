package ai.theragenx.pvcase.web;

import ai.theragenx.pvcase.store.CaseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness and readiness for {@code GET /health}.
 *
 * <p>A plain controller rather than Spring Boot Actuator. Actuator would pull in
 * a management stack, a second port to reason about and a set of endpoints
 * nobody asked for, to serve one JSON object. The brief also asks for exactly
 * {@code /health}, and remapping Actuator's path to get there is more
 * configuration than writing the endpoint.
 *
 * <p>The distinction that matters operationally: the service reports
 * {@code degraded}, not {@code up}, when it is running but holds no cases. A
 * container that starts cleanly with an unreadable bootstrap file will serve
 * 404s for every case, and a healthcheck that says "up" through that is a
 * healthcheck that hides the outage it exists to catch. It stays HTTP 200 so
 * Docker will not restart-loop a service that is merely empty — the runbook
 * covers what to do about it.
 */
@RestController
public class HealthController {

    private final CaseRepository repository;
    private final String version;
    private final Instant startedAt = Instant.now();

    public HealthController(
            CaseRepository repository,
            @Value("${pvcase.version:dev}") String version) {
        this.repository = repository;
        this.version = version;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        int caseCount = repository.listSummaries().size();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", caseCount > 0 ? "up" : "degraded");
        body.put("version", version);
        body.put("cases_loaded", caseCount);
        body.put("uptime_seconds", Duration.between(startedAt, Instant.now()).toSeconds());
        if (caseCount == 0) {
            body.put("detail",
                    "Service is running but no cases are loaded. The bootstrap file was "
                            + "missing or unreadable; see startup logs and ops/restore.sh.");
        }

        return ResponseEntity.status(HttpStatus.OK).body(body);
    }
}
