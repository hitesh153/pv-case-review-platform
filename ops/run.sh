#!/usr/bin/env bash
#
# ops/run.sh — lifecycle entrypoint for the PV case review platform.
#
# Wraps Docker Compose so that "started" means "the API answered GET /health
# with status=up", not merely "the container was created". The container
# healthcheck can go green while the service still reports "degraded" (running,
# but zero cases loaded), so this script polls the API itself.
#
# Only the subcommands that genuinely need the Docker daemon probe for it.
# `--help` and `test` never touch Docker, so they work on a machine where
# Docker Desktop is not installed or not running.
#
# Written for bash 3.2 (the macOS system bash) as well as bash 5.x in CI:
# no mapfile, no associative arrays, no GNU-only coreutils flags.

set -Eeuo pipefail

# ---------------------------------------------------------------------------
# Path resolution
# ---------------------------------------------------------------------------
# Derived from BASH_SOURCE, never $PWD, so the script behaves identically when
# invoked as ./ops/run.sh, /abs/path/ops/run.sh, from make, or from cron.
# `cd ... && pwd -P` is used instead of `readlink -f`, which does not exist in
# the BSD userland shipped with macOS.
SCRIPT_NAME="$(basename -- "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd -P)"
readonly SCRIPT_NAME SCRIPT_DIR REPO_ROOT

readonly COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"
readonly COMPOSE_PROJECT="theragenx"
readonly COMPOSE_SERVICE="backend"
readonly BACKEND_DIR="${REPO_ROOT}/backend"

readonly EXIT_OK=0
readonly EXIT_FAILURE=1
readonly EXIT_USAGE=2

# Tunables. Environment wins over the built-in default; an explicit flag wins
# over the environment.
BASE_URL="${BASE_URL:-http://localhost:8080}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-2}"

readonly CURL_CONNECT_TIMEOUT=3
readonly CURL_MAX_TIME=10
readonly DEFAULT_LOG_TAIL=100
readonly FAILURE_LOG_LINES=100

LOG_TAIL="${DEFAULT_LOG_TAIL}"
LOG_TAIL_GIVEN=0
LOG_FOLLOW=1
LOG_FOLLOW_GIVEN=0
TIMEOUT_GIVEN=0
SCRATCH_DIR=""

# Set by probe_health() so callers can explain *why* a probe failed.
HEALTH_REASON=""

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------
_timestamp() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# All diagnostics go to stderr. Stdout is reserved for command output that a
# caller might legitimately want to capture.
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

# Surfaces the failing line for genuinely unexpected errors. Expected failures
# are all handled explicitly with `|| rc=$?` or `if ! ...`, which do not trip
# the ERR trap.
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
  if [ -n "${SCRATCH_DIR}" ] && [ -d "${SCRATCH_DIR}" ]; then
    rm -rf -- "${SCRATCH_DIR}"
  fi
  exit "${rc}"
}
trap cleanup EXIT HUP INT TERM

# Lazily created, so `--help` and `test` never write to the filesystem.
scratch_file() {
  if [ -z "${SCRATCH_DIR}" ]; then
    SCRATCH_DIR="$(mktemp -d "${TMPDIR:-/tmp}/theragenx-run.XXXXXX")" \
      || die "could not create a temporary working directory"
  fi
  printf '%s/%s\n' "${SCRATCH_DIR}" "$1"
}

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
  cat <<EOF
${SCRIPT_NAME} — lifecycle for the PV case review platform.

Operates exclusively on Docker Compose project '${COMPOSE_PROJECT}' defined by
${COMPOSE_FILE}.

USAGE
  ${SCRIPT_NAME} <subcommand> [options]

SUBCOMMANDS
  build              Build the '${COMPOSE_SERVICE}' image.
  start              Bring the stack up, then block until GET /health reports
                     status="up". Exits non-zero (with a log tail) on timeout.
  stop               Stop and remove this project's containers. Idempotent:
                     exits 0 when the stack is already stopped.
  restart            stop, then start.
  test               Run the backend test suite via the Maven wrapper.
                     Requires a JDK; does NOT require Docker.
  logs [options]     Follow the service logs. Ctrl-C exits cleanly.
                     Use --no-follow to print and exit, which is what you want
                     when piping to grep.
  health             One-shot health probe. Exits 0 only when status="up".
  clean              Remove this project's containers, networks, volumes and
                     locally built images. Scoped to '${COMPOSE_PROJECT}': it
                     never runs 'docker system prune' and never touches
                     ${REPO_ROOT}/backups.
  -h, --help         Show this help and exit 0.

OPTIONS
  --base-url URL     API base URL used by 'start' and 'health'.
                     Default: ${BASE_URL}  (env: BASE_URL)
  --timeout SECONDS  How long 'start' waits for status="up".
                     Default: ${HEALTH_TIMEOUT}  (env: HEALTH_TIMEOUT)
  --tail N           'logs' only. Lines of history before following.
                     N is a positive integer or the word 'all'.
                     Default: ${DEFAULT_LOG_TAIL}
  --no-follow        'logs' only. Print the tail and exit instead of streaming.
                     Required when piping to grep — the default would hang.

EXAMPLES
  ${SCRIPT_NAME} build
  ${SCRIPT_NAME} start
  ${SCRIPT_NAME} start --timeout 240
  APP_PORT=9090 ${SCRIPT_NAME} start --base-url http://localhost:9090
  ${SCRIPT_NAME} logs --tail 200
  ${SCRIPT_NAME} logs --no-follow --tail 200 | grep -i 'bootstrap file'
  ${SCRIPT_NAME} health && echo 'service is up'
  ${SCRIPT_NAME} test
  ${SCRIPT_NAME} clean

ENVIRONMENT
  BASE_URL          API base URL                       (default http://localhost:8080)
  APP_PORT          Host port published by compose      (default 8080, read by compose)
  HEALTH_TIMEOUT    Seconds 'start' waits for health    (default 120)
  HEALTH_INTERVAL   Seconds between health probes       (default 2)

EXIT CODES
  0  success, including --help
  1  operational failure (build failed, health never reached "up", ...)
  2  usage error (unknown subcommand, unknown or malformed option)
EOF
}

# ---------------------------------------------------------------------------
# Docker preflight
# ---------------------------------------------------------------------------
# Called only from subcommands that actually talk to the daemon.
require_docker() {
  require_cmd docker

  if ! docker compose version >/dev/null 2>&1; then
    err "'docker compose' (Compose v2) is not available."
    err "this project requires Compose v2 — the 'docker compose' subcommand,"
    err "not the legacy standalone 'docker-compose' binary."
    exit "${EXIT_FAILURE}"
  fi

  if ! docker info >/dev/null 2>&1; then
    err "cannot reach the Docker daemon."
    err "  macOS:       start Docker Desktop and wait for the whale icon to settle."
    err "  Linux:       sudo systemctl start docker"
    err "  permissions: your user must be able to read the daemon socket"
    err "               (usually: add yourself to the 'docker' group, then re-login)."
    err "  remote host: DOCKER_HOST is currently '${DOCKER_HOST:-<unset>}'."
    exit "${EXIT_FAILURE}"
  fi

  [ -f "${COMPOSE_FILE}" ] \
    || die "compose file not found: ${COMPOSE_FILE}"
}

# Every compose invocation is pinned to this file and this project name, which
# is what makes 'clean' provably scoped and makes the script cwd-independent.
compose() {
  docker compose \
    --file "${COMPOSE_FILE}" \
    --project-name "${COMPOSE_PROJECT}" \
    "$@"
}

# ---------------------------------------------------------------------------
# Health probing
# ---------------------------------------------------------------------------
# One probe of GET <BASE_URL>/health.
#   0 -> HTTP 200 and .status == "up"
#   1 -> the service answered, but not with a usable "up"
#   2 -> nothing answered (connection refused, DNS, timeout)
# HEALTH_REASON always explains the outcome.
probe_health() {
  local body_file err_file code_file code status rc=0

  body_file="$(scratch_file health.json)"
  err_file="$(scratch_file health.err)"
  code_file="$(scratch_file health.code)"
  HEALTH_REASON=""

  # curl's exit status only tells us whether the request completed; the HTTP
  # status is captured separately via --write-out and checked explicitly.
  #
  # curl is run directly rather than inside a command substitution: `set -E`
  # makes subshells inherit the ERR trap, so `code="$(curl ...)"` would fire
  # the trap inside the substitution on every failed probe and spam the log
  # while waiting for a JVM to boot.
  curl --silent --show-error \
       --connect-timeout "${CURL_CONNECT_TIMEOUT}" \
       --max-time "${CURL_MAX_TIME}" \
       --header 'Accept: application/json' \
       --output "${body_file}" \
       --write-out '%{http_code}' \
       -- "${BASE_URL}/health" >"${code_file}" 2>"${err_file}" || rc=$?

  if [ "${rc}" -ne 0 ]; then
    HEALTH_REASON="no response from ${BASE_URL}/health ($(tr '\n' ' ' <"${err_file}" || true))"
    return 2
  fi

  code="$(cat -- "${code_file}" 2>/dev/null || true)"
  if [ -z "${code}" ]; then
    HEALTH_REASON="curl reported success but returned no HTTP status code for ${BASE_URL}/health"
    return 2
  fi

  if [ "${code}" != "200" ]; then
    HEALTH_REASON="GET ${BASE_URL}/health returned HTTP ${code}"
    return 1
  fi

  if ! status="$(jq -r 'if type == "object" then (.status // "") else "" end' \
                    <"${body_file}" 2>"${err_file}")"; then
    HEALTH_REASON="GET ${BASE_URL}/health returned HTTP 200 with a body that is not valid JSON"
    return 1
  fi

  case "${status}" in
    up)
      HEALTH_REASON='status="up"'
      return 0
      ;;
    degraded)
      HEALTH_REASON='status="degraded" — the service is running but has zero cases loaded'
      return 1
      ;;
    '')
      HEALTH_REASON='HTTP 200 but the response body has no "status" field'
      return 1
      ;;
    *)
      HEALTH_REASON="status=\"${status}\""
      return 1
      ;;
  esac
}

dump_recent_logs() {
  err "--- last ${FAILURE_LOG_LINES} log lines from service '${COMPOSE_SERVICE}' ---"
  if ! compose logs --no-color --tail "${FAILURE_LOG_LINES}" "${COMPOSE_SERVICE}" >&2; then
    err "(container logs could not be read — the container may never have started)"
  fi
  err "--- end of log tail ---"
}

wait_for_health() {
  local attempt=0 rc=0

  # SECONDS is a bash builtin counter; using it avoids date arithmetic, which
  # is where BSD and GNU `date` diverge.
  SECONDS=0
  log "waiting up to ${HEALTH_TIMEOUT}s for ${BASE_URL}/health to report status=\"up\""

  while :; do
    attempt=$((attempt + 1))
    rc=0
    probe_health || rc=$?

    if [ "${rc}" -eq 0 ]; then
      log "service is healthy after ${SECONDS}s (${attempt} probe(s))"
      return 0
    fi

    if [ "${SECONDS}" -ge "${HEALTH_TIMEOUT}" ]; then
      break
    fi

    # Report the first probe and then roughly every 10th, so a slow JVM start
    # is visible without flooding the terminal.
    if [ "${attempt}" -eq 1 ] || [ $((attempt % 10)) -eq 0 ]; then
      log "not ready yet (${SECONDS}s elapsed): ${HEALTH_REASON}"
    fi

    sleep "${HEALTH_INTERVAL}"
  done

  err "timed out after ${HEALTH_TIMEOUT}s waiting for ${BASE_URL}/health to report status=\"up\""
  err "last probe result: ${HEALTH_REASON}"
  case "${rc}" in
    2) err "the API never answered — the container may have exited, or APP_PORT may not match --base-url." ;;
    *) err "the API answered but never became \"up\" — check the log tail below." ;;
  esac
  dump_recent_logs
  exit "${EXIT_FAILURE}"
}

# ---------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------
cmd_build() {
  require_docker
  log "building image for service '${COMPOSE_SERVICE}' (project '${COMPOSE_PROJECT}')"
  compose build "${COMPOSE_SERVICE}" || die "'docker compose build' failed"
  log "build complete"
}

cmd_start() {
  require_docker
  require_cmd curl jq

  # Cheap guard against the most common false "start failed": compose publishes
  # ${APP_PORT} on the host while the health probe still points at 8080.
  if [ -n "${APP_PORT:-}" ] && [ "${APP_PORT}" != '8080' ] \
     && [ "${BASE_URL}" = 'http://localhost:8080' ]; then
    warn "APP_PORT=${APP_PORT} but the health probe targets ${BASE_URL}"
    warn "pass --base-url http://localhost:${APP_PORT} (or set BASE_URL) or 'start' will time out"
  fi

  log "starting project '${COMPOSE_PROJECT}'"
  # --remove-orphans keeps repeated starts from accumulating containers left
  # behind by an older compose file. Starting an already-running stack is a
  # no-op, so start is safe to repeat.
  compose up --detach --remove-orphans || die "'docker compose up' failed"
  wait_for_health
  log "started; API is available at ${BASE_URL}"
}

cmd_stop() {
  require_docker
  log "stopping project '${COMPOSE_PROJECT}'"
  # `down` on an already-stopped or never-created project is a successful
  # no-op, which is what makes stop idempotent without inspecting any state.
  # Volumes are deliberately preserved here — removing them is `clean`.
  compose down --remove-orphans || die "'docker compose down' failed"
  log "stopped"
}

cmd_test() {
  # Deliberately no require_docker: the test suite runs on the host.
  [ -d "${BACKEND_DIR}" ] || die "backend directory not found: ${BACKEND_DIR}"
  [ -x "${BACKEND_DIR}/mvnw" ] \
    || die "Maven wrapper missing or not executable: ${BACKEND_DIR}/mvnw"

  log "running backend test suite (Docker not required)"
  # Run in a subshell so the caller's working directory is never mutated.
  ( cd -- "${BACKEND_DIR}" && ./mvnw -B test ) \
    || die "backend test suite failed"
  log "backend test suite passed"
}

cmd_logs() {
  require_docker
  local rc=0

  # Without --no-follow this streams forever, which is right at a terminal and
  # wrong in a pipeline: `run.sh logs | grep pattern` would hang rather than
  # answer. Grepping the logs is exactly what the runbook asks an on-call
  # engineer to do, so the non-following form has to exist.
  if [ "${LOG_FOLLOW}" -eq 0 ]; then
    log "reading last ${LOG_TAIL} log line(s) for '${COMPOSE_SERVICE}'"
    compose logs --no-color --no-log-prefix --tail "${LOG_TAIL}" "${COMPOSE_SERVICE}" \
      || die "'docker compose logs' failed"
    return 0
  fi

  log "following logs for '${COMPOSE_SERVICE}' (--tail ${LOG_TAIL}); Ctrl-C to stop"
  compose logs --no-color --follow --tail "${LOG_TAIL}" "${COMPOSE_SERVICE}" || rc=$?
  # 130 is SIGINT: pressing Ctrl-C is the normal way to stop following logs and
  # is not an operational failure. Anything else is.
  if [ "${rc}" -ne 0 ] && [ "${rc}" -ne 130 ]; then
    die "'docker compose logs' failed (exit ${rc})"
  fi
  return 0
}

cmd_health() {
  require_cmd curl jq
  local rc=0
  probe_health || rc=$?
  if [ "${rc}" -eq 0 ]; then
    log "${BASE_URL} — ${HEALTH_REASON}"
    return 0
  fi
  err "${BASE_URL} — ${HEALTH_REASON}"
  exit "${EXIT_FAILURE}"
}

cmd_clean() {
  require_docker
  log "removing containers, networks, volumes and locally built images for"
  log "project '${COMPOSE_PROJECT}' only"
  # Everything below is confined by --project-name. There is deliberately no
  # `docker system prune` here: this must never touch images, volumes or
  # containers belonging to other projects on the developer's machine.
  compose down --volumes --remove-orphans --rmi local \
    || die "'docker compose down --volumes' failed"
  log "clean complete — ${REPO_ROOT}/backups was not touched"
}

# ---------------------------------------------------------------------------
# Argument parsing and dispatch
# ---------------------------------------------------------------------------
require_value() {
  # require_value <flag> <remaining-arg-count>
  [ "$2" -ge 2 ] || usage_error "option '$1' requires a value"
}

validate_positive_int() {
  # validate_positive_int <flag> <value>
  case "$2" in
    '' | *[!0-9]*) usage_error "option '$1' expects a positive integer, got '$2'" ;;
    *)             : ;;  # all digits: fall through to the range check below
  esac
  [ "$2" -gt 0 ] || usage_error "option '$1' expects a positive integer, got '$2'"
}

main() {
  local subcommand

  # --help is answered before any dependency or daemon check, so it works on a
  # machine with no Docker at all.
  case "${1:-}" in
    -h | --help | help)
      usage
      exit "${EXIT_OK}"
      ;;
    '')
      err "no subcommand given"
      usage >&2
      exit "${EXIT_USAGE}"
      ;;
    *)
      : ;;  # a real subcommand; validated in the dispatch case below
  esac

  subcommand="$1"
  shift

  while [ "$#" -gt 0 ]; do
    case "$1" in
      -h | --help)
        usage
        exit "${EXIT_OK}"
        ;;
      --base-url)
        require_value "$1" "$#"
        BASE_URL="$2"
        shift 2
        ;;
      --base-url=*)
        BASE_URL="${1#*=}"
        shift
        ;;
      --timeout)
        require_value "$1" "$#"
        validate_positive_int "$1" "$2"
        HEALTH_TIMEOUT="$2"
        TIMEOUT_GIVEN=1
        shift 2
        ;;
      --timeout=*)
        validate_positive_int '--timeout' "${1#*=}"
        HEALTH_TIMEOUT="${1#*=}"
        TIMEOUT_GIVEN=1
        shift
        ;;
      --no-follow)
        LOG_FOLLOW=0
        LOG_FOLLOW_GIVEN=1
        shift
        ;;
      --follow)
        LOG_FOLLOW=1
        LOG_FOLLOW_GIVEN=1
        shift
        ;;
      --tail)
        require_value "$1" "$#"
        [ "$2" = 'all' ] || validate_positive_int "$1" "$2"
        LOG_TAIL="$2"
        LOG_TAIL_GIVEN=1
        shift 2
        ;;
      --tail=*)
        [ "${1#*=}" = 'all' ] || validate_positive_int '--tail' "${1#*=}"
        LOG_TAIL="${1#*=}"
        LOG_TAIL_GIVEN=1
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
        usage_error "unexpected argument: $1"
        ;;
    esac
  done

  [ "$#" -eq 0 ] || usage_error "unexpected argument: $1"

  # Reject options that are meaningless for the chosen subcommand rather than
  # silently ignoring them.
  if [ "${LOG_TAIL_GIVEN}" -eq 1 ] && [ "${subcommand}" != 'logs' ]; then
    usage_error "--tail is only valid for the 'logs' subcommand"
  fi
  if [ "${LOG_FOLLOW_GIVEN}" -eq 1 ] && [ "${subcommand}" != 'logs' ]; then
    usage_error "--follow/--no-follow is only valid for the 'logs' subcommand"
  fi
  if [ "${TIMEOUT_GIVEN}" -eq 1 ] \
     && [ "${subcommand}" != 'start' ] && [ "${subcommand}" != 'restart' ]; then
    usage_error "--timeout is only valid for the 'start' and 'restart' subcommands"
  fi

  [ -n "${BASE_URL}" ] || usage_error "--base-url must not be empty"
  # Normalise so that ".../health" never becomes ".../ /health" or "//health".
  BASE_URL="${BASE_URL%/}"

  case "${subcommand}" in
    build)   cmd_build ;;
    start)   cmd_start ;;
    stop)    cmd_stop ;;
    restart) cmd_stop; cmd_start ;;
    test)    cmd_test ;;
    logs)    cmd_logs ;;
    health)  cmd_health ;;
    clean)   cmd_clean ;;
    *)       usage_error "unknown subcommand: '${subcommand}'" ;;
  esac
}

main "$@"
