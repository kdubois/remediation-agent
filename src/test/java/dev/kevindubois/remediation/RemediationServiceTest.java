package dev.kevindubois.remediation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the remediation orchestration with a faked sandbox runner and
 * GitHub client: allow-list enforcement, PR on verified success, issue on
 * failure and for operational problems.
 */
class RemediationServiceTest {

    private static final String REPO = "https://github.com/kdubois/argo-rollouts-quarkus-demo";

    private final FakeSandboxRunner sandbox = new FakeSandboxRunner();
    private final FakeGitHub github = new FakeGitHub();
    private final RemediationService service = new RemediationService(
            sandbox, github, List.of(REPO), Optional.of("token"));

    private RemediationRequest request(String category) {
        return new RemediationRequest(REPO, "main", category, "NPE in getStatus",
                "null dereference in getStatus", "error rate spiked", "NullPointerException at line 129");
    }

    @Test
    void rejectsRepoOutsideAllowList() {
        var response = service.remediate(
                new RemediationRequest("https://github.com/evil/repo", "main", "CODE_BUG", null, "r", "a", null));

        assertThat(response.status()).isEqualTo("rejected");
        assertThat(sandbox.ran).isFalse();
        assertThat(github.pullsCreated).isZero();
    }

    @Test
    void opensPrWithEvidenceOnVerifiedSuccess() {
        sandbox.next = new SandboxResult("success", "fix/k8s-issue-1", 2,
                "Tests run: 10, Failures: 0", "glm-5", 222);

        var response = service.remediate(request("CODE_BUG"));

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.prLink()).isEqualTo("https://github.com/pr/1");
        assertThat(response.branch()).isEqualTo("fix/k8s-issue-1");
        assertThat(response.testSummary()).contains("Tests run: 10");
        assertThat(github.pullsCreated).isEqualTo(1);
        assertThat(github.lastPullRequestBody).contains("Tests run: 10, Failures: 0");
        assertThat(github.lastPullRequestBody).contains("isolated sandbox");
        assertThat(github.lastPullRequestBody).contains("null dereference in getStatus");
        assertThat(github.lastPullRequestHead).isEqualTo("fix/k8s-issue-1");
    }

    @Test
    void opensIssueWhenTestsFail() {
        sandbox.next = new SandboxResult("tests_failed", "fix/x", 1, null, "glm-5", 60);

        var response = service.remediate(request("CODE_BUG"));

        assertThat(response.status()).isEqualTo("issue");
        assertThat(response.issueLink()).isEqualTo("https://github.com/issues/1");
        assertThat(github.pullsCreated).isZero();
        assertThat(github.lastIssueBody).contains("tests_failed");
    }

    @Test
    void opensIssueDirectlyForOperationalProblems() {
        var response = service.remediate(request("OPERATIONAL"));

        assertThat(response.status()).isEqualTo("issue");
        assertThat(sandbox.ran).isFalse();
        assertThat(github.lastIssueBody).contains("null dereference in getStatus");
    }

    private static final class FakeSandboxRunner extends SandboxRunner {
        boolean ran;
        SandboxResult next;

        FakeSandboxRunner() {
            super(null, null, null);
        }

        @Override
        public SandboxResult run(String name, RemediationRequest request, String prompt) {
            ran = true;
            return next;
        }
    }

    private static final class FakeGitHub implements GitHubClient {
        int pullsCreated;
        int issuesCreated;
        String lastPullRequestBody;
        String lastPullRequestHead;
        String lastIssueBody;

        @Override
        public PullRequest createPullRequest(String owner, String repo, String auth, CreatePullRequest request) {
            pullsCreated++;
            lastPullRequestBody = request.body();
            lastPullRequestHead = request.head();
            return new PullRequest("https://github.com/pr/" + pullsCreated, String.valueOf(pullsCreated));
        }

        @Override
        public Issue createIssue(String owner, String repo, String auth, CreateIssue request) {
            issuesCreated++;
            lastIssueBody = request.body();
            return new Issue("https://github.com/issues/" + issuesCreated, String.valueOf(issuesCreated));
        }
    }
}
