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
     * @return the wrapper result, or an agent_error result with the failure reason
     */
    public SandboxResult run(String name, RemediationRequest request, String prompt) {
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
            return new SandboxResult("agent_error", null, 0, null, null, 0);
        }

        try {
            awaitPod(name);
            String log = client.pods().inNamespace(namespace()).withName(name).getLog();
            SandboxResult result = parseResult(log);
            Log.infof("Sandbox %s finished: %s", name, result == null ? "no result" : result.status());
            return result == null
                    ? new SandboxResult("agent_error", null, 0, null, null, 0)
                    : result;
        } finally {
            delete(name);
        }
    }

    String namespace() {
        return client.getConfiguration().getNamespace() != null
                ? client.getConfiguration().getNamespace()
                : "remediation";
    }

    private String toJson(Object manifest) {
        try {
            return mapper.writeValueAsString(manifest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize sandbox manifest", e);
        }
    }

    /**
     * Waits for the sandbox pod to reach a terminal phase. The pod name equals
     * the sandbox name.
     */
    private void awaitPod(String name) {
        long deadline = System.currentTimeMillis() + config.timeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Pod pod = client.pods().inNamespace(namespace()).withName(name).get();
            if (pod != null && pod.getStatus() != null) {
                String phase = pod.getStatus().getPhase();
                if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                    return;
                }
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.warnf("Sandbox %s timed out after %ds", name, config.timeoutSeconds());
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
}
