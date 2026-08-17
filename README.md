# PV Case Review Platform

A pharmacovigilance (PV) case processing service and reviewer UI.

AI extracts structured fields from adverse-event source documents into an ICSR
(Individual Case Safety Report). Follow-up reports arrive as new information
surfaces, the AI re-extracts, and a human reviewer needs to see **exactly what
changed** before signing off. This repo is the backend that performs that merge,
the tooling to operate it, and the screen the reviewer works in.

## Repository layout

| Path        | What lives there                                                  |
| ----------- | ----------------------------------------------------------------- |
| `backend/`  | Spring Boot service: case storage, follow-up merge, reviewer queries |
| `ops/`      | Lifecycle, backup and restore scripts                             |
| `frontend/` | React reviewer UI                                                 |

## Status

| Phase | Scope                     | State       |
| ----- | ------------------------- | ----------- |
| 1A    | Java backend              | in progress |
| 1B    | Operability tooling       | pending     |
| 2     | React reviewer UI         | pending     |

## Toolchain

Pinned deliberately; see `docs/DECISIONS.md` for why.

- Java 17 (Temurin)
- Spring Boot 3.5.16, built with the committed Maven wrapper (`./mvnw`) — no
  system Maven install required
- Node 20 (frontend)
- Docker with Compose v2

## Quick start

```bash
cd backend && ./mvnw spring-boot:run
```

The service listens on `http://localhost:8080`. Verify it:

```bash
curl -sS http://localhost:8080/health | jq
```

Full API reference, merge semantics and the operations runbook follow as each
phase lands.
