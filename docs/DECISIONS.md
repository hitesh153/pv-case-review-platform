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
