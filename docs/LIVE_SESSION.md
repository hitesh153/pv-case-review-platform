# Live session run sheet

45 minutes to build, 10 to walk through. This is the plan and the recovery
paths. Read it once before the call; glance at §2 during.

---

## 1. Before the call (T-30 minutes)

Run this whole block and check every line. It ends with the service healthy on
8080 and a backup on disk.

```bash
cd ~/Desktop/Projects/Experiments/Trials/TheraGenx/pv-case-review-platform

git status                       # must be clean
git log --oneline | head -5      # Phase 1A + 1B pushed

./ops/run.sh start               # waits for the healthcheck, not just the container
curl -sS localhost:8080/health | jq      # expect "status":"up", cases_loaded 1

./ops/backup.sh                  # ← DO NOT SKIP. This is the recovery net for §4.
```

Then:

- [ ] **Fresh Claude Code session, `cwd` = repo root.** The brief requires
      committing the session log, and it must be the session where the frontend
      was built. Starting it elsewhere puts the log under a different encoded
      project path.
- [ ] Terminal, editor, browser and DevTools laid out for screen share.
- [ ] `cd frontend && npm run dev` verified once, then stopped.
- [ ] Nothing else on ports 8080 or 5173: `lsof -i :8080 -i :5173`

**The `frontend/` directory must still be the stock Vite template.** That is the
honest starting point and it is visible in the commit history.

---

## 2. The 45 minutes

Commit at the end of every block — the brief asks for progression, and these are
natural seams rather than artificial ones.

| Time | Do | Commit |
| --- | --- | --- |
| **0–5** | Open the client's `case_v2_followup_payload.json` on screen and read it aloud. `POST` it to `/cases/PV-2026-0451/follow-ups`. `GET` the case and show the conflicts in the terminal. | — |
| **5–14** | Types from the real response. Fetch on load. Loading and error states. Render sections grouped, fields listed. | `feat(frontend): fetch and render grouped case fields` |
| **14–24** | Confidence colour bands. Conflict view: new value primary, previous alongside. Status pills. | `feat(frontend): add confidence and conflict treatments` |
| **24–33** | Classification selector, sort by confidence ascending, filter to conflicts only, `missing_fields` banner. | `feat(frontend): add review controls and missing-field alert` |
| **33–42** | Raise Query modal on conflicting fields → `POST /queries`. Focus trap, Escape to close, focus returns to the trigger. | `feat(frontend): submit reviewer queries from a dialog` |
| **42–45** | Keyboard pass, `npm run build`, fix whatever is red. | `fix(frontend): …` as needed |

**Ordering rationale, if asked:** must-haves before bonuses, and the query modal
late because it is the only piece that writes — if time runs out, everything
before it still demonstrates a working reviewer screen.

### Brand colours

```
navy   #0C1A36   text, headers
blue   #0077B6   primary actions, the new value in a conflict
teal   #00C2E0   accents, focus rings
```

Confidence bands come from the brief: low `< 0.80`, medium `0.80–0.90`, high
`> 0.90`. Do not use red/amber/green alone — pair colour with the numeral, which
is both an accessibility point and one worth saying out loud.

### The API makes this easy on purpose

Say this while writing the render loop — it is the "full-stack coherence" point:

- `field.field_path` is already on every field. Sorting and filtering are
  one-liners; raising a query passes it straight through.
- `previous_value` is **absent** unless the field is `overridden`, so the
  conflict view branches on presence.
- `change_summary` gives conflict counts without walking the tree.
- `label` is server-derived, so a field nobody has seen before still renders.

```ts
const visible = Object.entries(fields)
  .filter(([, f]) => !conflictsOnly || f.status === "overridden")
  .sort(([, a], [, b]) => (a.confidence ?? 2) - (b.confidence ?? 2));
```

---

## 3. Narrate these three things

Communication is on the rubric. If nothing else lands:

1. **Why the conflict view looks the way it does** — the new value is primary
   because the reviewer's default action is to accept the newer extraction; the
   previous value is present because they cannot judge it without seeing what it
   replaced.
2. **`carried_forward` vs `unchanged`** — if the UI shows status pills, this is
   the natural moment. See `WALKTHROUGH.md` §3.1.
3. **What you are skipping and why** — naming a cut deliberately reads as
   judgement; silently omitting it reads as running out of time.

---

## 4. If something breaks

**Backend won't start.**
`./ops/run.sh logs --tail 50`. Runbook in README → Operations.

**Backend dies mid-session.** State is in-memory, so it comes back as v1 with no
conflicts — and the whole screen is about conflicts. Recover:

```bash
./ops/run.sh start
./ops/restore.sh backups/<the file from §1>.json   # back to the pre-call state
# then re-POST the client's v2 if the backup predates it
```

**Total backend failure.** The brief permits importing JSON directly. Use the
backup file from §1 — it is already in the exact `GET /cases/{id}` shape, so no
adapter is needed:

```ts
// FALLBACK: backend unavailable, see known issues
import fixture from "../../backups/<file>.json";
```

Say it out loud, put it in the commit message, and disable the Raise Query
button rather than pretending the POST succeeded. **Do not wire this up in
advance** — it only exists if it is needed.

**CORS error in the console.** Should not happen; preflight from `:5173` was
verified before the call. If Vite picked a different port because 5173 was busy,
that is the cause — free the port, or add the origin to
`pvcase.cors.allowed-origins` and restart.

---

## 5. After the call

```bash
# 1. Find the session log for THIS repo directory
ls -lt ~/.claude/projects/-Users-hiteshgoyal-Desktop-Projects-Experiments-Trials-TheraGenx-pv-case-review-platform/

# 2. Copy the newest one in
cp ~/.claude/projects/-Users-hiteshgoyal-Desktop-Projects-Experiments-Trials-TheraGenx-pv-case-review-platform/<session-id>.jsonl \
   claude-code-session.jsonl

# 3. Skim it for anything that should not be public, then commit
git add claude-code-session.jsonl
git commit -m "chore: add Claude Code session transcript"
git push
```

Commit it as it is. It is evaluated as a record of how the work actually went,
so editing it defeats the point — and the timestamps can be checked against the
commit history and the recording.
