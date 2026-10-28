package dev.kevindubois.remediation;

/**
 * Remediation request from the kubernetes-agent after a rollback decision.
 *
 * @param repoUrl       https URL of the repository that failed the canary
 * @param baseBranch    branch the fix should target
 * @param issueCategory CODE_BUG or OPERATIONAL, decides PR or issue handling
 * @param title         short summary for the PR or issue
 * @param rootCause     root cause from the canary analysis
 * @param analysis      full analysis text from the canary analysis
 * @param errorLogs     canary error logs (stack traces) for the sandbox prompt
 */
public record RemediationRequest(
        String repoUrl,
        String baseBranch,
        String issueCategory,
        String title,
        String rootCause,
        String analysis,
        String errorLogs) {

    public RemediationRequest {
        baseBranch = baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch;
    }

    public boolean operational() {
        return "OPERATIONAL".equalsIgnoreCase(issueCategory);
    }
}
