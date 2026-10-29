package dev.kevindubois.remediation.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kevindubois.remediation.SandboxConfig;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of the running remediation sandbox for the demo UI:
 * which sandbox is active, and its agent log as compact, classified
 * events (tool calls, agent commentary, wrapper progress, result).
 *
 * <p>Deliberately polling-based: the UI polls {@code /sandbox/active}
 * and fetches new log lines by index, which survives reconnects and
 * needs no SSE plumbing through the demo proxy.
 */
@Path("/sandbox")
@ApplicationScoped
public class SandboxStreamResource {

    @Inject
    KubernetesClient client;

    @Inject
    SandboxConfig config;

    @Inject
    ObjectMapper mapper;

    @Inject
    SandboxStreamResource(KubernetesClient client, SandboxConfig config, ObjectMapper mapper) {
        this.client = client;
        this.config = config;
        this.mapper = mapper;
    }

    public record ActiveSandbox(String name, String phase) {
    }

    /**
     * The sandbox pod currently Pending or Running, if any. The most
     * recently created one wins when several are racing.
     */
    @GET
    @Path("/active")
    @Produces(MediaType.APPLICATION_JSON)
    public ActiveSandbox active() {
        return client.pods().inNamespace(config.namespace()).list().getItems().stream()
                .filter(this::isSandboxPod)
                .filter(p -> {
                    String phase = p.getStatus() != null ? p.getStatus().getPhase() : "";
                    return "Running".equals(phase) || "Pending".equals(phase);
                })
                .reduce((a, b) -> newer(a, b))
                .map(p -> new ActiveSandbox(p.getMetadata().getName(),
                        p.getStatus() != null ? p.getStatus().getPhase() : "Unknown"))
                .orElseThrow(() -> new NotFoundException("no active sandbox"));
    }

    /**
     * Classified agent log lines after the given index. {@code done} is
     * true once the wrapper's result JSON has been seen (or the pod is
     * terminal), so the UI knows when to stop polling.
     */
    @GET
    @Path("/{name}/log")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> log(@PathParam("name") String name, @QueryParam("after") int after) {
        List<Map<String, String>> events = classify(readLog(name));
        int from = Math.max(0, Math.min(after, events.size()));
        List<Map<String, String>> fresh = events.subList(from, events.size());
        boolean done = events.stream().anyMatch(e -> "result".equals(e.get("type")))
                || isTerminal(name);
        return Map.of("lines", fresh, "next", events.size(), "done", done);
    }

    /** Turns raw pod log lines into compact UI events, dropping protocol noise. */
    List<Map<String, String>> classify(String podLog) {
        if (podLog == null || podLog.isBlank()) {
            return List.of();
        }
        var events = new java.util.ArrayList<Map<String, String>>();
        podLog.lines().forEach(line -> {
            String trimmed = line.trim();
            if (trimmed.startsWith("[run-agent]")) {
                events.add(event("wrapper", trimmed.substring("[run-agent] ".length())));
            } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    var node = mapper.readTree(trimmed);
                    String type = node.path("type").asText();
                    if ("tool_use".equals(type)) {
                        var input = node.path("part").path("state").path("input");
                        String text = firstNonBlank(input.path("command").asText(),
                                input.path("filePath").asText(), "tool call");
                        events.add(event("tool", truncate(text, 160)));
                    } else if ("text".equals(type)) {
                        String text = node.path("part").path("text").asText().strip();
                        if (!text.isBlank()) {
                            events.add(event("agent", truncate(text, 200)));
                        }
                    } else if (trimmed.contains("\"status\":\"success\"")
                            || trimmed.contains("\"status\":\"tests_failed\"")
                            || trimmed.contains("\"status\":\"agent_error\"")
                            || trimmed.contains("\"status\":\"no_changes\"")) {
                        events.add(event("result", node.path("status").asText("unknown")));
                    }
                    // step_start/step_finish and tool result chunks are noise
                } catch (Exception ignored) {
                    // not JSON, ignore
                }
            }
        });
        return events;
    }

    private String readLog(String name) {
        Pod pod = client.pods().inNamespace(config.namespace()).withName(name).get();
        if (pod == null) {
            throw new NotFoundException("sandbox " + name + " not found");
        }
        try {
            return client.pods().inNamespace(config.namespace()).withName(name).getLog();
        } catch (Exception e) {
            // pod starting or gone; nothing to show yet
            return "";
        }
    }

    private boolean isTerminal(String name) {
        Pod pod = client.pods().inNamespace(config.namespace()).withName(name).get();
        if (pod == null || pod.getStatus() == null) {
            return true;
        }
        String phase = pod.getStatus().getPhase();
        return "Succeeded".equals(phase) || "Failed".equals(phase);
    }

    private boolean isSandboxPod(Pod pod) {
        var labels = pod.getMetadata().getLabels();
        return labels != null && "remediation-agent".equals(labels.get("app"));
    }

    private Pod newer(Pod a, Pod b) {
        return a.getMetadata().getCreationTimestamp().compareTo(b.getMetadata().getCreationTimestamp()) >= 0 ? a : b;
    }

    private static Map<String, String> event(String type, String text) {
        return Map.of("type", type, "text", text);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return "";
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
