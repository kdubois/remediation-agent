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
kubernetes-agent (LangChain4j A2A client agent, async, activation-gated)
        │ A2A JSON-RPC: SendMessage → Task → artifact
        ▼
remediation-agent (A2A server, agent card, remediate skill)
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
`restricted-v2` SCC with a read-only root filesystem (writable only via sized
emptyDir mounts for `/workspace`, `/home/sandbox` and `/tmp`), no
service-account token, zero RBAC, and a namespace EgressFirewall that allows
only GitHub, Maven Central, npm, DNS and the LLM endpoint. The GitHub token it
receives is fine-grained to a single repository with `contents:write` and
`pull_requests:write` — it can push branches, open, force-push and delete
branches in that repository, and nothing else. The test suite, not the agent,
decides whether a fix ships, and the wrapper rejects runs that modify build
files or existing tests. A human reviews every PR; auto-merge is deliberately
deferred.

Hardware isolation via `kata-remote` peer pods is not available on the current
cluster: it needs the OpenShift sandboxed containers operator with peer pods
configured for AWS and TCP 15150/9000 open in the VPC security groups, which
the cluster's AWS credentials cannot do. Enabling it later means installing the
operator, opening the ports, and adding a `runtimeClassName` to the sandbox pod
template in `SandboxManifest` — a code change and image rebuild, not a config
flip.

## Deployment

Deployed through GitOps in the [progressive-delivery](../progressive-delivery)
repository (`system/remediation-agent/`): the service runs in `openshift-gitops`
next to the kubernetes-agent that calls it, sandbox pods run in the dedicated
`remediation` namespace with the EgressFirewall described above. The
`remediation-agent` secret (`github_token`, `llm_api_key`) is created manually in
both namespaces — see the kustomization note.

## Swapping the coding agent

Each coding agent is an image variant built from the same wrapper contract — the
verification (test suite green) is objective and CLI-agnostic:

```bash
cd sandbox
docker build --build-arg CODING_AGENT=opencode -t ghcr.io/kdubois/remediation-sandbox:opencode .
docker push ghcr.io/kdubois/remediation-sandbox:opencode
```

The image is not built by CI (the workflow builds the service image); build and
push it manually when the wrapper or toolchain changes. The service selects the
image through configuration, so demoing a different agent is a config change,
not a rebuild.

## The A2A contract

The service is a real A2A agent: agent card at `/.well-known/agent-card.json`,
JSON-RPC `SendMessage` (v1.0 protocol) at `/`. The message text is a JSON
`RemediationRequest`; the task completes with the JSON `RemediationResponse` as
its artifact. The sandbox run takes minutes — the executor reports the task as
working and completes it on a virtual thread, so the A2A request thread never
blocks.

## Status

Phase 1 (sandbox prototype) is verified against the demo NPE scenario: opencode
fixed `MetricsResource.getStatus()`, added a regression test, and the wrapper
pushed the branch only after the suite passed. The A2A service (agent card,
JSON-RPC task lifecycle with streaming, sandbox manifest with the isolation
profile, evidence-based PR/issue creation) is unit- and integration-tested and
deployed through GitOps. The live end-to-end path — sandbox in the cluster,
A2A round trip with the real streaming task — is exercised by the demo
scenarios. See [AGENTS.md](AGENTS.md) for the development guide.
