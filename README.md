# remediation-agent

A Quarkus A2A service that remediates failed canary rollouts: it runs a swappable
coding agent (opencode, pi, Bob) inside an isolated sandbox, verifies the fix with
the repository's test suite, and opens a GitHub PR carrying the verification
evidence.

Part of the [argo-rollouts AI progressive delivery workspace](..). The
`kubernetes-agent` decides promote/rollback; when it rolls back a code bug it
delegates the fix here over A2A, the same way the Argo Rollouts plugin delegates
analysis to it.

## How it works

```
kubernetes-agent ──A2A──▶ remediation-agent
                            │ creates a Sandbox CR (isolated pod)
                            ▼
                   ┌─────────────────────────────────────┐
                   │ sandbox: run-agent.sh               │
                   │  clone repo                         │
                   │  coding agent fixes the bug         │
                   │  ./mvnw test (independent gate)     │
                   │  push fix/k8s-issue-* branch        │
                   └─────────────────────────────────────┘
                            │ watches the sandbox, reads the result JSON
                            ▼
                   green → GitHub PR with verification evidence
                   red   → GitHub issue with the failure reason
```

The agent inside the sandbox is untrusted by design. It runs under the
`restricted-v2` SCC with a read-only root filesystem, no service-account token,
zero RBAC, and a namespace EgressFirewall that allows only GitHub, Maven Central,
DNS and the LLM endpoint. The GitHub token it receives is fine-grained to a single
repository with `contents:write` — it can push a branch and nothing else. The test
suite, not the agent, decides whether a fix ships; a human reviews the PR.
Auto-merge is deliberately deferred.

Hardware isolation via `kata-remote` peer pods is designed in but not active on
the current cluster — it needs TCP 15150/9000 open in the VPC security groups,
which the cluster's AWS credentials cannot do. Flipping the Sandbox CR's
`runtimeClassName` activates it once that changes.

## Swapping the coding agent

Each coding agent is an image variant built from the same wrapper contract — the
verification (test suite green) is objective and CLI-agnostic:

```bash
cd sandbox
docker build --build-arg CODING_AGENT=opencode -t quay.io/you/remediation-sandbox:opencode .
```

The service selects the image through configuration, so demoing a different agent
is a config change, not a rebuild.

## Status

Phase 1 (sandbox prototype) is complete and verified against the demo NPE
scenario: opencode fixed `MetricsResource.getStatus()`, added a regression test,
and the wrapper pushed the branch only after the suite passed. The A2A service is
under development — see [AGENTS.md](AGENTS.md) for the development guide.
