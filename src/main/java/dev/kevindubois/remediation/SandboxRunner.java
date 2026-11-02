package dev.kevindubois.remediation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.logging.Log;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Runs one remediation in a Sandbox custom resource and returns the wrapper's
 * result JSON. Creates the sandbox, polls its pod until it exits (or the
 * timeout hits), reads the result from the pod log, then deletes the sandbox.
 *
 * <p>The pod name equals the Sandbox CR name — a contract of the Agent
 * Sandbox controller (it sets an agents.x-k8s.io/pod-name annotation only as
 * a legacy fallback). Infrastructure failures (no namespace, bad image,
 * RBAC, missing pod, timeout) return a {@link SandboxOutcome.Failed}
 * carrying the reason, so the caller can report them distinctly rather
 * than as a remediation outcome.
 */
@ApplicationScoped
public class SandboxRunner {

    private final KubernetesClient client;
    private final ObjectMapper mapper;
    private final SandboxConfig config;

    @Inject
    public SandboxRunner(KubernetesClient client, ObjectMapper mapper, SandboxConfig config) {
        this.client = client;
        this.mapper = mapper;
        this.config = config;
    }

    /**
     * Runs the remediation sandbox. Blocks until the wrapper finishes or the
     * timeout expires — callers decide whether to wait on their own threads.
     *
     * <p>When {@code config.keepFailedSandboxes()} is set and the run ends in
     * a failure the pod logs could explain, the sandbox is left in place for
     * inspection; shutdownPolicy Delete still cleans it up eventually.
     *
     * @return the wrapper result, or the reason none could be produced
     */
    public SandboxOutcome run(String name, RemediationRequest request, String prompt) {
        var sandbox = Serialization.unmarshal(
                toJson(SandboxManifest.build(name, request, prompt, config)),
                io.fabric8.kubernetes.api.model.GenericKubernetesResource.class);
        try {
            client.genericKubernetesResources("agents.x-k8s.io/v1beta1", "Sandbox")
                    .inNamespace(namespace())
                    .resource(sandbox)
                    .create();
        } catch (Exception e) {
            Log.errorf(e, "Failed to create sandbox %s", name);
            return SandboxOutcome.failed(SandboxOutcome.Reason.CREATE_FAILED);
        }

        SandboxOutcome outcome;
        if (!awaitPod(name)) {
            outcome = SandboxOutcome.failed(SandboxOutcome.Reason.TIMEOUT);
        } else {
            String log = readLog(name);
            if (log == null) {
                outcome = SandboxOutcome.failed(SandboxOutcome.Reason.LOG_UNREADABLE);
            } else {
                SandboxResult result = parseResult(log);
                outcome = result == null
                        ? SandboxOutcome.failed(SandboxOutcome.Reason.NO_RESULT)
                        : SandboxOutcome.completed(result);
            }
        }

        // keepFailedSandboxes applies to runs that ended with a log worth
        // inspecting: a failed result, or a log with no result JSON in it.
        // Timeouts and unreadable logs are deleted — the pod may still be
        // running and burning resources.
        boolean keep = config.keepFailedSandboxes() && switch (outcome) {
            case SandboxOutcome.Completed c -> !c.result().success();
            case SandboxOutcome.Failed f -> f.reason() == SandboxOutcome.Reason.NO_RESULT;
        };
        if (keep) {
            Log.warnf("Keeping failed sandbox %s for inspection", name);
        } else {
            delete(name);
        }

        if (outcome instanceof SandboxOutcome.Completed c) {
            Log.infof("Sandbox %s finished: %s", name, c.result().status());
        }
        return outcome;
    }

    /** Deletes a running sandbox, used by A2A task cancellation. */
    public void cancel(String name) {
        delete(name);
    }

    String namespace() {
        return config.namespace();
    }

    /**
     * Waits for the sandbox pod to reach a terminal phase.
     */
    private boolean awaitPod(String name) {
        long deadline = System.currentTimeMillis() + config.timeoutSeconds() * 1000L;
        boolean seen = false;
        while (System.currentTimeMillis() < deadline) {
            Pod pod;
            try {
                pod = client.pods().inNamespace(namespace()).withName(name).get();
            } catch (Exception e) {
                Log.errorf(e, "Failed to watch sandbox pod %s", name);
                return false;
            }
            if (pod != null && pod.getStatus() != null) {
                seen = true;
                String phase = pod.getStatus().getPhase();
                if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                    return true;
                }
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.warnf("Sandbox %s timed out after %ds (pod seen: %s)", name, config.timeoutSeconds(), seen);
        return false;
    }

    private String readLog(String name) {
        try {
            return client.pods().inNamespace(namespace()).withName(name).getLog();
        } catch (Exception e) {
            Log.errorf(e, "Failed to read sandbox pod log %s", name);
            return null;
        }
    }

    /** Extracts the wrapper's result JSON — its last stdout line — from the pod log. */
    SandboxResult parseResult(String podLog) {
        if (podLog == null || podLog.isBlank()) {
            return null;
        }
        var lines = podLog.lines().toList();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                try {
                    return mapper.readValue(line, SandboxResult.class);
                } catch (Exception ignored) {
                    // not the result JSON, keep scanning
                }
            }
        }
        return null;
    }

    private void delete(String name) {
        try {
            client.genericKubernetesResources("agents.x-k8s.io/v1beta1", "Sandbox")
                    .inNamespace(namespace()).withName(name).delete();
        } catch (Exception e) {
            // shutdownPolicy Delete cleans up eventually; a failed delete here is not fatal
            Log.debugf("Failed to delete sandbox %s: %s", name, e.getMessage());
        }
    }

    private String toJson(Object manifest) {
        try {
            return mapper.writeValueAsString(manifest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize sandbox manifest", e);
        }
    }
}
