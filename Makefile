# Makefile — thin, discoverable front door for the ops scripts.
#
# Deliberately contains no operational logic. Every target delegates to a
# script under ops/, which is where error handling, timeouts, retries and
# idempotency live. If a rule here grows a conditional or a curl call, it
# belongs in ops/ instead.

# Fail loudly rather than half-way through a recipe.
SHELL := /usr/bin/env bash
.SHELLFLAGS := -Eeuo pipefail -c

# Resolve paths from this Makefile, not the caller's cwd, so `make -f` and
# `make -C` behave identically. The realpath keeps output readable when the
# repo path contains spaces or symlinks.
MAKEFILE_PATH := $(abspath $(lastword $(MAKEFILE_LIST)))
REPO_ROOT     := $(patsubst %/,%,$(dir $(MAKEFILE_PATH)))
OPS_DIR       := $(REPO_ROOT)/ops

RUN     := $(OPS_DIR)/run.sh
BACKUP  := $(OPS_DIR)/backup.sh
RESTORE := $(OPS_DIR)/restore.sh

# Pass-through knobs. Empty by default so the scripts keep their own defaults.
#   make start   TIMEOUT=240
#   make logs    TAIL=200
#   make backup  OUTPUT_DIR=/secure/backups
#   make restore FILE=backups/cases-....json DRY_RUN=1
BASE_URL   ?=
TIMEOUT    ?=
TAIL       ?=
OUTPUT_DIR ?=
FILE       ?=
DRY_RUN    ?=

BASE_URL_ARG   := $(if $(BASE_URL),--base-url $(BASE_URL),)
TIMEOUT_ARG    := $(if $(TIMEOUT),--timeout $(TIMEOUT),)
TAIL_ARG       := $(if $(TAIL),--tail $(TAIL),)
OUTPUT_DIR_ARG := $(if $(OUTPUT_DIR),--output-dir $(OUTPUT_DIR),)
DRY_RUN_ARG    := $(if $(DRY_RUN),--dry-run,)

.DEFAULT_GOAL := help

.PHONY: help build start stop restart test logs clean backup restore health

help: ## Show this help
	@printf '%s\n' \
	  'PV Case Review Platform — make targets' \
	  '' \
	  'Every target delegates to a script in ops/; run those directly for full' \
	  'flag documentation (e.g. ops/run.sh --help).' \
	  '' \
	  '  make build                     Build the backend image' \
	  '  make start                     Start the stack, wait for /health = "up"' \
	  '  make stop                      Stop the stack (safe if already stopped)' \
	  '  make restart                   stop, then start' \
	  '  make test                      Run the backend test suite (no Docker)' \
	  '  make logs                      Follow container logs' \
	  '  make health                    One-shot health probe' \
	  '  make clean                     Remove this project only; never backups/' \
	  '  make backup                    Snapshot all cases into backups/' \
	  '  make restore FILE=<path>       Restore cases from a backup file' \
	  '' \
	  'Variables:' \
	  '  BASE_URL=<url>                 Override the API base URL' \
	  '  TIMEOUT=<seconds>              start: how long to wait for health' \
	  '  TAIL=<n>                       logs: history lines before following' \
	  '  OUTPUT_DIR=<dir>               backup: where to write' \
	  '  FILE=<path>                    restore: backup file (required)' \
	  '  DRY_RUN=1                      restore: validate and report, write nothing' \
	  '' \
	  'Examples:' \
	  '  make start TIMEOUT=240' \
	  '  make logs TAIL=200' \
	  '  make restore FILE=$$(ls -t backups/cases-*.json | head -1) DRY_RUN=1' \
	  '  make backup BASE_URL=http://localhost:9090'

build: ## Build the backend image
	"$(RUN)" build

start: ## Start the stack and block until healthy
	"$(RUN)" start $(BASE_URL_ARG) $(TIMEOUT_ARG)

stop: ## Stop the stack (idempotent)
	"$(RUN)" stop

restart: ## Stop then start
	@# Delegated as one subcommand rather than declaring `stop start` as
	@# prerequisites, which `make -j` would be free to run concurrently.
	"$(RUN)" restart $(BASE_URL_ARG) $(TIMEOUT_ARG)

test: ## Run the backend test suite
	"$(RUN)" test

logs: ## Follow container logs
	"$(RUN)" logs $(TAIL_ARG)

health: ## One-shot health probe
	"$(RUN)" health $(BASE_URL_ARG)

clean: ## Remove this compose project's containers, networks, volumes, images
	"$(RUN)" clean

backup: ## Snapshot all cases into backups/
	"$(BACKUP)" $(BASE_URL_ARG) $(OUTPUT_DIR_ARG)

restore: ## Restore cases from FILE=<path> (add DRY_RUN=1 to preview)
	@if [ -z "$(FILE)" ]; then \
	  printf 'make restore requires FILE=<backup file>\n' >&2; \
	  printf '  e.g. make restore FILE=backups/cases-20260817T101421Z-4242.json\n' >&2; \
	  printf '       make restore FILE=$$(ls -t backups/cases-*.json | head -1) DRY_RUN=1\n' >&2; \
	  exit 2; \
	fi
	"$(RESTORE)" $(DRY_RUN_ARG) $(BASE_URL_ARG) -- "$(FILE)"
