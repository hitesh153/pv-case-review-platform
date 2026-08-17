# Walkthrough — how to explain this build

Written for the person presenting this work. Every claim below is something the
repo can prove; where there is a proof, the command that produces it is given so
you can run it live rather than assert it.

---

## 1. The sixty-second version

> "It's a Spring Boot service that merges follow-up ICSR extractions onto a
> stored case and tells the reviewer *why* every field holds the value it holds
> — not just what changed, but whether a new document confirmed it or whether it
> merely survived. Around that there's a digest-pinned container, three
> defensive ops scripts, and a runbook. The reviewer UI is built on top of it
> live."

If they ask what you're proudest of, the honest answer is the
`unchanged` / `carried_forward` distinction, because it's the one thing in here
that came from thinking about the reviewer's job rather than from the spec.

---

## 2. The shape of it

```
                    case_v1.json
                         │
                         ▼
                CaseBootstrapLoader        ← seeds v1 at startup
                         │
   follow-up JSON        ▼
        │         ┌─────────────┐
        └────────▶│ CasePayload │  the ONLY place that tolerates
                  │ Normalizer  │  input variation
                  └──────┬──────┘
                         │  NormalizedCasePayload (one predictable shape)
                         ▼
                  ┌─────────────┐
                  │MergeService │  pure; no storage, no Spring plumbing
                  └──────┬──────┘
                         │  CaseView (annotated)
                         ▼
                  ┌─────────────┐
                  │CaseRepository│ versioned, atomic append
                  └──────┬──────┘
                         │
        ┌────────────────┼────────────────┐
        ▼                ▼                ▼
    GET /cases/{id}  POST follow-ups   PUT /cases/{id}
                  ── all return CaseView ──
```

The single most important structural claim: **one wire contract**. Read, merge
and restore all speak `CaseView`. Say it like this —

> "The UI never branches on how a case arrived, and a backup file restores
> without translation. That falls out of using one shape everywhere."

---

## 3. The five decisions you will be asked to defend

### 3.1 "Field in stored version but not in the follow-up — your call. Why?"

This is the question the brief explicitly plants. **Answer:**

> "Preserved, and annotated `carried_forward` — a distinct status, not folded
> into `unchanged`. A follow-up is incremental evidence, not a replacement
> document, so silence means 'this source added nothing on that point', not
> 'withdraw the value'. Treating omission as deletion would let a safety signal
> disappear because a PDF failed to restate it, and in pharmacovigilance that's
> the wrong direction to fail in."

Then the part that separates it from a spec-follower:

> "I gave it its own status rather than reusing `unchanged` because they look
> identical in the data and carry completely different evidential weight.
> `unchanged` means a second source document corroborated the value.
> `carried_forward` means only that nothing has contradicted it. The reviewer's
> whole job is deciding what to trust — collapsing those two would hide exactly
> the thing they need."

**Proof:** `MergeServiceTest.omittedFieldIsCarriedForward` asserts the total
field count is unchanged, i.e. nothing was dropped.

If pushed on deletion: *"Deliberately unsupported. A real withdrawal should be
an explicit audited correction, not an inference from absence."*

### 3.2 "Why Maven and not Gradle?"

> "I started toward Gradle — it's what's installed on this machine. Two things
> changed it. `start.spring.io` is currently returning HTTP 500 for every Gradle
> project type while Maven returns 200, and the Boot Gradle plugin's supported
> Gradle range is version-specific, so generating a wrapper with the system
> Gradle 9.6.1 and hoping the Boot plugin agrees is a compatibility guess I
> didn't want to make on a deadline. The Maven wrapper also removes the problem
> that started it — it downloads its own pinned Maven, so `mvn` doesn't need to
> be installed at all."

**Proof:** clone fresh, `cd backend && ./mvnw test` — works with only a JDK.

### 3.3 "Why Spring Boot 3.5.16 when 4.1 is current?"

Do **not** say "3 is safer". Say what you actually checked:

> "I scaffolded on 4.1.0 first and it built green. Then I looked at the
> dependency tree: Boot 4 moves Jackson databind to the `tools.jackson`
> namespace while annotations stay on `com.fasterxml.jackson`. My merge engine
> works directly against `JsonNode`, so that split isn't an incidental
> transitive detail, it's the API I write against all day. Boot 4 buys nothing
> for this exercise and costs migration surface. I'd choose 4 for a greenfield
> service with time to absorb it."

**Proof:** `./mvnw dependency:tree | grep jackson` — and `docs/DECISIONS.md` D2
records that both were built before choosing.

### 3.4 "You added endpoints that weren't in the brief."

This is a strength — lead with the reasoning, not an apology:

> "Two, and both are forced by your own Phase 1B requirements rather than by me
> wanting more API. `backup.sh` has to 'fetch all known cases', which is
> impossible when the only read path needs an id you already know — so
> `GET /cases`. And `restore.sh` has to put cases back; routing that through
> `/follow-ups` is wrong twice over, because it appends a version on every run
> so restoring the same file twice gives different state, and it can't recreate
> a case that's missing entirely, which is the situation a restore exists for.
> So `PUT /cases/{id}` — PUT because the semantics genuinely are 'make this case
> be exactly this', and idempotency then falls out of the verb instead of being
> bolted on."

The meta-point worth stating out loud:

> "The API surface and the ops requirements were specified in separate sections
> of the brief and don't quite meet. Noticing that seam felt like most of the
> exercise."

**Proof:** run `./ops/restore.sh` twice, diff the case — identical.

### 3.5 "Why is the image 441MB? Alpine would be half that."

> "I checked alpine. Temurin publishes no arm64 manifest for it — it's
> amd64-only — and this machine, which is where the demo runs, is arm64. A
> smaller image that needs emulation to start isn't smaller, it's an outage. So
> noble, and 441MB is the honest cost of a JRE on a multi-arch base."

**Proof, and it lands well because it's one command:**

```bash
docker buildx imagetools inspect eclipse-temurin:17.0.19_10-jre-alpine | grep Platform
```

Two more you can offer unprompted if Docker comes up:

- *"The digests I pinned are the top-level OCI index, not the arm64 manifest a
  local pull hands you — that would have broken the build on CI."*
- *"I checked whether `curl` was in the JRE image before writing the compose
  healthcheck. It is, so there's no `apt-get` layer. It matters in the other
  direction too: a healthcheck naming a binary the image lacks fails every probe
  forever and reports a healthy service as down."*

---

## 4. Code tour — what to open, in what order

If asked to walk the code, go in this order. It follows the data.

| # | File | The one thing to say |
| - | ---- | -------------------- |
| 1 | `domain/FieldStatus.java` | "Five statuses. The interesting pair is `unchanged` vs `carried_forward`." |
| 2 | `domain/CaseView.java` | "One wire contract for read, merge and restore. Sections stay nested because grouping is a primary UI concept, but every field carries its own `field_path` so the client never reassembles a key." |
| 3 | `merge/CasePayloadNormalizer.java` | "The only place that tolerates input variation. When the real v2 turns out to differ, this is the single file that changes." |
| 4 | `merge/MergeService.java` | "Pure — no storage, no Spring beyond `@Service`. That's why its edge cases test as plain functions." |
| 5 | `store/CaseRepository.java` | "Read-merge-append inside `ConcurrentHashMap.compute`, so validation failures leave storage untouched and concurrent follow-ups can't diff against the same predecessor." |
| 6 | `web/error/RestExceptionHandler.java` | "One envelope. Validation errors name the exact path; unexpected errors return a generic message and log the trace, because leaking an exception string to a browser helps an attacker, not the reviewer." |

### Three details worth volunteering

**Confidence and source are nullable and serialised even when null.** A
follow-up can send a bare scalar with no provenance. The merge does *not* carry
the old confidence onto the new value —

> "Attaching the previous extraction's provenance to a different value would be
> a quiet lie about where that value came from."

**`"62"` and `62` are one clinical fact.** Scalars compare on trimmed text.

> "An extraction pipeline changing its mind about JSON typing hasn't discovered
> anything new about the patient. Surfacing that as a conflict spends reviewer
> attention, which is the scarce resource this whole screen exists to protect."

**`missing_fields` annotates, it never erases.** And it doesn't accumulate
across versions —

> "It describes the latest extraction. Unioning it forward would leave stale
> warnings on fields a later document read perfectly well."

---

## 5. Questions you should expect

**"Why no database?"** — Non-goal in the brief. In-memory, versioned. It's
stated as a limitation in the README rather than left to be discovered, and
`backup.sh` is the mitigation.

**"Is this an audit trail?"** — No, and the README says so. It records what
changed between extractions, not who approved what and when. That's a different
feature with regulatory requirements attached.

**"You keep version history but don't expose it."** — "A diff needs a
well-defined predecessor, and immutable snapshots stop v1 being mutated while v2
is built — about fifteen lines. I stopped short of a history endpoint because
the brief asks for the most recent version and browsing history is a product
decision nobody has made."

**"What would you do next?"** — In order: authentication, because `PUT /cases`
is unauthenticated and that's the one thing here that would be dangerous in a
real deployment; then persistence; then query resolution workflow, since queries
currently have no answered state.

**"Where did you use Claude Code?"** — Answer honestly and specifically. It
scaffolded and drafted; the decisions above were the parts worth arguing about,
and several came from checking rather than accepting a first answer — Boot 4's
Jackson split, alpine's missing arm64 manifest, and `curl`'s presence in the JRE
image all changed the design after verification. The ops scripts were drafted
then reviewed line by line against shellcheck and actually executed. That is a
better answer than either "I wrote it all" or "the AI did it".

**"What went wrong?"** — Have a real answer ready. Initializr's Gradle generator
returning 500 forced the build-tool decision; `4.1.0.RELEASE` from the
Initializr metadata isn't a real Maven coordinate (`4.1.0` is); a lambda
captured a reassigned local and didn't compile. Small, honest, and they show you
were actually driving.

---

## 6. Things to say that are true and easy to forget under pressure

- Validation completes **before** storage is touched — there's a test that
  proves a rejected follow-up doesn't advance the version.
- `previous_value` is **absent**, not null, when there's no conflict, so the UI
  branches on presence.
- `/health` reports `degraded` when the service is up with zero cases, because a
  healthcheck that says `up` through an unreadable bootstrap file hides the
  outage it exists to catch.
- CORS origins are **enumerated, not wildcarded** — `"*"` is the line someone
  copies into a deployment later.
- The example follow-up fixture is **mine, and labelled as mine**. The official
  one arrives at the call.
- 44 backend tests. `./mvnw test` takes about ten seconds; run it live if asked.
