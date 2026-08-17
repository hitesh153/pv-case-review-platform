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
request with a 404 while looking perfectly alive; a healthcheck that says `up`
through that hides the outage it exists to catch. It stays 200 so Docker will
not restart-loop a service that is merely empty. See the runbook.

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
  expected. It does **not** inherit the previous value's confidence or source —
  attaching old provenance to a new value would misstate where it came from
- `"62"` and `62` are the same clinical value, not a conflict

It rejects, with a `400` naming the exact path:

- a field-level object with no `value` key — ambiguous between a malformed
  envelope and an intended sub-section, and guessing either way loses or
  invents data
- `confidence` outside `[0, 1]`, or non-numeric
- a `null` value — `missing_fields` is how an unreadable field is reported
- a body `case_id` disagreeing with the URL

Every violation in a payload is reported at once rather than one per round trip.
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

## Scope and known limitations

Stated plainly rather than discovered by a reviewer:

- **Storage is in-memory and disappears on restart.** No database, per the
  brief. `ops/backup.sh` is the mitigation, not a substitute.
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
