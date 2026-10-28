#!/usr/bin/env bash
# CLI-agnostic remediation sandbox wrapper.
#
# Contract: clone the repository, let a coding agent fix the bug, verify the fix
# with the repository's own test suite, and push a branch only when the suite is
# green. The agent never decides whether its fix is accepted — the tests do.
#
# Inputs (environment):
#   REPO_URL            https URL of the repository to fix (required)
#   BASE_BRANCH         branch to base the fix on (default: repository default)
#   GITHUB_TOKEN        token with contents:write on REPO_URL (required)
#   REMEDIATION_PROMPT  remediation context for the agent (required)
#   CODING_AGENT        opencode | pi | bob (default: opencode)
#   MODEL               model id passed through to the coding agent
#   TEST_CMD            verification command (default: ./mvnw -q test)
#   WORKDIR             workspace (default: /workspace, must be writable)
#
# Output: a single JSON object on stdout, the last line of the log.
set -uo pipefail

REPO_URL=${REPO_URL:?REPO_URL is required}
GITHUB_TOKEN=${GITHUB_TOKEN:?GITHUB_TOKEN is required}
REMEDIATION_PROMPT=${REMEDIATION_PROMPT:?REMEDIATION_PROMPT is required}
CODING_AGENT=${CODING_AGENT:-opencode}
MODEL=${MODEL:-}
TEST_CMD=${TEST_CMD:-./mvnw test}
WORKDIR=${WORKDIR:-/workspace}
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

mkdir -p "$WORKDIR" || fail agent_error
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
if ! git "${CLONE[@]}" "$AUTH_URL" "$REPO_DIR" 2>/dev/null; then
  log "clone failed"
  fail agent_error
fi
git -C "$REPO_DIR" remote set-url origin "$REPO_URL"
cd "$REPO_DIR" || fail agent_error

git config user.name "remediation-agent"
git config user.email "remediation-agent@cluster.local"
BRANCH="$BRANCH_PREFIX-$(date +%s | tail -c 5)$(openssl rand -hex 2 2>/dev/null | head -c 4)"
git checkout -q -b "$BRANCH"

PROMPT_FILE="$WORKDIR/prompt.md"
cat > "$PROMPT_FILE" <<EOF
Fix the bug described below in this repository.

$REMEDIATION_PROMPT

## Rules
- Make the minimal change that fixes the bug. Do not refactor unrelated code.
- Do not modify build files, CI configuration, or dependencies.
- Run the test suite and make sure it passes before you finish.
- Summarize your change in one sentence when you are done.
EOF

# --- Run the coding agent ---------------------------------------------------
# Each supported CLI gets one dispatch case. The agent works directly in the
# repository checkout; how it iterates is its business.
run_agent() {
  case "$CODING_AGENT" in
    opencode)
      opencode run --standalone ${MODEL:+--model "$MODEL"} --auto "$(cat "$PROMPT_FILE")"
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

if ! run_agent > "$WORKDIR/agent.log" 2>&1; then
  log "coding agent exited non-zero, see agent.log"
  tail -50 "$WORKDIR/agent.log" >&2
  fail agent_error
fi

# --- Verify independently of the agent --------------------------------------
if [[ -z $(git status --porcelain) ]]; then
  log "agent made no changes"
  fail no_changes
fi

FILES_CHANGED=$(git status --porcelain | wc -l | tr -d ' ')
log "agent changed $FILES_CHANGED file(s); verifying with: $TEST_CMD"

TEST_LOG="$WORKDIR/test.log"
if ! (cd "$REPO_DIR" && eval "$TEST_CMD") > "$TEST_LOG" 2>&1; then
  log "test suite failed, nothing pushed"
  grep -E "Tests run:|BUILD FAILURE|ERROR" "$TEST_LOG" | tail -5 >&2
  result tests_failed "$BRANCH" "$FILES_CHANGED" ""
fi

TEST_SUMMARY=$(grep -oE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+(, Skipped: [0-9]+)?" "$TEST_LOG" | tail -1)

# --- Push only what the tests approved --------------------------------------
git add -A
git commit -q -m "Fix: remediation from canary analysis

$(head -c 500 "$WORKDIR/agent.log" | grep -v '^$' | tail -3)

Verified: $TEST_CMD ($TEST_SUMMARY)"
if ! git push -q "$AUTH_URL" "HEAD:$BRANCH" 2>/dev/null; then
  log "push failed"
  fail agent_error
fi

log "pushed $BRANCH ($FILES_CHANGED files, tests green)"
result success "$BRANCH" "$FILES_CHANGED" "${TEST_SUMMARY:-tests passed}"
