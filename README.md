# remediation-agent

A Quarkus A2A service that remediates failed canary rollouts. It runs a swappable coding agent (opencode, pi, Bob) inside an isolated sandbox, verifies the fix with the repository's test suite, and opens a GitHub pull request carrying the verification evidence. When the failure is operational rather than a patchable code defect, it opens an issue instead.

An analysis agent such as [`kubernetes-agent`](https://github.com/kdubois/kubernetes-aiops-agent) decides promote or rollback; after a rollback of a code bug it delegates the fix here over A2A. The service is independent: anything that speaks the A2A contract below can call it.

## How it works

```
analysis agent (A2A client, async after rollback)
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

The sandbox pod is a standard Kubernetes `Sandbox` resource from the [Agent Sandbox](https://github.com/kubernetes-sigs/agent-sandbox) project. The coding agent inside it is untrusted: the pod runs with a read-only root filesystem, no service-account token, and no RBAC. The GitHub token is fine-grained to one repository with `contents:write` and `pull_requests:write`; the LLM key is written to an on-disk config rather than exported to the coding agent's environment. The test suite, not the agent, decides whether a fix ships, and the wrapper rejects runs that modify build files or existing tests. A human reviews every PR; auto-merge is deliberately deferred.

## Running it on Kubernetes

### Prerequisites

- Kubernetes 1.29 or later (the Sandbox CRD uses CEL validation)
- The [Agent Sandbox](https://github.com/kubernetes-sigs/agent-sandbox) controller
- A sandbox image with the coding agent and its toolchain; `ghcr.io/kdubois/remediation-sandbox:opencode` is the reference build
- A GitHub token scoped to one repository (`contents:write`, `pull_requests:write`)
- An API key for the sandbox coding agent's LLM

### 1. Install the Agent Sandbox controller

```bash
export VERSION="v1.0.2"   # pin a release
kubectl apply -f https://github.com/kubernetes-sigs/agent-sandbox/releases/download/${VERSION}/sandbox-with-extensions.yaml
kubectl get crd sandboxes.agents.x-k8s.io
```

On OpenShift, the Red Hat build of Agent Sandbox is installed as an operator instead; the reference deployment in `progressive-delivery` uses that path.

### 2. Build the service

```bash
./mvnw package
docker build -f src/main/docker/Dockerfile.jvm -t ghcr.io/kdubois/remediation-agent:latest .
docker push ghcr.io/kdubois/remediation-agent:latest
```

The prebuilt image is `ghcr.io/kdubois/remediation-agent:main`.

### 3. Create the secrets

The sandbox pods read two keys from the `remediation-agent` Secret in the sandbox namespace: `github_token` for pushing the branch and `llm_api_key` for the coding agent. The service itself reads `github_token` from its own namespace to open the PR or issue.

```bash
kubectl create namespace remediation-system
kubectl create namespace remediation

kubectl create secret generic remediation-agent \
  --namespace remediation-system \
  --from-literal=github_token=ghp_... \
  --from-literal=llm_api_key=sk-...

kubectl create secret generic remediation-agent \
  --namespace remediation \
  --from-literal=github_token=ghp_... \
  --from-literal=llm_api_key=sk-...
```

### 4. Configure and deploy

```bash
$EDITOR deployment/configmap.yaml   # allowed repos, sandbox image, model, endpoint
kubectl apply -k deployment/
```

The manifests create the service in `remediation-system`, a Role in `remediation` for creating Sandbox resources and reading pod logs, and a deny-all-ingress NetworkPolicy. The sandbox image is selected by `REMEDIATION_SANDBOX_IMAGE`; the model and endpoint by `REMEDIATION_SANDBOX_MODEL` and `REMEDIATION_SANDBOX_LLM_BASE_URL`.

### 5. Verify

```bash
kubectl get pods -n remediation-system
kubectl logs -f deployment/remediation-agent -n remediation-system
kubectl port-forward -n remediation-system svc/remediation-agent 8080:8080
curl http://localhost:8080/.well-known/agent-card.json
curl http://localhost:8080/q/health
```

### Running locally

`./mvnw quarkus:dev` starts the A2A server against your current kubeconfig. It still creates Sandbox resources, so the controller, both namespaces, and both Secrets have to exist. Create them with the commands above, then:

```bash
export GITHUB_TOKEN=ghp_...
./mvnw quarkus:dev
```

The sandbox reads `llm_api_key` from the Secret named by `REMEDIATION_SANDBOX_LLM_SECRET_NAME` (default `remediation-agent`), so the sandbox namespace needs that Secret even for a local run. The service itself only needs `GITHUB_TOKEN` in its environment.

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `REMEDIATION_ALLOWED_REPOS` | demo repo | Comma-separated repository URLs this service may touch |
| `REMEDIATION_SANDBOX_IMAGE` | `ghcr.io/kdubois/remediation-sandbox:opencode` | Sandbox image |
| `REMEDIATION_SANDBOX_CODING_AGENT` | `opencode` | Agent CLI inside the sandbox |
| `REMEDIATION_SANDBOX_MODEL` | unset | Model for the coding agent |
| `REMEDIATION_SANDBOX_LLM_BASE_URL` | unset | OpenAI-compatible endpoint for the coding agent |
| `REMEDIATION_SANDBOX_NAMESPACE` | `remediation` | Namespace for sandbox pods |
| `REMEDIATION_SANDBOX_TIMEOUT_SECONDS` | `2400` | Hard timeout for a run |
| `REMEDIATION_SANDBOX_KEEP_FAILED` | `false` | Keep failed sandboxes for inspection |
| `GITHUB_TOKEN` | required | Token for the target repository |

A repository not in `REMEDIATION_ALLOWED_REPOS` is rejected before any sandbox starts.

## Security

The service is unauthenticated by design. Run it on a ClusterIP Service and treat in-cluster callers as trusted; there is no JWT, OIDC, or request filter anywhere in the service. If that trust boundary does not fit, add authentication to the whole service rather than a single endpoint.

The sandbox namespace should have an egress allow-list: GitHub, Maven Central, npm, DNS, and the LLM endpoint, and nothing else. The generic `NetworkPolicy` in `deployment/` only denies ingress, because standard Kubernetes NetworkPolicy cannot match DNS names. Use an OpenShift `EgressFirewall`, a Cilium `toFQDNs` policy, or an equivalent for the egress side. The reference deployment uses `EgressFirewall`.

Hardware isolation through a `RuntimeClass` (gVisor or Kata) is optional. The reference cluster runs on the default runtime with pod hardening; adding `runtimeClassName` to the Sandbox pod template is a code change in `SandboxManifest`.

## The A2A contract

The service is an A2A agent: agent card at `/.well-known/agent-card.json`, JSON-RPC `SendMessage` (v1.0 protocol) at `/`. The message text is a JSON `RemediationRequest`; the task completes with the JSON `RemediationResponse` as its artifact. A sandbox run takes minutes, so the executor reports the task as working and completes it on a virtual thread.

## Swapping the coding agent

Each coding agent is an image variant built from the same wrapper contract. Verification is objective and CLI-agnostic:

```bash
cd sandbox
docker build --build-arg CODING_AGENT=opencode -t ghcr.io/kdubois/remediation-sandbox:opencode .
docker push ghcr.io/kdubois/remediation-sandbox:opencode
```

The image is not built by CI; the workflow builds the service image. The service selects the sandbox image through configuration, so demoing a different agent is a config change.

## Using the progressive-delivery example

[`progressive-delivery`](https://github.com/kdubois/progressive-delivery) is one way to instantiate the whole stack: Argo CD, Argo Rollouts, the analysis agent, this service, and a demo application, with the reference OpenShift configuration. It also wires Vault and the sandbox namespace. The service does not depend on it; the manifests in `deployment/` are enough on a plain Kubernetes cluster.

## Status

Phase 1 (sandbox prototype) is verified against the demo NullPointerException scenario: opencode fixed the bug, added a regression test, and the wrapper pushed the branch only after the suite passed. The A2A service, sandbox manifest, and evidence-based PR/issue creation are unit- and integration-tested. See [AGENTS.md](AGENTS.md) for the development guide.
