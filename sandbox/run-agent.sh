#!/usr/bin/env bash
# CLI-agnostic remediation sandbox wrapper.
#
# Contract: clone the repository, let a coding agent fix the bug, verify the fix
# with the repository's own test suite, and push a branch only when the suite is
# green. The agent never decides whether its fix is accepted — the tests do —
# and the agent cannot touch the build files or existing tests that would
# decide it (those changes are rejected before verification).
#
# Inputs (environment):
#   REPO_URL             https URL of the repository to fix (required)
#   BASE_BRANCH          branch to base the fix on (default: repository default)
#   GITHUB_TOKEN         token with contents:write on REPO_URL (required)
#   REMEDIATION_PROMPT   remediation context for the agent (required)
#   CODING_AGENT         opencode | pi | bob (default: opencode)
#   MODEL                model id passed through to the coding agent
#   LLM_BASE_URL         OpenAI-compatible endpoint for the coding agent
#   LLM_API_KEY          API key for that endpoint
#   COMMIT_TITLE         subject line for the fix commit
#   ROOT_CAUSE           root cause from the canary analysis, used as commit body
#   AGENT_TIMEOUT_SECONDS budget for the coding agent alone (default: 1200)
#   TEST_CMD             verification command (default: ./mvnw test)
#   WORKDIR              workspace (default: /workspace, must be writable)
#
# Output: a single JSON object on stdout, the last line of the log.
#
# Security note: the GitHub token is unset while the coding agent runs and
# re-injected only for the clone and the push. This stops accidental
# discovery (env dumps, logs); it is not a security boundary — the agent
# shares the UID and could recover it from git if it tried.
set -euo pipefail

REPO_URL=${REPO_URL:?REPO_URL is required}
GITHUB_TOKEN=${GITHUB_TOKEN:?GITHUB_TOKEN is required}
REMEDIATION_PROMPT=${REMEDIATION_PROMPT:?REMEDIATION_PROMPT is required}
CODING_AGENT=${CODING_AGENT:-opencode}
MODEL=${MODEL:-}
AGENT_TIMEOUT_SECONDS=${AGENT_TIMEOUT_SECONDS:-1200}
TEST_CMD=${TEST_CMD:-if ./mvnw -v >/dev/null 2>&1; then ./mvnw test; else mvn test; fi}
WORKDIR=${WORKDIR:-/workspace}

# Restricted pods run as a random UID with no passwd entry, so HOME is unset
# and the toolchain (opencode config, ~/.m2) writes to the read-only root.
# Point HOME at the writable agent-home volume; local runs keep their HOME.
export HOME=${HOME:-/home/sandbox}
BRANCH_PREFIX=${BRANCH_PREFIX:-fix/k8s-issue}

START=$(date +%s)
log() { echo "[run-agent] $*" >&2; }

result() {
  # Emit the result JSON on stdout and exit. Everything else goes to stderr.
  local status=$1 branch=$2 files=$3 test_summary=$4
  local duration=$(( $(date +%s) - START ))
  printf '{"status":"%s","branch":"%s","filesChanged":%s,"testSummary":"%s","model":"%s","durationSeconds":%d}\n' \
    "$status" "$branch" "$files" "$test_summary" "$MODEL" "$duration"
  exit 0
}

fail() { result "$1" "" 0 ""; }

strip_ansi() { sed 's/\x1b\[[0-9;]*[a-zA-Z]//g'; }

mkdir -p "$WORKDIR"
REPO_DIR="$WORKDIR/repo"
rm -rf "$REPO_DIR"

# Clone with the token embedded, then strip it from the git config so it never
# appears in agent-visible state or logs.
AUTH_URL=$(echo "$REPO_URL" | sed "s#https://#https://x-access-token:${GITHUB_TOKEN}@#")
if [[ -n "${BASE_BRANCH:-}" ]]; then
  CLONE=(clone -q --branch "$BASE_BRANCH")
else
  CLONE=(clone -q)
fi
git "${CLONE[@]}" "$AUTH_URL" "$REPO_DIR" || fail agent_error
git -C "$REPO_DIR" remote set-url origin "$REPO_URL"
cd "$REPO_DIR"

git config user.name "remediation-agent"
git config user.email "remediation-agent@cluster.local"
BRANCH="$BRANCH_PREFIX-$(openssl rand -hex 6)"
git checkout -q -b "$BRANCH"
BASE_COMMIT=$(git rev-parse HEAD)

PROMPT_FILE="$WORKDIR/prompt.md"
cat > "$PROMPT_FILE" <<EOF
Fix the bug described below in this repository.

$REMEDIATION_PROMPT

## Rules
- Make the minimal change that fixes the bug. Do not refactor unrelated code.
- Do not modify build files (pom.xml, mvnw, .mvn), CI configuration, or dependencies.
- You may add new test files, but do not change existing tests.
- Run the test suite and make sure it passes before you finish.
- Summarize your change in one sentence when you are done.
EOF

# --- Configure the coding agent's LLM endpoint, when provided ---------------
configure_llm() {
  if [[ -z ${LLM_BASE_URL:-} || -z ${LLM_API_KEY:-} ]]; then
    return 0
  fi
  case "$CODING_AGENT" in
    opencode)
      mkdir -p "$HOME/.config/opencode"
      cat > "$HOME/.config/opencode/opencode.json" <<EOF
{
  "provider": {
    "sandbox-llm": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Sandbox LLM",
      "options": { "baseURL": "$LLM_BASE_URL", "apiKey": "$LLM_API_KEY" },
      "models": { "${MODEL:-default}": { "name": "${MODEL:-default}" } }
    }
  }
}
EOF
      ;;
    *)
      # other agents configure their endpoint themselves or use image defaults
      ;;
  esac
}

# --- Run the coding agent ---------------------------------------------------
# Each supported CLI gets one dispatch case. The agent works directly in the
# repository checkout; how it iterates is its business.
run_agent() {
  case "$CODING_AGENT" in
    opencode)
      opencode run --standalone ${MODEL:+--model "sandbox-llm/$MODEL"} --format json --auto "$(cat "$PROMPT_FILE")"
      ;;
    pi)
      pi --prompt "$(cat "$PROMPT_FILE")" ${MODEL:+--model "$MODEL"}
      ;;
    bob)
      bob --message "$(cat "$PROMPT_FILE")" ${MODEL:+--model "$MODEL"}
      ;;
    *)
      log "unknown coding agent: $CODING_AGENT"
      return 1
      ;;
  esac
}

configure_llm
PUSH_TOKEN=$GITHUB_TOKEN
# timeout(1) runs an executable, not a shell function: export both the
# function and the variables it reads, then run it through bash -c
export -f run_agent
export CODING_AGENT MODEL PROMPT_FILE
if ! timeout "$AGENT_TIMEOUT_SECONDS" bash -c run_agent > "$WORKDIR/agent.log" 2>&1; then
  log "coding agent exited non-zero or timed out after ${AGENT_TIMEOUT_SECONDS}s, see agent.log"
  tail -50 "$WORKDIR/agent.log" | strip_ansi >&2 || true
  fail agent_error
fi
unset GITHUB_TOKEN LLM_API_KEY

# --- Reject tampering with the verification harness --------------------------
# The agent may change production code and add NEW tests, but not build files
# or existing tests: those decide whether the fix is accepted.
if [[ -z $(git status --porcelain) ]]; then
  log "agent made no changes"
  fail no_changes
fi

while IFS= read -r changed; do
  if [[ $changed == pom.xml || $changed == mvnw* || $changed == .mvn/* || $changed == */pom.xml || $changed == .github/* ]]; then
    log "agent modified build or CI file: $changed — rejecting"
    fail tests_failed
  fi
  if [[ $changed == src/test/* || $changed == */src/test/* ]] && git cat-file -e "$BASE_COMMIT:$changed" 2>/dev/null; then
    log "agent modified existing test: $changed — rejecting"
    fail tests_failed
  fi
done < <(git diff --name-only "$BASE_COMMIT")

FILES_CHANGED=$(git status --porcelain | wc -l | tr -d ' ')
log "agent changed $FILES_CHANGED file(s); verifying with: $TEST_CMD"

# --- Verify independently of the agent --------------------------------------
TEST_LOG="$WORKDIR/test.log"
if ! (cd "$REPO_DIR" && bash -c "$TEST_CMD") > "$TEST_LOG" 2>&1; then
  log "test suite failed, nothing pushed"
  grep -E "Tests run:|BUILD FAILURE|Error" "$TEST_LOG" | tail -5 >&2 || true
  result tests_failed "$BRANCH" "$FILES_CHANGED" ""
fi

TEST_SUMMARY=$(grep -oE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+(, Skipped: [0-9]+)?" "$TEST_LOG" | tail -1 || true)
TEST_SUMMARY=${TEST_SUMMARY:-tests passed (summary unavailable)}

# --- Push only what the tests approved --------------------------------------
git add -A
git commit -q -m "$COMMIT_TITLE" -m "${ROOT_CAUSE:-Automated fix from canary analysis}

Verified: $TEST_CMD ($TEST_SUMMARY)"
GITHUB_TOKEN=$PUSH_TOKEN git push -q "$AUTH_URL" "HEAD:$BRANCH" || fail agent_error

log "pushed $BRANCH ($FILES_CHANGED files, tests green)"
result success "$BRANCH" "$FILES_CHANGED" "$TEST_SUMMARY"
