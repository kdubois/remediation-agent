package dev.kevindubois.remediation;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remediation orchestration: validates the request, runs the coding-agent
 * sandbox for code bugs, and turns the outcome into a GitHub PR with
 * verification evidence or a GitHub issue explaining the failure.
 * Operational issues skip the sandbox — they are not code defects.
 *
 * <p>Infrastructure failures (bad image, missing namespace, RBAC) are
 * reported as {@code infra_error} and never open a GitHub issue: they say
 * nothing about the repository.
 */
@ApplicationScoped
public class RemediationService {

    private static final int MAX_ANALYSIS_LENGTH = 2000;
    private static final int MAX_ERROR_LOG_LENGTH = 4000;

    private final SandboxRunner sandboxRunner;
    private final GitHubClient github;
    private final List<String> allowedRepos;
    private final String githubToken;

    /** Task id to sandbox name, so A2A cancellation can delete the sandbox. */
    private final Map<String, String> runningSandboxes = new ConcurrentHashMap<>();

    @Inject
    public RemediationService(SandboxRunner sandboxRunner, @RestClient GitHubClient github,
            @ConfigProperty(name = "remediation.allowed-repos") List<String> allowedRepos,
            @ConfigProperty(name = "github.token") Optional<String> githubToken) {
        this.sandboxRunner = sandboxRunner;
        this.github = github;
        this.allowedRepos = allowedRepos;
        this.githubToken = githubToken.orElse("");
    }

    public RemediationResponse remediate(RemediationRequest request) {
        return remediate(request, null);
    }

    public RemediationResponse remediate(RemediationRequest request, String taskId) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return RemediationResponse.rejected("repoUrl is required");
        }
        if (!isAllowed(request.repoUrl())) {
            Log.warnf("Rejected remediation for repo outside the allow-list: %s", request.repoUrl());
            return RemediationResponse.rejected("repository is not in the allow-list");
        }

        var ownerRepo = GitHubClient.ownerRepo(request.repoUrl());
        if (request.operational()) {
            return createIssue(ownerRepo, request, "Operational issue detected by canary analysis");
        }

        String sandboxName = SandboxManifest.newSandboxName();
        if (taskId != null) {
            runningSandboxes.put(taskId, sandboxName);
        }
        SandboxResult result;
        try {
            result = sandboxRunner.run(sandboxName, request, prompt(request));
        } finally {
            if (taskId != null) {
                runningSandboxes.remove(taskId);
            }
        }

        if (result == null) {
            // the runner could not create or observe the sandbox — infrastructure
            return new RemediationResponse("infra_error", null, null, null, 0, null, sandboxName,
                    "sandbox infrastructure failure, see service logs");
        }
        if ("success".equals(result.status())) {
            return createPullRequest(ownerRepo, request, result, sandboxName);
        }
        if ("agent_error".equals(result.status())) {
            return new RemediationResponse("infra_error", null, null, null, 0, null, sandboxName,
                    "sandbox run failed, see service logs");
        }
        return createIssue(ownerRepo, request,
                "No verified fix produced (sandbox status: " + result.status() + ")");
    }

    /** Cancels the sandbox backing an A2A task, if one is running. */
    public void cancel(String taskId) {
        String sandboxName = taskId == null ? null : runningSandboxes.remove(taskId);
        if (sandboxName != null) {
            sandboxRunner.cancel(sandboxName);
        }
    }

    /** Allow-list comparison on normalized owner/repo, ignoring host case and URL shape. */
    private boolean isAllowed(String repoUrl) {
        String[] candidate;
        try {
            candidate = GitHubClient.ownerRepo(repoUrl);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return allowedRepos.stream().anyMatch(allowed -> {
            String[] entry = GitHubClient.ownerRepo(allowed);
            return entry[0].equalsIgnoreCase(candidate[0]) && entry[1].equalsIgnoreCase(candidate[1]);
        });
    }

    private RemediationResponse createPullRequest(String[] ownerRepo, RemediationRequest request,
            SandboxResult result, String sandboxName) {
        try {
            var pr = github.createPullRequest(ownerRepo[0], ownerRepo[1], auth(),
                    new GitHubClient.CreatePullRequest(
                            "Fix: " + shortTitle(request),
                            result.branch(),
                            request.baseBranch(),
                            pullRequestBody(request, result, sandboxName, ownerRepo)));
            Log.infof("Opened PR %s for branch %s", pr.html_url(), result.branch());
            return new RemediationResponse("success", pr.html_url(), null, result.branch(),
                    result.filesChanged(), result.testSummary(), sandboxName, null);
        } catch (Exception e) {
            Log.errorf(e, "PR creation failed for branch %s", result.branch());
            RemediationResponse response = createIssue(ownerRepo, request,
                    "Verified fix pushed to " + result.branch() + " but the PR could not be created: "
                            + e.getMessage());
            return new RemediationResponse("pr_failed", null, response.issueLink(), result.branch(),
                    result.filesChanged(), result.testSummary(), sandboxName, response.message());
        }
    }

    private RemediationResponse createIssue(String[] ownerRepo, RemediationRequest request, String reason) {
        try {
            var issue = github.createIssue(ownerRepo[0], ownerRepo[1], auth(),
                    new GitHubClient.CreateIssue(
                            "Canary deployment failed: " + shortTitle(request),
                            issueBody(request, reason),
                            List.of("canary", "deployment-failure")));
            Log.infof("Opened issue %s", issue.html_url());
            return new RemediationResponse("issue", null, issue.html_url(), null, 0, null, null, reason);
        } catch (Exception e) {
            Log.errorf(e, "Issue creation failed");
            return new RemediationResponse("agent_error", null, null, null, 0, null, null,
                    "GitHub issue creation failed: " + e.getMessage());
        }
    }

    private String pullRequestBody(RemediationRequest request, SandboxResult result, String sandboxName,
            String[] ownerRepo) {
        String branchUrl = "https://github.com/" + ownerRepo[0] + "/" + ownerRepo[1] + "/tree/" + result.branch();
        return """
                ## Automated remediation from canary analysis

                %s

                ## Verification

                The fix was produced by a coding agent (%s) inside an isolated sandbox
                (restricted-v2, no service-account token, egress limited to GitHub, Maven
                Central and the LLM endpoint) and verified with this repository's own
                test suite before the PR was created.

                - **Test result:** %s
                - **Files changed:** %d
                - **Branch:** [%s](%s)
                - **Sandbox:** %s
                - **Duration:** %ds

                ## Analysis

                %s

                ## Canary error logs

                ```
                %s
                ```
                """.formatted(
                orDash(request.rootCause()),
                result.model() == null || result.model().isBlank() ? "default" : result.model(),
                result.testSummary() == null || result.testSummary().isBlank()
                        ? "tests passed (summary unavailable)" : result.testSummary(),
                result.filesChanged(),
                result.branch(), branchUrl,
                sandboxName,
                result.durationSeconds(),
                orDash(cap(request.analysis(), MAX_ANALYSIS_LENGTH)),
                cap(request.diagnosticContext() == null ? "" : request.diagnosticContext(), MAX_ERROR_LOG_LENGTH));
    }

    private String issueBody(RemediationRequest request, String reason) {
        return """
                A canary deployment of this repository was rolled back by an
                automated analysis and no verified fix could be produced.

                **Reason:** %s

                ## Root cause

                %s

                ## Analysis

                %s

                ## Canary error logs

                ```
                %s
                ```
                """.formatted(
                reason,
                orDash(request.rootCause()),
                orDash(cap(request.analysis(), MAX_ANALYSIS_LENGTH)),
                cap(request.diagnosticContext() == null ? "" : request.diagnosticContext(), MAX_ERROR_LOG_LENGTH));
    }

    private String prompt(RemediationRequest request) {
        return """
                A canary deployment of this repository was rolled back by an automated analysis.

                **Decision:** rollback
                **Category:** %s

                **Root cause**

                %s

                **Analysis**

                %s

                **Diagnostic context**

                ```
                %s
                ```
                """.formatted(
                request.issueCategory(),
                request.rootCause(),
                request.analysis(),
                cap(request.diagnosticContext() == null ? "" : request.diagnosticContext(), MAX_ERROR_LOG_LENGTH));
    }

    private String shortTitle(RemediationRequest request) {
        var title = request.title() != null && !request.title().isBlank() ? request.title() : request.rootCause();
        var line = (title == null ? "canary rollback" : title).lines().findFirst().orElse("canary rollback");
        return line.length() > 100 ? line.substring(0, 100) : line;
    }

    private String auth() {
        return "Bearer " + githubToken;
    }

    private static String cap(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n... (truncated)";
    }

    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }
}
