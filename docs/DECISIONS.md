# Decision log

Running record of choices that were not forced by the brief, and the reasoning
behind each. Written as I go, so the ordering is chronological rather than
tidy.

---

## D1 — Build tool: Maven wrapper, not Gradle

**Decision.** Maven, driven by the committed wrapper (`backend/mvnw`).

**Why.** `mvn` is not installed on this machine and `gradle` 9.6.1 is, which
initially argued for Gradle. Two findings reversed that:

1. `start.spring.io` currently returns HTTP 500 for every Gradle project type
   (`gradle-project`, `gradle-project-kotlin`), across Boot versions. Maven
   project generation returns 200. I did not want to hand-author a
   `build.gradle` plus wrapper as the very first act of the exercise.
2. The Spring Boot Gradle plugin's supported Gradle range is version-specific,
   and Gradle 9 support varies by Boot patch. Generating a wrapper with the
   system Gradle 9.6.1 and hoping the Boot plugin agrees is exactly the kind of
   compatibility guess worth avoiding on a deadline.

The Maven wrapper removes the "no system Maven" problem entirely — it downloads
its own pinned Maven 3.9.16 — and it matches the `mvn spring-boot:run` option
the brief already offers. The wrapper is committed, so the repo builds on a
clean machine with only a JDK present.

---

## D2 — Spring Boot 3.5.16, not 4.1.0

**Decision.** Pin Spring Boot 3.5.16 (latest 3.5.x) rather than the current
default, 4.1.0.

**Why.** I scaffolded on Boot 4.1.0 first and it built green on Java 17. Then I
inspected the dependency tree:

```
tools.jackson.core:jackson-core:3.1.4
tools.jackson.core:jackson-databind:3.1.4
com.fasterxml.jackson.core:jackson-annotations:2.21
```

Boot 4 moves Jackson databind to the `tools.jackson` namespace while annotations
remain under `com.fasterxml.jackson`. The merge engine here works directly with
`JsonNode` (see D3), so this is not an incidental transitive detail — it is the
API I write against all day, in a split namespace.

Boot 3.5.16 gives a single coherent `com.fasterxml.jackson` 2.21.4 surface. Boot
4 buys nothing for this exercise and costs migration surface on a live-demo
deadline. Boot 4 is the right call for a greenfield service with time to
absorb it; it is the wrong call here.

Both versions were verified to build and test green before choosing.

---

## D3 — Follow-up merge: omission never deletes

**Decision.** A field present in the stored version but absent from the
follow-up is preserved and annotated `carried_forward` — a distinct status, not
silently folded into `unchanged`.

**Why.** This is the case the brief leaves to my judgement. A follow-up report
is incremental evidence, not a replacement document. Silence in a later
extraction means "this source added nothing on that point", not "withdraw the
previously known value". Treating omission as deletion would let a safety signal
disappear because a PDF failed to restate it, which is the wrong failure
direction in pharmacovigilance.

`carried_forward` rather than `retained`: "retained" reads as a reviewer
decision, whereas this is merge provenance. The reviewer can see in the response
that the value survived by carry-forward rather than by confirmation, which is
a materially different level of evidence.

Deletion is deliberately not supported. A genuine withdrawal should be an
explicit, audited correction operation, not an inference from absence.

**Status vocabulary.**

| Status            | Meaning                                                      |
| ----------------- | ------------------------------------------------------------ |
| `baseline`        | Initial version; nothing to compare against                  |
| `new`             | Path absent before, explicitly supplied in the follow-up      |
| `unchanged`       | Follow-up explicitly restates the same clinical value         |
| `overridden`      | Follow-up supplies a different value; `previous_value` is set |
| `carried_forward` | Follow-up is silent; prior value and provenance preserved     |

Comparison is on the clinical `value` only. A changed `confidence` or `source`
with an identical value is `unchanged` — a re-scored extraction is not a
clinical conflict for the reviewer to resolve.

---

## D4 — Two endpoints the brief's own requirements force

**Decision.** Add `GET /cases` and `PUT /cases/{caseId}` beyond the five
endpoints listed.

**Why.** Phase 1B requires `ops/backup.sh` to "fetch all known cases from the
running service". With only `GET /cases/{caseId}` that is impossible — you can
only fetch a case whose id you already knew, so "all known cases" would mean a
hardcoded id, and a backup script that silently misses every case created after
it was written is worse than no backup at all. `GET /cases` returns summaries;
the backup script iterates them and fetches each case in full. There is no
pagination — at this scale that would be speculative — so the index itself
grows with case count.

`ops/restore.sh` has the mirror problem. The only write path is
`POST /cases/{id}/follow-ups`, and routing a restore through it is wrong twice
over: it appends a version on every run, so restoring the same file twice
produces different state, and it cannot recreate a case that is absent
entirely — which is precisely the situation a restore exists for.

`PUT` rather than `POST` because the semantics genuinely are "make this case be
exactly this snapshot". Idempotency then falls out of the verb rather than
being bolted on: the second run overwrites with identical content instead of
stacking another version. Verified by diffing the response after two
consecutive restores.

The general point: the operational requirements and the API surface were
specified in separate sections of the brief and do not quite meet. Noticing
that seam is most of the exercise.

---

## D5 — Container base: Temurin `jre-noble`, digest-pinned

**Decision.** `eclipse-temurin:17.0.19_10-jre-noble` pinned by its
multi-architecture index digest. Final image 441 MB.

**Why not alpine, which would be roughly half the size.** I checked rather than
assumed:

```
$ docker buildx imagetools inspect eclipse-temurin:17.0.19_10-jre-alpine
  Platform: linux/amd64
$ docker buildx imagetools inspect eclipse-temurin:17.0.19_10-jre-noble
  Platform: linux/amd64, linux/arm64/v8, linux/arm/v7, ...
$ uname -m
arm64
```

Temurin publishes no arm64 alpine image. On this machine — an Apple Silicon Mac,
which is also where the live demo runs — an alpine base would run under
emulation or not at all. A smaller image that cannot start natively on the
demo machine is not a smaller image, it is an outage. 441 MB is the honest cost
of a JRE on a multi-arch base.

Two further things verified rather than assumed:

- **The digest is the top-level OCI index**, not a platform-specific manifest.
  Pinning the arm64 digest from a local pull would break the build on CI.
- **`curl` already exists at `/usr/bin/curl` in the JRE image.** The usual
  advice is to `apt-get install curl` for the healthcheck; here that would be a
  pointless layer. Checking mattered in the other direction too — a compose
  healthcheck referencing a binary the image lacks fails every probe forever,
  and reports the service as unhealthy when it is fine.

Confirmed on the running container: `uid=10001(app)`, writes to `/app` rejected
by the read-only root filesystem, healthy 6 seconds after start, and the merge
endpoint returning correct annotations through the published port.
