package dev.kevindubois.remediation;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Minimal GitHub REST client: creating a pull request with the verification
 * evidence, or an issue when no verified fix was produced.
 */
@RegisterRestClient(configKey = "github-api")
@Path("/repos")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface GitHubClient {

    @POST
    @Path("/{owner}/{repo}/pulls")
    PullRequest createPullRequest(@PathParam("owner") String owner, @PathParam("repo") String repo,
            @HeaderParam("Authorization") String auth, CreatePullRequest request);

    @POST
    @Path("/{owner}/{repo}/issues")
    Issue createIssue(@PathParam("owner") String owner, @PathParam("repo") String repo,
            @HeaderParam("Authorization") String auth, CreateIssue request);

    record CreatePullRequest(String title, String head, String base, String body) {
    }

    record CreateIssue(String title, String body, java.util.List<String> labels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequest(String html_url, String number) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Issue(String html_url, String number) {
    }

    /** Parses and normalizes an owner/repo pair from a GitHub repository https URL. */
    static String[] ownerRepo(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL is required");
        }
        String path = repoUrl.trim()
                .replaceFirst("(?i)^https://github\\.com/", "")
                .replaceFirst("(?i)^http://github\\.com/", "")
                .replaceAll("/+$", "")
                .replaceFirst("\\.git$", "")
                .replaceAll("/+$", "");
        if (path.startsWith("http") || path.contains("://")) {
            throw new IllegalArgumentException("Not a GitHub repository URL: " + repoUrl);
        }
        String[] parts = path.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Not a GitHub repository URL: " + repoUrl);
        }
        return new String[] { parts[0], parts[1] };
    }
}
