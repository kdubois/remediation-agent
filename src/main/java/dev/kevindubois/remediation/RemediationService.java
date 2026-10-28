package dev.kevindubois.remediation;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * Remediation orchestration: validates the request, runs the coding-agent
 * sandbox for code bugs, and turns the outcome into a GitHub PR with
 * verification evidence or a GitHub issue explaining the failure.
 * Operational issues skip the sandbox — they are not code defects.
 */
@ApplicationScoped
public class RemediationService {

    private final SandboxRunner sandboxRunner;
    private final GitHubClient github;
    private final List<String> allowedRepos;
    private final String githubToken;

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
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return RemediationResponse.rejected("repoUrl is required");
        }
        if (!allowedRepos.contains(request.repoUrl())) {
            Log.warnf("Rejected remediation for repo outside the allow-list: %s", request.repoUrl());
            return RemediationResponse.rejected("repository is not in the allow-list");
        }

        var ownerRepo = GitHubClient.ownerRepo(request.repoUrl());
        if (request.operational()) {
            return createIssue(ownerRepo, request, "Operational issue detected by canary analysis");
        }

        String sandboxName = SandboxManifest.newSandboxName();
        SandboxResult result = sandboxRunner.run(sandboxName, request, prompt(request));

        if (result.success()) {
            return createPullRequest(ownerRepo, request, result, sandboxName);
        }
        return createIssue(ownerRepo, request,
                "No verified fix produced (sandbox status: " + result.status() + ")");
    }

    private RemediationResponse createPullRequest(String[] ownerRepo, RemediationRequest request,
            SandboxResult result, String sandboxName) {
        try {
            var pr = github.createPullRequest(ownerRepo[0], ownerRepo[1], auth(),
                    new GitHubClient.CreatePullRequest(
                            "Fix: " + shortTitle(request),
                            result.branch(),
                            request.baseBranch(),
                            pullRequestBody(request, result, sandboxName)));
            Log.infof("Opened PR %s for branch %s", pr.html_url(), result.branch());
            return new RemediationResponse("success", pr.html_url(), null, result.branch(),
                    result.filesChanged(), result.testSummary(), sandboxName, null);
        } catch (Exception e) {
            Log.errorf(e, "PR creation failed for branch %s", result.branch());
            return createIssue(ownerRepo, request, "Verified fix pushed to " + result.branch()
                    + " but the PR could not be created: " + e.getMessage());
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

    private String pullRequestBody(RemediationRequest request, SandboxResult result, String sandboxName) {
        return """
                ## Automated remediation from canary analysis

                %s

                ## Verification

                The fix was produced by a coding agent (%s) inside an isolated sandbox
                (restricted-v2, no service-account token, egress limited to GitHub and
                Maven Central) and verified with this repository's own test suite
                before the PR was created.

                - **Test result:** %s
                - **Files changed:** %d
                - **Sandbox:** %s
                - **Duration:** %ds

                ## Analysis

                %s

                ## Canary error logs

                ```
                %s
                ```
                """.formatted(
                request.rootCause(),
                result.model() == null || result.model().isBlank() ? "default" : result.model(),
                result.testSummary(),
                result.filesChanged(),
                sandboxName,
                result.durationSeconds(),
                request.analysis(),
                request.errorLogs() == null ? "" : request.errorLogs());
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
                request.rootCause(),
                request.analysis(),
                request.errorLogs() == null ? "" : request.errorLogs());
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

                **Canary error logs**

                ```
                %s
                ```
                """.formatted(
                request.issueCategory(),
                request.rootCause(),
                request.analysis(),
                request.errorLogs() == null ? "" : request.errorLogs());
    }

    private String shortTitle(RemediationRequest request) {
        var title = request.title() != null && !request.title().isBlank() ? request.title() : request.rootCause();
        var line = (title == null ? "canary rollback" : title).lines().findFirst().orElse("canary rollback");
        return line.length() > 100 ? line.substring(0, 100) : line;
    }

    private String auth() {
        return "Bearer " + githubToken;
    }
}
