package dev.kevindubois.remediation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Result JSON emitted by {@code sandbox/run-agent.sh} — the sandbox's only
 * output contract. Field names must match the wrapper's printf format.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxResult(
        String status,
        String branch,
        int filesChanged,
        String testSummary,
        String model,
        int durationSeconds) {

    public boolean success() {
        return "success".equals(status);
    }
}
