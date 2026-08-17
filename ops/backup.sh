#!/usr/bin/env bash
#
# ops/backup.sh — snapshot every case held by the PV case review service.
#
# Enumerates GET /cases, fetches each case in full via GET /cases/{id}, and
# writes a single self-describing JSON document into backups/.
#
# Designed to be run from cron:
#   * never prompts,
#   * writes every diagnostic to stderr and ONLY the final backup path to
#     stdout, so `path="$(ops/backup.sh)"` works in a pipeline,
#   * exits non-zero on any failure,
#   * assembles into a temp file inside the output directory and moves it into
#     place with a single rename(2), so a killed run can never leave a
#     half-written file that looks like a valid backup.
#
# The output contains patient case data: umask 077 is set before anything is
# created, and no case field values are ever written to the log.
#
# Written for bash 3.2 (macOS system bash) and bash 5.x alike.

set -Eeuo pipefail

# Backups contain patient data. Set this before the first file is created so
# the temp file is never world-readable, not even for an instant.
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

readonly DEFAULT_OUTPUT_DIR="${REPO_ROOT}/backups"
readonly BACKUP_SCHEMA_VERSION=1

readonly CURL_CONNECT_TIMEOUT=5
readonly CURL_MAX_TIME=30
readonly RETRY_ATTEMPTS=3
readonly RETRY_BASE_DELAY=1

BASE_URL="${BASE_URL:-http://localhost:8080}"
OUTPUT_DIR="${DEFAULT_OUTPUT_DIR}"

WORK_DIR=""

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
  err "unexpected failure at ${SCRIPT_NAME}:${1} (exit ${2})"
}
trap 'on_error "${LINENO}" "$?"' ERR

# Idempotent: EXIT and a signal can both fire, and rm -rf -f tolerates a
# directory that was never created.
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
${SCRIPT_NAME} — snapshot every case from the PV case review service.

USAGE
  ${SCRIPT_NAME} [--base-url URL] [--output-dir DIR]
  ${SCRIPT_NAME} --help

OPTIONS
  --base-url URL     Service base URL.
                     Default: ${BASE_URL}  (env: BASE_URL)
  --output-dir DIR   Directory the backup is written to. Created if missing.
                     Default: ${DEFAULT_OUTPUT_DIR}
  -h, --help         Show this help and exit 0.

OUTPUT
  Diagnostics go to stderr. The absolute path of the finished backup is the
  only thing printed to stdout, so it composes in a cron pipeline:

      path="\$(${SCRIPT_NAME})" && gpg --encrypt --recipient ops@example.com "\$path"

  File name: cases-<UTC timestamp>-<pid>.json — two runs in the same second
  cannot clobber each other.

  File format (stable, versioned):
      {
        "backup_schema_version": ${BACKUP_SCHEMA_VERSION},
        "created_at": "2026-08-17T10:14:21Z",
        "source": "http://localhost:8080",
        "case_count": 2,
        "cases": [ <full case object as returned by GET /cases/{id}>, ... ]
      }

EXAMPLES
  ${SCRIPT_NAME}
  ${SCRIPT_NAME} --base-url https://pv.internal.example.com
  ${SCRIPT_NAME} --output-dir /secure/backups/theragenx

  # crontab: hourly, mail only on failure
  17 * * * * /path/to/ops/${SCRIPT_NAME} >/dev/null

EXIT CODES
  0  a complete, validated backup was written
  1  operational failure — nothing was left behind in the output directory
  2  usage error
EOF
}

# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
# http_get_json <url> <destination> <description>
#
# GET is idempotent, so it is safe to retry: transport errors and 5xx get a
# bounded linear backoff. 4xx responses are deterministic and are never
# retried. The HTTP status is checked explicitly — curl exiting 0 only means
# a response arrived, not that it was a good one.
http_get_json() {
  local url="$1" dest="$2" what="$3"
  local attempt=0 rc code delay err_file api_code
  err_file="${WORK_DIR}/curl.err"

  while :; do
    attempt=$((attempt + 1))
    rc=0
    code="$(curl --silent --show-error \
                 --connect-timeout "${CURL_CONNECT_TIMEOUT}" \
                 --max-time "${CURL_MAX_TIME}" \
                 --header 'Accept: application/json' \
                 --output "${dest}" \
                 --write-out '%{http_code}' \
                 -- "${url}" 2>"${err_file}")" || rc=$?

    if [ "${rc}" -eq 0 ] && [ "${code}" = '200' ]; then
      return 0
    fi

    if [ "${rc}" -ne 0 ]; then
      err "${what}: request failed ($(tr '\n' ' ' <"${err_file}"))"
    else
      # Only the machine-readable error code is logged. The message field can
      # echo request content, and this script must not put case data in logs.
      api_code="$(jq -r 'if type == "object" then (.code // "-") else "-" end' \
                     <"${dest}" 2>/dev/null || printf '%s' '-')"
      err "${what}: HTTP ${code} (error code: ${api_code})"
      case "${code}" in
        5*) : ;;               # transient, worth another go
        *)  return 1 ;;        # 4xx and anything else: deterministic
      esac
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

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -h | --help)
        usage
        exit "${EXIT_OK}"
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
      --output-dir)
        [ "$#" -ge 2 ] || usage_error "option '--output-dir' requires a value"
        OUTPUT_DIR="$2"
        shift 2
        ;;
      --output-dir=*)
        OUTPUT_DIR="${1#*=}"
        shift
        ;;
      --)
        shift
        break
        ;;
      -*)
        usage_error "unknown option: $1"
        ;;
      *)
        usage_error "unexpected argument: $1 (this script takes no positional arguments)"
        ;;
    esac
  done

  [ "$#" -eq 0 ] || usage_error "unexpected argument: $1"
  [ -n "${BASE_URL}" ] || usage_error "--base-url must not be empty"
  [ -n "${OUTPUT_DIR}" ] || usage_error "--output-dir must not be empty"
  BASE_URL="${BASE_URL%/}"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  parse_args "$@"
  require_cmd curl jq mktemp

  # Both timestamps are derived from a single `date` call so the file name and
  # the created_at field can never straddle a second boundary. The compact form
  # is sortable and safe in a file name; the ISO form goes in the document.
  local stamp created_at
  stamp="$(date -u +%Y%m%dT%H%M%SZ)" || die "could not read the current time"
  created_at="${stamp:0:4}-${stamp:4:2}-${stamp:6:2}T${stamp:9:2}:${stamp:11:2}:${stamp:13:2}Z"

  mkdir -p -- "${OUTPUT_DIR}" || die "could not create output directory: ${OUTPUT_DIR}"
  [ -w "${OUTPUT_DIR}" ] || die "output directory is not writable: ${OUTPUT_DIR}"

  # The work directory lives inside the output directory so the final `mv` is a
  # same-filesystem rename(2) — atomic. `mktemp -p` is GNU-only; the template
  # form is portable. The leading dot keeps it out of cases-*.json globs.
  WORK_DIR="$(mktemp -d "${OUTPUT_DIR}/.backup-work.XXXXXX")" \
    || die "could not create a work directory inside ${OUTPUT_DIR}"

  local index_file="${WORK_DIR}/index.json"
  local ids_file="${WORK_DIR}/ids.txt"
  local stream_file="${WORK_DIR}/cases.jsonstream"
  local case_file="${WORK_DIR}/case.json"
  local staged_file="${WORK_DIR}/backup.json"

  log "backing up from ${BASE_URL} into ${OUTPUT_DIR}"

  http_get_json "${BASE_URL}/cases" "${index_file}" 'GET /cases' \
    || die "could not enumerate cases from ${BASE_URL}/cases"

  # Validate the index before trusting it. `.cases | length` is the source of
  # truth; `.count` is cross-checked rather than believed.
  local expected_count reported_count
  expected_count="$(jq -r '
      if type != "object" then error("GET /cases did not return a JSON object")
      elif (.cases | type) != "array" then error("GET /cases has no \"cases\" array")
      else (.cases | length) end' <"${index_file}")" \
    || die "GET /cases returned an unexpected payload shape"

  reported_count="$(jq -r '.count // "-"' <"${index_file}")" \
    || die "could not read \"count\" from the case index"

  if [ "${reported_count}" != '-' ] && [ "${reported_count}" != "${expected_count}" ]; then
    warn "service reported count=${reported_count} but returned ${expected_count} case summaries; using ${expected_count}"
  fi

  # A newline-delimited id stream consumed by `while IFS= read -r`. Never
  # `for id in $(jq ...)`: that word-splits and glob-expands.
  jq -r '.cases[] | .case_id // ""' <"${index_file}" >"${ids_file}" \
    || die "could not extract case ids from the case index"

  : >"${stream_file}" || die "could not initialise the case stream file"

  local fetched=0 case_id case_id_enc actual_id
  while IFS= read -r case_id; do
    if [ -z "${case_id}" ]; then
      die "the case index contains an entry with a missing or empty case_id; refusing to write a partial backup"
    fi

    # Percent-encode via jq rather than assuming ids are URL-safe. This also
    # stops a hostile id from altering the request path.
    case_id_enc="$(jq -rn --arg s "${case_id}" '$s | @uri')" \
      || die "could not URL-encode case id '${case_id}'"

    http_get_json "${BASE_URL}/cases/${case_id_enc}" "${case_file}" "GET /cases/${case_id}" \
      || die "could not fetch case '${case_id}'; refusing to write a partial backup"

    # Guard against the service returning something other than the case asked
    # for. Only identifiers are compared and logged — never field values.
    actual_id="$(jq -r '
        if type != "object" then error("case payload is not a JSON object")
        elif (.case_id | type) != "string" then error("case payload has no string case_id")
        else .case_id end' <"${case_file}")" \
      || die "case '${case_id}' came back in an unexpected shape"

    if [ "${actual_id}" != "${case_id}" ]; then
      die "asked for case '${case_id}' but the service returned '${actual_id}'"
    fi

    cat -- "${case_file}" >>"${stream_file}" \
      || die "could not append case '${case_id}' to the staging stream"
    printf '\n' >>"${stream_file}" \
      || die "could not append case '${case_id}' to the staging stream"

    fetched=$((fetched + 1))
  done <"${ids_file}"

  if [ "${fetched}" -ne "${expected_count}" ]; then
    die "expected ${expected_count} case(s) but fetched ${fetched}; refusing to write an incomplete backup"
  fi

  if [ "${fetched}" -eq 0 ]; then
    warn "the service reported zero cases — writing an empty but structurally valid backup"
  fi

  # Assemble in one jq pass. case_count is computed from the array itself, so
  # the envelope cannot disagree with its own payload. Key order here is the
  # documented on-disk order.
  jq -n \
     --argjson schema_version "${BACKUP_SCHEMA_VERSION}" \
     --arg created_at "${created_at}" \
     --arg source "${BASE_URL}" \
     --slurpfile cases "${stream_file}" \
     '{
        backup_schema_version: $schema_version,
        created_at: $created_at,
        source: $source,
        case_count: ($cases | length),
        cases: $cases
      }' >"${staged_file}" \
    || die "could not assemble the backup document"

  # Independent re-parse of what actually landed on disk. Catches a truncated
  # write (full disk, quota) that jq itself would not have reported.
  jq empty <"${staged_file}" \
    || die "the assembled backup did not re-parse as valid JSON; nothing was published"

  local staged_count
  staged_count="$(jq -r '.case_count' <"${staged_file}")" \
    || die "could not read case_count back from the assembled backup"
  [ "${staged_count}" = "${fetched}" ] \
    || die "assembled backup claims ${staged_count} case(s) but ${fetched} were fetched"

  # Timestamp + pid already makes a same-second collision impossible between
  # concurrent runs; the loop closes the remaining sub-second reuse window.
  local final_path="${OUTPUT_DIR}/cases-${stamp}-$$.json"
  local suffix=0
  while [ -e "${final_path}" ]; do
    suffix=$((suffix + 1))
    [ "${suffix}" -le 100 ] || die "could not find a free backup file name in ${OUTPUT_DIR}"
    final_path="${OUTPUT_DIR}/cases-${stamp}-$$-${suffix}.json"
  done

  # Single rename(2) within one filesystem: readers see either no file or the
  # complete one, never a partial document.
  mv -- "${staged_file}" "${final_path}" \
    || die "could not move the staged backup into ${OUTPUT_DIR}"

  log "wrote ${fetched} case(s) to ${final_path}"

  # The one and only line on stdout.
  printf '%s\n' "${final_path}"
}

main "$@"
