# Remediation Agent Development Guide

Quarkus-based A2A service that remediates failed canary rollouts: it runs a swappable
coding agent (opencode, pi, Bob) inside an isolated sandbox, verifies the fix with the
repository's test suite, and opens a GitHub PR carrying the verification evidence.
When the failure is operational rather than a patchable code defect, it opens an issue.
This is an experimental project: prioritize simplicity over backwards compatibility.

Do not create summary or migration documents. If a change is made, update the relevant
code and documentation in place.

## Overview

**Stack:** Quarkus 3.x, A2A Java SDK, Fabric8 Kubernetes client, Agent Sandbox
(`Sandbox` CRDs, `agents.x-k8s.io/v1alpha1`), GitHub REST API

**Architecture:**

```
analysis agent (decides promote=false, e.g. kubernetes-agent)
  └─ A2A client agent (async, activation-gated) → A2A JSON-RPC
       └─ remediation-agent (A2A server: agent card, remediate skill)
            └─ Sandbox CR (isolated pod, restricted security profile, no SA token)
                 └─ sandbox/run-agent.sh: clone → coding agent → ./mvnw test → push branch
            └─ watches sandbox → opens PR with verification evidence, or a GitHub issue
```

The service is independent of any particular caller. The reference deployment has
`kubernetes-agent` call it after a rollback, but anything that speaks the A2A contract
below can.

**A2A protocol.** A2A Java SDK 1.4.x (the generation the LangChain4j A2A client
speaks). The agent card is served at `/.well-known/agent-card.json`; JSON-RPC
`SendMessage` at `/` with the `A2A-Version: 1.0` header. Message text is a JSON
`RemediationRequest`; the response artifact is a JSON `RemediationResponse`.
Long runs use the task lifecycle: the executor marks the task working and
completes it on a virtual thread when the sandbox finishes.

**Isolation model.** The coding agent runs untrusted, LLM-generated commands inside a
sandbox pod: read-only root filesystem, no service-account token, zero RBAC, and an
egress allow-list limited to GitHub, Maven Central, npm, DNS and the LLM endpoint.
The pod security settings target the Kubernetes `restricted` Pod Security Standard.
The GitHub token pushed into the sandbox is fine-grained, scoped to a single repository
with `contents:write`: it can push, force-push and delete branches in that repository,
open PRs, and nothing else. The
agent itself runs with `GITHUB_TOKEN` and the LLM key stripped from its environment:
the LLM key reaches it through an on-disk `opencode.json` written before launch, and
the push step re-injects the saved token. The residual `/proc/1/environ` exposure is
accepted, because it cannot be closed while the push and the agent share one container;
the egress allow-list is the compensating control. The
test suite, not the agent, decides whether a fix is accepted. The PR is reviewed by a
human; auto-merge is deliberately deferred.

**Sandbox runtime.** Sandboxes run on the default container runtime (`runc`).
Hardware isolation via a `RuntimeClass` (gVisor or Kata) is optional and requires
matching cluster support and node images; adding `runtimeClassName` to the Sandbox pod
template is a code change in `SandboxManifest`. The reference deployment runs on
OpenShift with the Red Hat build of Agent Sandbox, which is installed as an operator;
on plain Kubernetes, install the upstream controller with the upstream release
manifest. See the [Agent Sandbox docs](https://github.com/kubernetes-sigs/agent-sandbox).

## Running and deploying standalone

The service needs a Kubernetes cluster (1.29 or later, because the Sandbox CRD uses CEL
validation), the Agent Sandbox controller, the sandbox namespaces, and two Secrets that
hold the GitHub token and the LLM key. `deployment/` holds kustomize manifests that
create everything except the Secrets.

```bash
# Controller (pin a release)
kubectl apply -f https://github.com/kubernetes-sigs/agent-sandbox/releases/download/v1.0.2/sandbox-with-extensions.yaml

# Secrets: one in the service namespace, one in the sandbox namespace
kubectl create secret generic remediation-agent -n remediation-system \
  --from-literal=github_token=... --from-literal=llm_api_key=...
kubectl create secret generic remediation-agent -n remediation \
  --from-literal=github_token=... --from-literal=llm_api_key=...

$EDITOR deployment/configmap.yaml   # allowed repos, sandbox image, model, endpoint
kubectl apply -k deployment/
```

`progressive-delivery` is one way to instantiate the whole stack (Argo CD, this
service, the analysis agent, and a demo application) with the reference OpenShift
configuration. Nothing in this repository depends on it.

## Project Layout

```
sandbox/
  run-agent.sh    # CLI-agnostic wrapper: clone → agent → verify → push → result JSON
  Dockerfile      # sandbox image: ubi9-minimal, JDK 25, Maven, git + coding agent
  prompt.md       # remediation prompt template passed to the coding agent
deployment/       # standalone kustomize manifests (Secrets excluded)
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
adding a dispatch case in `run-agent.sh` and an image variant; nothing else changes.

## Development

```bash
# Local sandbox prototype against a real repo:
REPO_URL=https://github.com/kdubois/argo-rollouts-aiops-dashboard \
GITHUB_TOKEN=... CODING_AGENT=opencode MODEL=... ./sandbox/run-agent.sh

./mvnw test
```

`./mvnw quarkus:dev` starts the A2A server against the current kubeconfig. It still
creates Sandbox resources, so the controller and both namespaces have to exist.

## Code Standards

- Clean, idiomatic Quarkus code. No overengineering: the simplest solution that works.
- No repetition, no boilerplate. Prefer records over object mappers or manual DTO
  mapping; use Java 25 idioms (records, pattern matching, sealed types, text blocks)
  where they fit.
- Records for DTOs: no mutable state in data contracts.
- Log at `debug` for per-operation detail, `info` for decisions, `error` for failures.
- Use `Log.infof` (parametrized) instead of string concatenation for logging.

## Feature Checklist

- [ ] Simple, clean, concise code
- [ ] Error handling and logging are appropriate
- [ ] Tests added and passing
- [ ] `README.md` updated if behaviour changed
- [ ] No compiler warnings

## Resources

[Agent Sandbox](https://github.com/kubernetes-sigs/agent-sandbox) |
[Kubernetes Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/) |
[A2A Java SDK](https://github.com/a2asdk/a2a-java-sdk) |
[Quarkus](https://quarkus.io/guides/)
