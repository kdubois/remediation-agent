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

    /** Parses an owner/repo pair from a repository https URL. */
    static String[] ownerRepo(String repoUrl) {
        String path = repoUrl.replaceFirst("^https://github\\.com/", "").replaceFirst("\\.git$", "");
        String[] parts = path.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Not a GitHub repository URL: " + repoUrl);
        }
        return new String[] { parts[0], parts[1] };
    }
}
