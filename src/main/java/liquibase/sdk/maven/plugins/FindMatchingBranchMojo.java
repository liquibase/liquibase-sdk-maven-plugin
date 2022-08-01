package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * <p>Finds the branch.</p>
 */
@Mojo(name = "find-matching-branch")
public class FindMatchingBranchMojo extends AbstractGitHubMojo {

    private static final Logger log = LoggerFactory.getLogger(FindMatchingBranchMojo.class);

    /**
     * Branch names. If a pull request from a fork, use the syntax `fork_owner:branch`
     */
    @Parameter(property = "liquibase.sdk.branchSearch", required = true)
    protected String branchSearch;

    public void execute() throws MojoExecutionException {

        String repo = getRepo();
        log.info("Looking for " + branchSearch + " in " + repo);

        try {
            GitHubClient github = createGitHubClient();

            final String matchedLabel = github.findMatchingBranch(repo, this.branchSearch.split("\\s*,\\s*"));
            log.info("Found matching branch " + matchedLabel);
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
