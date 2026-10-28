# Remediation Agent Development Guide

Quarkus-based A2A service that remediates failed canary rollouts: it runs a swappable
coding agent (opencode, pi, Bob) inside an isolated sandbox, verifies the fix with the
repository's test suite, and opens a GitHub PR carrying the verification evidence.
This is an experimental project — prioritize simplicity over backwards compatibility.

Do not create summary or migration documents. If a change is made, update the relevant
code and documentation in place.

## Overview

**Stack:** Quarkus 3.x, A2A Java SDK, Fabric8 Kubernetes client, Red Hat build of
Agent Sandbox (`Sandbox` CRDs), GitHub REST API

**Architecture:**

```
kubernetes-agent (analysis decides promote=false)
  └─ LangChain4j A2A client agent (async, activation-gated) → A2A JSON-RPC
       └─ remediation-agent (A2A server: agent card, remediate skill)
            └─ Sandbox CR (isolated pod, restricted-v2, no SA token, egress allow-list)
                 └─ sandbox/run-agent.sh: clone → coding agent → ./mvnw test → push branch
            └─ watches sandbox → opens PR with verification evidence, or a GitHub issue
```

**A2A protocol.** A2A Java SDK 1.3.x (the generation the LangChain4j A2A client
speaks). The agent card is served at `/.well-known/agent-card.json`; JSON-RPC
`SendMessage` at `/` with the `A2A-Version: 1.0` header. Message text is a JSON
`RemediationRequest`; the response artifact is a JSON `RemediationResponse`.
Long runs use the task lifecycle: the executor marks the task working and
completes it on a virtual thread when the sandbox finishes.

**Isolation model.** The coding agent runs untrusted, LLM-generated commands inside a
sandbox pod: `restricted-v2` SCC, read-only root filesystem, no service-account token,
zero RBAC, and a namespace EgressFirewall allowing only GitHub, Maven Central, DNS and
the LLM endpoint. The GitHub token pushed into the sandbox is fine-grained, scoped to a
single repository with `contents:write` — it can push, force-push and delete branches in that repository, open PRs, and nothing else. The
test suite, not the agent, decides whether a fix is accepted. The PR is reviewed by a
human; auto-merge is deliberately deferred.

**Sandbox runtime.** Sandboxes run on the default container runtime (`runc`).
Hardware isolation via `runtimeClassName: kata-remote` (peer pods) requires TCP 15150
and 9000 open in the cluster VPC security groups — this cluster's AWS credentials
cannot open them, so the flip is documented but not active. See the Agent Sandbox
docs: https://docs.redhat.com/en/documentation/openshift_sandboxed_containers/1.12/

## Project Layout

```
sandbox/
  run-agent.sh    # CLI-agnostic wrapper: clone → agent → verify → push → result JSON
  Dockerfile      # sandbox image: ubi9-minimal, JDK 25, Maven, git + coding agent
  prompt.md       # remediation prompt template passed to the coding agent
src/main/java/dev/kevindubois/remediation/
  a2a/
    RemediationAgentCard.java      # @PublicAgentCard producer (remediate skill)
    RemediationAgentExecutor.java  # A2A JSON-RPC → RemediationService, async completion
  RemediationRequest.java          # record: repoUrl, baseBranch, issueCategory, ...
  RemediationResponse.java         # record: status, prLink/issueLink, evidence
  RemediationService.java          # orchestration: allow-list, sandbox, PR/issue
  SandboxRunner.java               # Sandbox CR lifecycle + result from pod log
  SandboxManifest.java             # hardened Sandbox CR builder (isolation profile)
  SandboxConfig.java               # sandbox runtime config record
  GitHubClient.java                # minimal GitHub REST client (PR + issue)
```

## The wrapper contract

`run-agent.sh` is the only entry point inside the sandbox. Inputs come from
environment variables (`REPO_URL`, `BASE_BRANCH`, `GITHUB_TOKEN`, `CODING_AGENT`,
`MODEL`, `REMEDIATION_PROMPT`); the result is a single JSON object on stdout:

```json
{"status": "success", "branch": "fix/k8s-issue-ab12cd", "filesChanged": 1,
 "testSummary": "Tests run: 91, Failures: 0, Errors: 0", "model": "..."}
```

`status` is one of `success`, `no_changes`, `tests_failed`, `agent_error`. The
verification is objective: whatever the agent did, the wrapper re-runs the test suite
afterwards and only pushes a branch when it exits green. Adding a coding agent means
adding a dispatch case in `run-agent.sh` and an image variant — nothing else changes.

## Development

```bash
# Local sandbox prototype against a real repo:
REPO_URL=https://github.com/kdubois/argo-rollouts-quarkus-demo \
GITHUB_TOKEN=... CODING_AGENT=opencode MODEL=... ./sandbox/run-agent.sh

mvn test
```

## Code Standards

- Clean, idiomatic Quarkus code. No overengineering — the simplest solution that works.
- No repetition, no boilerplate. Prefer records over object mappers or manual DTO
  mapping; use Java 25 idioms (records, pattern matching, sealed types, text blocks)
  where they fit.
- Records for DTOs — no mutable state in data contracts.
- Log at `debug` for per-operation detail, `info` for decisions, `error` for failures.
- Use `Log.infof` (parametrized) instead of string concatenation for logging.

## Feature Checklist

- [ ] Simple, clean, concise code
- [ ] Error handling and logging are appropriate
- [ ] Tests added and passing
- [ ] `README.md` updated if behaviour changed
- [ ] No compiler warnings

## Resources

[Red Hat build of Agent Sandbox](https://docs.redhat.com/en/documentation/openshift_sandboxed_containers/1.12/html/deploying_red_hat_build_of_agent_sandbox/) |
[A2A Java SDK](https://github.com/a2asdk/a2a-java-sdk) |
[Quarkus](https://quarkus.io/guides/)
