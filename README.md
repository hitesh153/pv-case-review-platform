# PV Case Review Platform

A pharmacovigilance (PV) case processing service and reviewer UI.

AI extracts structured fields from adverse-event source documents into an ICSR
(Individual Case Safety Report). Follow-up reports arrive as new information
surfaces, the AI re-extracts, and a human reviewer needs to see **exactly what
changed** before signing off. This repo is the backend that performs that merge,
the tooling to operate it, and the screen the reviewer works in.

## Repository layout

| Path        | What lives there                                                     |
| ----------- | -------------------------------------------------------------------- |
| `backend/`  | Spring Boot service: case storage, follow-up merge, reviewer queries  |
| `ops/`      | Lifecycle, backup and restore scripts                                |
| `frontend/` | React reviewer UI                                                    |
| `fixtures/` | Example follow-up payload (candidate-created, see below)             |
| `docs/`     | Decision log — why things are the way they are                       |

## Quick start

```bash
# Container (preferred — this is what the runbook assumes)
./ops/run.sh build && ./ops/run.sh start

# Or straight from source, no Docker required
cd backend && ./mvnw spring-boot:run
```

Either way the service listens on **`http://localhost:8080`**. Confirm:

```bash
curl -sS http://localhost:8080/health | jq
```

The bootstrap case `PV-2026-0451` is loaded from `case_v1.json` at startup.
Storage is in-memory: **everything is lost on restart**, which is what
`ops/backup.sh` exists to mitigate.

---

# API

Case payloads are `snake_case`, matching the extraction pipeline's own format.
Query payloads are `camelCase`, matching the shape the brief specifies. That
seam is deliberate — neither contract is mine to rename — and both endpoints
accept the other convention as an alias so a client that guesses consistently
is not punished for it.

## The one case contract

`GET /cases/{id}`, `POST /cases/{id}/follow-ups` and `PUT /cases/{id}` all return
the identical shape. The reviewer UI never has to branch on how a case arrived,
and a backup file restores without translation.

Every field carries:

| Attribute              | Meaning                                                     |
| ---------------------- | ----------------------------------------------------------- |
| `field_path`           | `section.field` — the key `/queries` takes, no reassembly    |
| `label`                | Server-derived display name; works for fields never seen before |
| `value`                | The clinical value                                          |
| `confidence`           | Extraction confidence, `null` when the source gave none      |
| `source`               | Page/section reference in the source document                |
| `status`               | How this value got here — see below                          |
| `previous_value`       | **Only present** when `status` is `overridden`               |
| `missing_in_follow_up` | The latest extraction could not read this field              |

### Field statuses

| Status            | Meaning                                                             |
| ----------------- | ------------------------------------------------------------------- |
| `baseline`        | Initial version; nothing to compare against                          |
| `new`             | Path absent before, supplied by the follow-up                        |
| `unchanged`       | Follow-up explicitly restated the same clinical value                |
| `overridden`      | Follow-up supplied a different value; `previous_value` is populated  |
| `carried_forward` | Follow-up was silent; prior value and provenance preserved           |

**`unchanged` and `carried_forward` are not the same thing.** They look
identical in the data and carry very different evidential weight: `unchanged`
means a second source document corroborated the value, `carried_forward` means
only that nothing contradicted it. The reviewer's entire job is deciding what to
trust, so that distinction is surfaced rather than collapsed. The reasoning is
in [`docs/DECISIONS.md`](docs/DECISIONS.md) D3.

---

## `GET /health`

Liveness for the container healthcheck and the runbook.

```bash
curl -sS http://localhost:8080/health | jq
```

```json
{
  "status": "up",
  "version": "0.1.0",
  "cases_loaded": 1,
  "uptime_seconds": 0
}
```

Reports `"degraded"` — still HTTP 200 — when the service is running but holds
zero cases. A service whose bootstrap file failed to load answers every case
request with a 404 while looking perfectly alive.

**HTTP status here is liveness, and the `status` field is readiness.** The
process is genuinely alive and restarting it will not refill it — the fix is a
restore, not a bounce — so a non-2xx would be the wrong signal. The consequence
is that the Compose healthcheck, which only checks HTTP status, reports a
degraded service as healthy. `./ops/run.sh health` is the check that reads the
`status` field and exits non-zero on `degraded`; use that one when you need
readiness. See the runbook.

---

## `GET /cases/{caseId}`

Most recent version of a case. `404` if unknown.

```bash
curl -sS http://localhost:8080/cases/PV-2026-0451 | jq
```

```json
{
  "case_id": "PV-2026-0451",
  "version": 1,
  "compared_to_version": null,
  "case_classification": "non-significant",
  "missing_fields": [],
  "change_summary": {
    "baseline": 14, "new": 0, "unchanged": 0,
    "overridden": 0, "carried_forward": 0
  },
  "sections": {
    "patient": {
      "age": {
        "field_path": "patient.age",
        "label": "Age",
        "value": "62",
        "confidence": 0.91,
        "source": "p.2 §1",
        "status": "baseline",
        "missing_in_follow_up": false
      }
    }
  }
}
```

---

## `POST /cases/{caseId}/follow-ups`

Merges a follow-up onto the stored case and returns the annotated result.

```bash
curl -sS -X POST http://localhost:8080/cases/PV-2026-0451/follow-ups \
  -H 'Content-Type: application/json' \
  -d @fixtures/case_v2_followup_example.json | jq
```

```json
{
  "case_id": "PV-2026-0451",
  "version": 2,
  "compared_to_version": 1,
  "case_classification": "significant",
  "missing_fields": ["patient.weight_kg"],
  "change_summary": {
    "baseline": 0, "new": 4, "unchanged": 2,
    "overridden": 4, "carried_forward": 8
  }
}
```

A conflicting field, showing both values for the reviewer:

```json
{
  "field_path": "adverse_event.seriousness",
  "label": "Seriousness",
  "value": "Serious",
  "confidence": 0.92,
  "source": "p.3 §1",
  "status": "overridden",
  "previous_value": "Non-serious",
  "missing_in_follow_up": false
}
```

A field the follow-up could not read — value preserved, but flagged as
unconfirmed by the newest document:

```json
{
  "field_path": "patient.weight_kg",
  "label": "Weight (kg)",
  "value": "78",
  "confidence": 0.85,
  "source": "p.3 §2",
  "status": "carried_forward",
  "missing_in_follow_up": true
}
```

### What the merge accepts

The follow-up shape is produced by an extraction pipeline and is not fully known
at build time, so the parsing boundary is tolerant in bounded, documented ways —
all of it in one class, `CasePayloadNormalizer`:

- `case_id` / `caseId`, `missing_fields` / `missingFields`, and the other
  top-level keys in either convention
- sections and fields that have never been seen before
- a bare scalar (`"age": 63`) where a `{value, confidence, source}` envelope was
  expected. Confidence and source are then `null`, and are **never** borrowed
  from the previous version — including when the value is unchanged. Provenance
  travels with the extraction that reported it; taking a new confidence but
  keeping the old page reference would present a `(confidence, source)` pair
  that no single extraction ever produced
- `"62"` and `62` are the same clinical value, not a conflict

It rejects, with a `400` naming the exact path:

- a field-level object with no `value` key — ambiguous between a malformed
  envelope and an intended sub-section, and guessing either way loses or
  invents data
- `confidence` outside `[0, 1]`, or non-numeric
- a `null` value — `missing_fields` is how an unreadable field is reported
- a body `case_id` disagreeing with the URL

Every violation in a payload is reported at once rather than one per round trip,
capped at 25 so a wildly wrong payload cannot produce an enormous error body.
**Validation completes before storage is touched**, so a rejected follow-up
never leaves a partially merged case behind.

---

## `POST /queries`

Raises a reviewer query against one field. `201` on success.

```bash
curl -sS -X POST http://localhost:8080/queries \
  -H 'Content-Type: application/json' \
  -d '{
        "caseId": "PV-2026-0451",
        "fieldPath": "adverse_event.seriousness",
        "question": "Follow-up upgrades seriousness to Serious but the narrative on p.3 still reads as non-serious. Please confirm before submission."
      }' | jq
```

```json
{
  "id": "e53412df-f1a2-4e09-af82-c96b84bbd8c8",
  "caseId": "PV-2026-0451",
  "fieldPath": "adverse_event.seriousness",
  "question": "Follow-up upgrades seriousness to Serious but the narrative on p.3 still reads as non-serious. Please confirm before submission.",
  "createdAt": "2026-08-17T10:21:01Z"
}
```

`fieldPath` is validated against the live case: `404` if the case is unknown,
`400` if the field is not one of its fields. A query is a task a human has to
action later, so one pointing at a field that does not exist is worse than
useless — it is discovered long after the reviewer moved on.

---

## `GET /queries?caseId={id}`

```bash
curl -sS "http://localhost:8080/queries?caseId=PV-2026-0451" | jq
```

```json
[
  {
    "id": "e53412df-f1a2-4e09-af82-c96b84bbd8c8",
    "caseId": "PV-2026-0451",
    "fieldPath": "adverse_event.seriousness",
    "question": "Follow-up upgrades seriousness to Serious but the narrative on p.3 still reads as non-serious. Please confirm before submission.",
    "createdAt": "2026-08-17T10:21:01Z"
  }
]
```

A case with no queries returns `[]`, not a `404` — the reviewer screen loads
queries alongside the case, and having none is the normal state on first open.
`caseId` is required; an unscoped dump of every query in the system is not
something any caller wants, and it should not be the accidental result of a
forgotten parameter.

---

## Operational endpoints

Not in the brief's endpoint list, but Phase 1B's requirements cannot be met
without them. Reasoning in [`docs/DECISIONS.md`](docs/DECISIONS.md) D4.

### `GET /cases`

Every known case, in summary. `ops/backup.sh` needs to enumerate cases without
already knowing their ids.

```bash
curl -sS http://localhost:8080/cases | jq
```

```json
{
  "count": 1,
  "cases": [
    {
      "case_id": "PV-2026-0451",
      "version": 2,
      "case_classification": "significant",
      "extracted_at": "2026-05-02T11:20:00Z"
    }
  ]
}
```

### `PUT /cases/{caseId}`

Restores a case to exactly the supplied snapshot. Body is the full case object
as returned by `GET /cases/{id}`. `201` if created, `200` if replaced.

Idempotent by construction: running the same backup file through twice leaves
byte-identical state and does not advance the version.

---

## Errors

One envelope for every failure, so the UI writes one error renderer.

```json
{
  "code": "INVALID_PAYLOAD",
  "message": "Case payload is invalid",
  "errors": [
    { "path": "patient.age.confidence", "message": "must be between 0 and 1 inclusive" }
  ],
  "timestamp": "2026-08-17T10:12:46Z"
}
```

| Code                | Status | When                                              |
| ------------------- | ------ | ------------------------------------------------- |
| `CASE_NOT_FOUND`    | 404    | No case with that id                              |
| `INVALID_PAYLOAD`   | 400    | Semantically invalid; `errors` locates each fault  |
| `MALFORMED_JSON`    | 400    | Body is not parseable JSON                        |
| `MISSING_PARAMETER` | 400    | Required query parameter absent                   |
| `NOT_FOUND`         | 404    | No such endpoint                                  |
| `INTERNAL_ERROR`    | 500    | Unexpected; stack trace goes to the logs, not here |

---

# Development

```bash
cd backend
./mvnw -B test          # 44 tests
./mvnw spring-boot:run  # localhost:8080
```

No system Maven install is needed — the wrapper downloads its own pinned Maven.
Only a JDK 17+ is required.

### Configuration

| Property / env var             | Default                    | Purpose                      |
| ------------------------------ | -------------------------- | ---------------------------- |
| `SERVER_PORT`                  | `8080`                     | Listen port                  |
| `pvcase.bootstrap.location`    | `classpath:case_v1.json`   | Seed file                    |
| `pvcase.bootstrap.case-id`     | `PV-2026-0451`             | Id the seed loads under      |
| `pvcase.cors.allowed-origins`  | localhost `5173`, `3000`   | Reviewer UI dev origins      |

### Test layout

- `MergeServiceTest` — 24 tests on merge semantics and payload rejection, run
  through the real normaliser so the parsing boundary is exercised too
- `CaseApiTest` — 19 tests over HTTP: status codes, CORS preflight, restore
  idempotency, and that a rejected request leaves storage untouched

---

# Operations

The runbook. Written for someone who did not build this and is reading it at
2am. Every command below has been run against the real service; nothing here is
described from memory.

**The one thing to know first:** storage is in-memory. A restart loses every
case and every query. `ops/backup.sh` is the only thing standing between a
container restart and data loss, and `ops/restore.sh` is how you get it back.

## Everything at a glance

```bash
./ops/run.sh --help          # works with Docker absent or stopped
make                         # same commands via make

./ops/run.sh build           # build the image
./ops/run.sh start           # up, then block until /health says "up"
./ops/run.sh health          # one-shot probe, exit 0 only when "up"
./ops/run.sh logs --tail 200
./ops/run.sh stop            # safe to run when already stopped
./ops/run.sh clean           # this project only; never touches backups/
./ops/run.sh test            # backend suite; needs a JDK, not Docker

./ops/backup.sh                                  # -> prints the backup path
./ops/restore.sh --dry-run backups/cases-….json  # writes nothing
./ops/restore.sh backups/cases-….json
```

Exit codes are uniform: **0** success (including `--help`), **1** operational
failure, **2** usage error.

---

## Build and deploy

```bash
./ops/run.sh build
./ops/run.sh start
```

`start` does not return until `GET /health` reports `status: "up"` — not merely
until the container exists. On a healthy machine this takes a few seconds:

```
INFO run.sh: waiting up to 120s for http://localhost:8080/health to report status="up"
INFO run.sh: service is healthy after 2s (2 probe(s))
INFO run.sh: started; API is available at http://localhost:8080
```

If it times out it prints the last 100 log lines and exits 1. It does not
silently succeed.

**Different port:** compose publishes `${APP_PORT}`, so both must move together.

```bash
APP_PORT=9090 ./ops/run.sh start --base-url http://localhost:9090
```

Setting only one is the most common false "start failed", so `start` warns when
`APP_PORT` and `--base-url` disagree rather than letting you wait out the
timeout.

---

## Verify it is healthy

```bash
curl -sS localhost:8080/health | jq
```

Three outcomes, and the middle one is the one that catches people:

| Response | Meaning | Do |
| --- | --- | --- |
| `"status": "up"` | Healthy, cases loaded | Nothing |
| `"status": "degraded"` | **Running but holds zero cases** | See below — this is an outage that looks fine |
| No response | Not listening | Container down or wrong port |

`degraded` is still HTTP 200 on purpose: the process is alive, and restarting it
will not refill it — the fix is a restore, not a bounce.

**Know this trap:** the Compose healthcheck only checks HTTP status, so Docker
reports a degraded service as `healthy`. `./ops/run.sh health` reads the
`status` field and exits non-zero, which is why `start` uses it rather than
trusting the container's own health state.

```json
{
  "status": "degraded",
  "cases_loaded": 0,
  "detail": "Service is running but no cases are loaded. The bootstrap file was missing or unreadable; see startup logs and ops/restore.sh."
}
```

A deeper check that exercises the real data path:

```bash
curl -sS localhost:8080/cases | jq '.count'
```

---

## Back up

```bash
./ops/backup.sh
```

Writes `backups/cases-<UTC timestamp>-<pid>.json`, mode `0600` — **it contains
patient case data**. Diagnostics go to stderr; the finished path is the only
thing on stdout, so it composes:

```bash
path="$(./ops/backup.sh)" && gpg --encrypt -r ops@example.com "$path"
```

It assembles into a temp directory inside `backups/` and publishes with a single
rename, so an interrupted run leaves nothing behind — you will never find a
half-written file that parses as valid JSON. It exits non-zero and writes
nothing if any single case fails to fetch, rather than producing a quietly
incomplete backup.

**Cron** (hourly, mail only on failure):

```cron
17 * * * * /path/to/repo/ops/backup.sh >/dev/null
```

No prompts, no TTY assumptions. Backups are gitignored.

---

## Restore

**Always dry-run first.** It performs zero writes.

```bash
./ops/restore.sh --dry-run backups/cases-20260817T103333Z-47449.json
```

```
INFO restore.sh: backup validated: 1 case(s), created_at=…, source=…
INFO restore.sh: PV-2026-0451: would REPLACE (case exists)
INFO restore.sh: dry-run summary (no writes performed): would-create=0 would-replace=1 unprobeable=0 of 1
```

Then for real:

```bash
./ops/restore.sh backups/cases-20260817T103333Z-47449.json
```

**Safe to re-run.** Restore is a `PUT` — it replaces rather than appends — so
running the same file twice leaves byte-identical state and does not advance the
version. If you are unsure whether a restore completed, run it again.

The whole file is validated before the first write. A corrupt backup fails with
the service untouched.

---

## Debugging a failed startup

```bash
./ops/run.sh logs --tail 100
```

Work down this list; it is ordered by how often each one is the answer.

**1. Port already taken.** The usual cause.

```bash
lsof -i :8080
```

Something else bound to 8080 — very often a `./mvnw spring-boot:run` from
earlier in the day. Kill it, or move the service with `APP_PORT` **and**
`--base-url` together.

**2. Service is up but has no cases** (`status: "degraded"`). The bootstrap file
could not be read. Grep for it:

```bash
./ops/run.sh logs --no-follow --tail 200 2>&1 | grep -i 'bootstrap file'
```

```
ERROR a.t.p.bootstrap.CaseBootstrapLoader : Bootstrap file classpath:does-not-exist.json
not found. Service is up but has no cases; GET /cases will return an empty list.
```

Restarting will not help. Either fix `pvcase.bootstrap.location` and rebuild, or
restore from a backup — which is faster and is usually what you want.

**3. Container exits immediately.**

```bash
docker compose -p theragenx ps -a
docker compose -p theragenx logs --no-color --tail 200 backend
```

A JVM that dies in under a second is almost always a bad `SERVER_PORT` or a
malformed environment override.

**4. Docker itself.** `run.sh` distinguishes these and tells you which:

- `docker` missing from `PATH`
- Compose v2 absent (the legacy `docker-compose` binary is not supported)
- daemon unreachable — start Docker Desktop, or check socket permissions

`--help` and `test` deliberately work regardless, so you can still run the test
suite on a machine with no Docker at all.

**5. Rule out the container entirely.** If it runs on the host but not in
Docker, the problem is packaging, not code:

```bash
cd backend && ./mvnw spring-boot:run
```

---

## Requests are failing — what to check first

**Check in this order.** Steps 1–2 catch the large majority.

**1. Is it actually up?**

```bash
./ops/run.sh health
```

Exits non-zero and states the reason: no response, wrong HTTP status, or
`degraded`.

**2. Every case request is a 404.** Look at `cases_loaded` in `/health`. If it
is `0`, this is the `degraded` case above — the service is fine, the data is
gone. Restore.

**3. The browser says the backend is down but curl works.** This is CORS, not an
outage. The reviewer UI's origin must be listed:

```bash
curl -sS -o /dev/null -D - -X OPTIONS localhost:8080/cases/PV-2026-0451 \
  -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: GET' \
  | grep -i access-control
```

No `Access-Control-Allow-Origin` in the response means the origin is not
allowed — commonly because Vite fell back to a different port when 5173 was
busy. Add it to `PVCASE_CORS_ALLOWED_ORIGINS` in `docker-compose.yml` and
restart. **Do not set it to `*`.**

**4. `400` on a follow-up.** The response names the exact path:

```json
{"code":"INVALID_PAYLOAD","errors":[{"path":"patient.age.confidence","message":"must be between 0 and 1 inclusive"}]}
```

Validation runs before storage is touched, so a rejected follow-up has not
half-merged anything. The case is exactly as it was.

**5. `409`/`500`, or anything unexplained.** The full stack trace is in the
service log, never in the response:

```bash
./ops/run.sh logs --no-follow --tail 200 2>&1 | grep -A 30 'Unhandled exception'
```

**6. Merge produced something surprising.** Check `change_summary` and
`compared_to_version` on the response. Remember `carried_forward` means the
follow-up never mentioned that field — that is by design, not a bug. See
`docs/DECISIONS.md` D3.

---

## Things that will surprise you

- **`clean` removes the image**, so the next `start` needs a `build` first. It
  is scoped to the `theragenx` compose project and never touches `backups/` or
  any other project on the machine.
- **The container filesystem is read-only** apart from a tmpfs `/tmp`. Anything
  that tries to write to disk will fail — which is intended, since the service
  has no reason to.
- **Version numbers only go up**, except through a restore, which sets them to
  whatever the backup held.
- **`missing_fields` is per-version**, not cumulative. A field missing in v2 and
  successfully read in v3 will not appear in v3's list.
- **`backups/` is gitignored** apart from `.gitkeep`. Backups contain patient
  data and must not be committed.

---

## Scope and known limitations

Stated plainly rather than discovered by a reviewer:

- **Storage is in-memory and disappears on restart.** No database, per the
  brief. `ops/backup.sh` is the mitigation, not a substitute.
- **Backups cover cases, not reviewer queries.** A restore brings the case data
  back; any queries raised against it are gone. Queries would need their own
  endpoint and their own place in the backup envelope, which is a schema change
  I did not make inside the time budget.
- **A restore replaces a case and discards its version history.** That is right
  when recovering into an empty process, which is what `restore.sh` does. It
  would be the wrong semantics for a general-purpose write endpoint, and
  `PUT /cases/{id}` is documented as backup and restore only for that reason.
- **Storage objects are shallowly immutable.** Stored `CaseView` instances hand
  out their own maps and `JsonNode` values rather than defensive copies. No code
  path mutates them and an HTTP client cannot reach them, so this is an
  encapsulation weakness rather than a live defect — but it is the first thing I
  would harden if this grew more writers.
- **A retried follow-up reads as corroboration.** POST the same payload twice
  and the second pass marks fields `unchanged`, which the UI presents as "a
  second document confirmed this". Distinguishing a network retry from genuine
  new evidence needs report identity or an idempotency key on the follow-up,
  which the payload format does not currently carry.
- **Version history is kept internally but not exposed.** A diff needs a
  well-defined predecessor, and immutable snapshots stop v1 being mutated while
  v2 is built. There is no history endpoint — the brief asks for the most recent
  version, and browsing history is a product decision nobody has made yet.
- **No authentication**, per the brief's non-goals. `PUT /cases/{id}` in
  particular would need it before going anywhere near a real deployment.
- **This is not a regulatory audit trail.** It records what changed between
  extractions, not who approved what and when.
- **`fixtures/case_v2_followup_example.json` is candidate-created.** The
  official `case_v2_followup_payload.json` is supplied at the start of the live
  session and has not been seen. Mine exercises all five statuses plus a
  previously unseen section in one payload, and the merge is deliberately
  tolerant of shape variation because of that uncertainty.
