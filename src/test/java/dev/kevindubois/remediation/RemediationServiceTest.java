package dev.kevindubois.remediation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the remediation orchestration with a faked sandbox runner and
 * GitHub client: allow-list enforcement and normalization, PR on verified
 * success, issue on failure and for operational problems, distinct statuses
 * for infrastructure errors and PR-creation failures.
 */
class RemediationServiceTest {

    private static final String REPO = "https://github.com/kdubois/argo-rollouts-quarkus-demo";

    private final FakeSandboxRunner sandbox = new FakeSandboxRunner();
    private final FakeGitHub github = new FakeGitHub();
    private final RemediationService service = new RemediationService(
            sandbox, github, List.of(REPO), java.util.Optional.of("token"));

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
    void allowListMatchesNormalizedUrls() {
        // trailing slash and .git variants of the allow-listed repo pass
        for (String url : new String[] {
                REPO + "/", REPO + ".git", REPO + ".git/",
                "https://github.com/KDUBOIS/argo-rollouts-quarkus-demo" }) {
            sandbox.next = new SandboxResult("no_changes", null, 0, null, null, 0);
            var response = service.remediate(new RemediationRequest(url, "main", "CODE_BUG", null, "r", "a", null));
            assertThat(response.status()).as(url).isNotEqualTo("rejected");
        }
    }

    @Test
    void rejectsNonGithubUrlMatchingAllowListShape() {
        var response = service.remediate(
                new RemediationRequest("https://evil.example.com/kdubois/argo-rollouts-quarkus-demo",
                        "main", "CODE_BUG", null, "r", "a", null));
        assertThat(response.status()).isEqualTo("rejected");
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
        assertThat(github.lastPullRequestBody).contains("https://github.com/kdubois/argo-rollouts-quarkus-demo/tree/fix/k8s-issue-1");
        assertThat(github.lastPullRequestHead).isEqualTo("fix/k8s-issue-1");
    }

    @Test
    void prBodyRendersDashForNullFields() {
        sandbox.next = new SandboxResult("success", "fix/x", 1, null, null, 5);
        service.remediate(new RemediationRequest(REPO, "main", "CODE_BUG", null, null, null, null));

        assertThat(github.lastPullRequestBody).doesNotContain("null");
        assertThat(github.lastPullRequestBody).contains("summary unavailable");
    }

    @Test
    void reportsPrFailedWhenPrCreationFailsAfterVerifiedFix() {
        sandbox.next = new SandboxResult("success", "fix/k8s-issue-1", 1, "Tests run: 1, Failures: 0", "m", 5);
        github.failPullRequests = true;

        var response = service.remediate(request("CODE_BUG"));

        assertThat(response.status()).isEqualTo("pr_failed");
        assertThat(response.branch()).isEqualTo("fix/k8s-issue-1");
        assertThat(response.issueLink()).isNotNull();
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
    void infrastructureFailureDoesNotOpenIssue() {
        sandbox.next = null;

        var response = service.remediate(request("CODE_BUG"));

        assertThat(response.status()).isEqualTo("infra_error");
        assertThat(github.issuesCreated).isZero();
        assertThat(github.pullsCreated).isZero();
    }

    @Test
    void opensIssueDirectlyForOperationalProblems() {
        var response = service.remediate(request("OPERATIONAL"));

        assertThat(response.status()).isEqualTo("issue");
        assertThat(sandbox.ran).isFalse();
        assertThat(github.lastIssueBody).contains("null dereference in getStatus");
    }

    @ParameterizedTest
    @CsvSource({
            "https://github.com/owner/repo, owner, repo",
            "https://github.com/owner/repo.git, owner, repo",
            "https://github.com/owner/repo/, owner, repo",
            "https://github.com/owner/repo/.git/, owner, repo",
            "https://github.com/owner/repo/pull/42, owner, repo",
            "http://github.com/owner/repo, owner, repo",
    })
    void parsesAndNormalizesOwnerRepo(String url, String owner, String repo) {
        assertThat(GitHubClient.ownerRepo(url)).containsExactly(owner, repo);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://gitlab.com/owner/repo",
            "https://evil.example.com/owner/repo",
            "not a url",
            "https://github.com/onlyowner",
            ""
    })
    void rejectsNonGithubOrMalformedUrls(String url) {
        assertThatThrownBy(() -> GitHubClient.ownerRepo(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeSandboxRunner extends SandboxRunner {
        boolean ran;
        boolean cancelled;
        SandboxResult next;

        FakeSandboxRunner() {
            super(null, null, null);
        }

        @Override
        public SandboxResult run(String name, RemediationRequest request, String prompt) {
            ran = true;
            return next;
        }

        @Override
        public void cancel(String name) {
            cancelled = true;
        }
    }

    private static final class FakeGitHub implements GitHubClient {
        int pullsCreated;
        int issuesCreated;
        boolean failPullRequests;
        String lastPullRequestBody;
        String lastPullRequestHead;
        String lastIssueBody;

        @Override
        public PullRequest createPullRequest(String owner, String repo, String auth, CreatePullRequest request) {
            if (failPullRequests) {
                throw new RuntimeException("GitHub is down");
            }
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
