package dev.kevindubois.remediation;

/**
 * Result of a remediation run, returned to the kubernetes-agent.
 *
 * @param status       success | tests_failed | no_changes | agent_error | rejected | timeout
 * @param prLink       GitHub PR URL when a verified fix was proposed, else null
 * @param issueLink    GitHub issue URL when an issue was opened, else null
 * @param branch       pushed branch name on success, else null
 * @param filesChanged number of files the coding agent changed
 * @param testSummary  test suite summary from the sandbox verification
 * @param sandbox      name of the sandbox that ran the remediation
 * @param message      human-readable detail for the non-happy paths
 */
public record RemediationResponse(
        String status,
        String prLink,
        String issueLink,
        String branch,
        int filesChanged,
        String testSummary,
        String sandbox,
        String message) {

    public static RemediationResponse rejected(String message) {
        return new RemediationResponse("rejected", null, null, null, 0, null, null, message);
    }
}
