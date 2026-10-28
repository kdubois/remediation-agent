package dev.kevindubois.remediation;

/**
 * Sandbox runtime configuration, bound from application.properties and passed
 * explicitly so tests can construct it directly.
 *
 * @param image            sandbox image tag, selects the coding agent (opencode, pi, bob)
 * @param namespace        namespace where sandbox pods run (dedicated, egress-restricted)
 * @param codingAgent      value for the wrapper's CODING_AGENT env var
 * @param model            model id passed through to the coding agent, may be blank
 * @param llmBaseUrl       OpenAI-compatible endpoint the sandbox agent authenticates against
 * @param llmSecretName    secret holding the LLM API key for the sandbox
 * @param llmSecretKey     key of the LLM API key inside that secret
 * @param githubSecretName secret holding the GitHub token the sandbox uses to push
 * @param githubSecretKey  key of the token inside that secret
 * @param timeoutSeconds      hard limit for one sandbox run
 * @param keepFailedSandboxes keep sandboxes that produced no result, for log inspection
 */
public record SandboxConfig(
        String image,
        String namespace,
        String codingAgent,
        String model,
        String llmBaseUrl,
        String llmSecretName,
        String llmSecretKey,
        String githubSecretName,
        String githubSecretKey,
        int timeoutSeconds,
        boolean keepFailedSandboxes) {
}
