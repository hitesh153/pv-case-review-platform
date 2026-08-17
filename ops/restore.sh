#!/usr/bin/env bash
#
# ops/restore.sh — restore cases from a backup produced by ops/backup.sh.
#
# The whole file is validated before the first write, so a malformed backup can
# never leave the service half-restored: schema version, the shape of every
# case, and case_id uniqueness are all checked up front.
#
# Each case is written with PUT /cases/{case_id}, which replaces state. That
# makes the restore idempotent by construction: running the same file twice
# leaves byte-identical state and does not bump any case version. This script
# deliberately never POSTs follow-ups, which would merge rather than replace and
# would break that property.
#
# Written for bash 3.2 (macOS system bash) and bash 5.x alike.

set -Eeuo pipefail

# Case payloads are written to a temp directory during the run.
umask 077

# ---------------------------------------------------------------------------
# Path resolution
# ---------------------------------------------------------------------------
SCRIPT_NAME="$(basename -- "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd -P)"
readonly SCRIPT_NAME SCRIPT_DIR REPO_ROOT

readonly EXIT_OK=0
readonly EXIT_FAILURE=1
readonly EXIT_USAGE=2

readonly SUPPORTED_SCHEMA_VERSION=1

readonly CURL_CONNECT_TIMEOUT=5
readonly CURL_MAX_TIME=30
readonly RETRY_ATTEMPTS=3
readonly RETRY_BASE_DELAY=1

BASE_URL="${BASE_URL:-http://localhost:8080}"
DRY_RUN=0
BACKUP_FILE=""
WORK_DIR=""

# Set by http_send().
HTTP_CODE=""
HTTP_ERROR=""

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------
_timestamp() { date -u +%Y-%m-%dT%H:%M:%SZ; }

log()  { printf '%s  INFO %s: %s\n' "$(_timestamp)" "${SCRIPT_NAME}" "$*" >&2; }
warn() { printf '%s  WARN %s: %s\n' "$(_timestamp)" "${SCRIPT_NAME}" "$*" >&2; }
err()  { printf '%s ERROR %s: %s\n' "$(_timestamp)" "${SCRIPT_NAME}" "$*" >&2; }

die() { err "$*"; exit "${EXIT_FAILURE}"; }

usage_error() {
  err "$*"
  err "run '${SCRIPT_NAME} --help' for usage."
  exit "${EXIT_USAGE}"
}

require_cmd() {
  local cmd missing=0
  for cmd in "$@"; do
    if ! command -v -- "${cmd}" >/dev/null 2>&1; then
      err "required command not found on PATH: ${cmd}"
      missing=1
    fi
  done
  [ "${missing}" -eq 0 ] || exit "${EXIT_FAILURE}"
}

on_error() {
  # `set -E` deliberately propagates this trap into functions AND into
  # subshells, including command substitutions. That means a *handled* failure
  # such as `x="$(cmd)" || fallback` would trip the trap inside the
  # substitution before the `||` could suppress it, printing a bogus
  # diagnostic. BASH_SUBSHELL is 0 only in the top-level shell (verified on
  # bash 3.2 and 5.x), so reporting is limited to genuinely unhandled errors.
  [ "${BASH_SUBSHELL:-0}" -eq 0 ] || return 0
  err "unexpected failure at ${SCRIPT_NAME}:${1} (exit ${2})"
}
trap 'on_error "${LINENO}" "$?"' ERR

cleanup() {
  local rc=$?
  trap - EXIT HUP INT TERM
  if [ -n "${WORK_DIR}" ] && [ -d "${WORK_DIR}" ]; then
    rm -rf -- "${WORK_DIR}"
  fi
  exit "${rc}"
}
trap cleanup EXIT HUP INT TERM

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
  cat <<EOF
${SCRIPT_NAME} — restore cases from a backup written by backup.sh.

USAGE
  ${SCRIPT_NAME} [options] <backup-file>
  ${SCRIPT_NAME} --help

ARGUMENTS
  <backup-file>      Exactly one backup file, as produced by backup.sh
                     (backup_schema_version ${SUPPORTED_SCHEMA_VERSION}).

OPTIONS
  --dry-run          Perform ZERO writes. Probes each case and reports exactly
                     what a real run would create and what it would replace.
  --base-url URL     Service base URL.
                     Default: ${BASE_URL}  (env: BASE_URL)
  -h, --help         Show this help and exit 0.

BEHAVIOUR
  The entire file is validated before the first write. If anything is wrong —
  unsupported schema version, missing cases array, a blank or duplicated
  case_id — the script exits without having modified any case.

  Each case is written with PUT /cases/{case_id}, which replaces state rather
  than merging. Restoring the same file twice therefore leaves identical state
  and does not bump case versions.

  A PUT is not retried: it is the mutating call, and a bounded retry buys
  nothing that a re-run of the whole script does not already provide. Read-only
  probes are retried with linear backoff.

EXAMPLES
  ${SCRIPT_NAME} --dry-run backups/cases-20260817T101421Z-4242.json
  ${SCRIPT_NAME} backups/cases-20260817T101421Z-4242.json
  ${SCRIPT_NAME} --base-url http://staging.internal:8080 backups/latest.json

  # restore whatever the newest backup is
  ${SCRIPT_NAME} "\$(ls -t "${REPO_ROOT}/backups"/cases-*.json | head -1)"

EXIT CODES
  0  every case was restored (or, with --dry-run, every case was probed)
  1  validation failed, the service was unreachable, or at least one case
     failed — a partial success still exits non-zero
  2  usage error (no file, more than one file, unknown option)
EOF
}

# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
# http_send <method> <url> <request-body-file|-> <response-file>
#
# Sets HTTP_CODE (empty if no response arrived) and HTTP_ERROR. Returns 0 when
# a response of any status arrived, non-zero when the request could not be
# completed at all. Interpreting the status code is the caller's job — 404 is a
# perfectly good answer for a probe and a failure for a write.
http_send() {
  local method="$1" url="$2" body="$3" dest="$4"
  local rc=0
  local err_file="${WORK_DIR}/curl.err"
  local code_file="${WORK_DIR}/curl.code"

  HTTP_CODE=""
  HTTP_ERROR=""

  # curl is run directly rather than inside a command substitution. `set -E`
  # makes subshells inherit the ERR trap, so `HTTP_CODE="$(curl ...)" || rc=$?`
  # would fire the trap *inside* the substitution before the `||` could
  # suppress it, printing a bogus "unexpected failure" line for every handled
  # transport error. %{http_code} goes to a file so curl stays in this shell.
  if [ "${body}" = '-' ]; then
    curl --silent --show-error \
         --connect-timeout "${CURL_CONNECT_TIMEOUT}" \
         --max-time "${CURL_MAX_TIME}" \
         --request "${method}" \
         --header 'Accept: application/json' \
         --output "${dest}" \
         --write-out '%{http_code}' \
         -- "${url}" >"${code_file}" 2>"${err_file}" || rc=$?
  else
    curl --silent --show-error \
         --connect-timeout "${CURL_CONNECT_TIMEOUT}" \
         --max-time "${CURL_MAX_TIME}" \
         --request "${method}" \
         --header 'Content-Type: application/json' \
         --header 'Accept: application/json' \
         --data-binary "@${body}" \
         --output "${dest}" \
         --write-out '%{http_code}' \
         -- "${url}" >"${code_file}" 2>"${err_file}" || rc=$?
  fi

  if [ "${rc}" -ne 0 ]; then
    HTTP_ERROR="$(tr '\n' ' ' <"${err_file}" || true)"
    HTTP_CODE=""
    return 1
  fi

  HTTP_CODE="$(cat -- "${code_file}" 2>/dev/null || true)"
  if [ -z "${HTTP_CODE}" ]; then
    HTTP_ERROR='curl reported success but returned no HTTP status code'
    return 1
  fi
  return 0
}

# Retrying read-only GET. Transport errors and 5xx are retried with bounded
# linear backoff; every other status is handed straight back to the caller.
http_get_retry() {
  local url="$1" dest="$2" what="$3"
  local attempt=0 delay

  while :; do
    attempt=$((attempt + 1))
    if http_send GET "${url}" '-' "${dest}"; then
      case "${HTTP_CODE}" in
        5*) err "${what}: HTTP ${HTTP_CODE}" ;;
        *)  return 0 ;;
      esac
    else
      err "${what}: request failed (${HTTP_ERROR})"
    fi

    if [ "${attempt}" -ge "${RETRY_ATTEMPTS}" ]; then
      err "${what}: giving up after ${attempt} attempt(s)"
      return 1
    fi

    delay=$((RETRY_BASE_DELAY * attempt))
    warn "${what}: retrying in ${delay}s (attempt $((attempt + 1))/${RETRY_ATTEMPTS})"
    sleep "${delay}"
  done
}

# Only the machine-readable .code is pulled out of an error envelope. The
# message/errors fields can quote request content, and case field values must
# never reach the log.
api_error_code() {
  local response="$1" code
  code="$(jq -r 'if type == "object" then (.code // "-") else "-" end' \
             <"${response}" 2>/dev/null)" || code='-'
  [ -n "${code}" ] || code='-'
  printf '%s\n' "${code}"
}

# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------
# One jq program, run before anything is written. Every failure path names the
# problem precisely so an operator can fix the file rather than guess.
validate_backup() {
  local file="$1" err_file="${WORK_DIR}/validate.err"
  local doc_count line

  # jq happily streams a file holding several concatenated JSON documents,
  # applying the program to each in turn. If every document passed the schema
  # check below, later `jq -r '.cases | length'` calls would emit one number
  # per document and downstream integer tests would break on "2\n1". Insist on
  # exactly one document up front. This also catches unparseable input.
  if ! doc_count="$(jq -s 'length' <"${file}" 2>"${err_file}")"; then
    err "backup file is not valid JSON: ${file}"
    while IFS= read -r line; do
      if [ -n "${line}" ]; then
        err "  ${line}"
      fi
    done <"${err_file}"
    err "nothing was written — the service is unchanged."
    return 1
  fi

  if [ "${doc_count}" != '1' ]; then
    err "backup file must contain exactly one JSON document, found ${doc_count}: ${file}"
    err "nothing was written — the service is unchanged."
    return 1
  fi

  # The supported version is passed in with --argjson rather than spliced into
  # the program text, so the shell never builds jq source out of variables.
  if ! jq -e --argjson want "${SUPPORTED_SCHEMA_VERSION}" '
      def ids: [.cases[] | .case_id?];
      def blank_ids: [.cases[] | select((.case_id | type) == "string")
                               | .case_id
                               | select(gsub("^\\s+|\\s+$"; "") == "")];
      def dup_ids: (ids | group_by(.) | map(select(length > 1) | .[0]));

      if type != "object" then
        error("top level is not a JSON object")
      elif (has("backup_schema_version") | not) then
        error("missing required field \"backup_schema_version\"")
      elif .backup_schema_version != $want then
        error("unsupported backup_schema_version \(.backup_schema_version | tojson); this script understands \($want)")
      elif (has("cases") | not) then
        error("missing required field \"cases\"")
      elif (.cases | type) != "array" then
        error("\"cases\" is \(.cases | type), expected array")
      elif ([.cases[] | select(type != "object")] | length) > 0 then
        error("\([.cases[] | select(type != "object")] | length) entry/entries in \"cases\" are not JSON objects")
      elif ([.cases[] | select((.case_id | type) != "string")] | length) > 0 then
        error("\([.cases[] | select((.case_id | type) != "string")] | length) case(s) have a missing or non-string case_id")
      elif (blank_ids | length) > 0 then
        error("\(blank_ids | length) case(s) have a blank case_id")
      elif (dup_ids | length) > 0 then
        error("duplicate case_id(s): \(dup_ids | join(", "))")
      elif (has("case_count") and (.case_count != (.cases | length))) then
        error("case_count says \(.case_count | tojson) but \"cases\" holds \(.cases | length); the file is inconsistent")
      else
        true
      end' <"${file}" >/dev/null 2>"${err_file}"; then
    err "backup file failed validation: ${file}"
    # jq prefixes its own diagnostics; pass them through verbatim, one per line.
    # Written as an `if` rather than `[ ... ] && err ...` because a false test
    # as the last command in the loop body would trip `set -e`.
    while IFS= read -r line; do
      if [ -n "${line}" ]; then
        err "  ${line}"
      fi
    done <"${err_file}"
    err "nothing was written — the service is unchanged."
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
parse_args() {
  local positional_count=0

  while [ "$#" -gt 0 ]; do
    case "$1" in
      -h | --help)
        usage
        exit "${EXIT_OK}"
        ;;
      --dry-run)
        DRY_RUN=1
        shift
        ;;
      --base-url)
        [ "$#" -ge 2 ] || usage_error "option '--base-url' requires a value"
        BASE_URL="$2"
        shift 2
        ;;
      --base-url=*)
        BASE_URL="${1#*=}"
        shift
        ;;
      --)
        shift
        # Everything after -- is positional, including names starting with '-'.
        while [ "$#" -gt 0 ]; do
          positional_count=$((positional_count + 1))
          [ "${positional_count}" -eq 1 ] \
            || usage_error "expected exactly one backup file, got extra argument: $1"
          BACKUP_FILE="$1"
          shift
        done
        break
        ;;
      -*)
        usage_error "unknown option: $1"
        ;;
      *)
        positional_count=$((positional_count + 1))
        [ "${positional_count}" -eq 1 ] \
          || usage_error "expected exactly one backup file, got extra argument: $1"
        BACKUP_FILE="$1"
        shift
        ;;
    esac
  done

  [ "${positional_count}" -ge 1 ] || usage_error "no backup file given"
  [ -n "${BASE_URL}" ] || usage_error "--base-url must not be empty"
  BASE_URL="${BASE_URL%/}"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  parse_args "$@"
  require_cmd curl jq mktemp

  [ -e "${BACKUP_FILE}" ] || die "backup file does not exist: ${BACKUP_FILE}"
  [ -f "${BACKUP_FILE}" ] || die "not a regular file: ${BACKUP_FILE}"
  [ -r "${BACKUP_FILE}" ] || die "backup file is not readable: ${BACKUP_FILE}"
  [ -s "${BACKUP_FILE}" ] || die "backup file is empty: ${BACKUP_FILE}"

  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/theragenx-restore.XXXXXX")" \
    || die "could not create a temporary working directory"

  local mode='restore'
  [ "${DRY_RUN}" -eq 0 ] || mode='dry-run'
  log "${mode}: ${BACKUP_FILE} -> ${BASE_URL}"

  # ---- validate everything before touching the service --------------------
  validate_backup "${BACKUP_FILE}" || exit "${EXIT_FAILURE}"

  local total created_at source_url
  total="$(jq -r '.cases | length' <"${BACKUP_FILE}")" \
    || die "could not read the case count from ${BACKUP_FILE}"
  created_at="$(jq -r '.created_at // "unknown"' <"${BACKUP_FILE}")" \
    || die "could not read created_at from ${BACKUP_FILE}"
  source_url="$(jq -r '.source // "unknown"' <"${BACKUP_FILE}")" \
    || die "could not read source from ${BACKUP_FILE}"

  log "backup validated: ${total} case(s), created_at=${created_at}, source=${source_url}"

  if [ "${total}" -eq 0 ]; then
    warn "the backup contains zero cases; there is nothing to restore"
    log "summary: created=0 replaced=0 failed=0"
    return 0
  fi

  # ---- reachability preflight --------------------------------------------
  # Fail fast on an unreachable service instead of grinding through N writes.
  local health_file="${WORK_DIR}/health.json" health_status
  http_get_retry "${BASE_URL}/health" "${health_file}" "GET ${BASE_URL}/health" \
    || die "service at ${BASE_URL} is not reachable; nothing was written"
  [ "${HTTP_CODE}" = '200' ] \
    || die "GET ${BASE_URL}/health returned HTTP ${HTTP_CODE}; nothing was written"
  health_status="$(jq -r 'if type == "object" then (.status // "?") else "?" end' \
                      <"${health_file}")" \
    || die "GET ${BASE_URL}/health returned a body that is not valid JSON"
  log "target service is reachable (health status=\"${health_status}\")"

  # ---- apply --------------------------------------------------------------
  local case_file="${WORK_DIR}/case.json"
  local response_file="${WORK_DIR}/response.json"
  local index=0 created=0 replaced=0 failed=0
  local case_id case_id_enc url api_code

  # Iterated by index in the current shell. A `while read` on the right-hand
  # side of a pipe would run in a subshell and silently discard these counters.
  while [ "${index}" -lt "${total}" ]; do
    jq -ce --argjson i "${index}" '.cases[$i]' <"${BACKUP_FILE}" >"${case_file}" \
      || die "could not extract case at index ${index} from ${BACKUP_FILE}"
    case_id="$(jq -re '.case_id' <"${case_file}")" \
      || die "could not read case_id at index ${index} from ${BACKUP_FILE}"

    # Percent-encode instead of assuming ids are URL-safe; this also prevents a
    # crafted id from rewriting the request path.
    case_id_enc="$(jq -rn --arg s "${case_id}" '$s | @uri')" \
      || die "could not URL-encode case id '${case_id}'"
    url="${BASE_URL}/cases/${case_id_enc}"

    if [ "${DRY_RUN}" -eq 1 ]; then
      # Read-only probe. No PUT is issued anywhere in this branch.
      if ! http_get_retry "${url}" "${response_file}" "GET /cases/${case_id}"; then
        err "${case_id}: could not be probed; a real run would attempt a write"
        failed=$((failed + 1))
      else
        case "${HTTP_CODE}" in
          200)
            replaced=$((replaced + 1))
            log "${case_id}: would REPLACE (case exists)"
            ;;
          404)
            created=$((created + 1))
            log "${case_id}: would CREATE (case absent)"
            ;;
          *)
            api_code="$(api_error_code "${response_file}")"
            err "${case_id}: unexpected HTTP ${HTTP_CODE} on probe (error code: ${api_code})"
            failed=$((failed + 1))
            ;;
        esac
      fi
    else
      # PUT replaces state and is idempotent server-side, so no follow-up POST
      # and no retry: a re-run of this script is the retry.
      if ! http_send PUT "${url}" "${case_file}" "${response_file}"; then
        err "${case_id}: PUT failed (${HTTP_ERROR})"
        failed=$((failed + 1))
      else
        case "${HTTP_CODE}" in
          200)
            replaced=$((replaced + 1))
            log "${case_id}: replaced"
            ;;
          201)
            created=$((created + 1))
            log "${case_id}: created"
            ;;
          *)
            api_code="$(api_error_code "${response_file}")"
            err "${case_id}: PUT returned HTTP ${HTTP_CODE} (error code: ${api_code})"
            failed=$((failed + 1))
            ;;
        esac
      fi
    fi

    index=$((index + 1))
  done

  # ---- report -------------------------------------------------------------
  if [ "${DRY_RUN}" -eq 1 ]; then
    log "dry-run summary (no writes performed): would-create=${created} would-replace=${replaced} unprobeable=${failed} of ${total}"
  else
    log "summary: created=${created} replaced=${replaced} failed=${failed} of ${total}"
  fi

  if [ "${failed}" -gt 0 ]; then
    err "${failed} of ${total} case(s) did not succeed"
    exit "${EXIT_FAILURE}"
  fi
  return 0
}

main "$@"
