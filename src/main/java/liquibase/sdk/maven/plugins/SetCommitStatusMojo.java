package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.kohsuke.github.GHCommitState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;


/**
 * <p>Sets a github commit status.</p>
 */
@Mojo(name = "set-commit-status", requiresProject = false)
public class SetCommitStatusMojo extends AbstractGitHubMojo {

    private static final Logger log = LoggerFactory.getLogger(SetCommitStatusMojo.class);

    @Parameter(property = "liquibase.sdk.status.context", required = true)
    protected String statusContext;

    @Parameter(property = "liquibase.sdk.status.description", required = true)
    protected String statusDescription;

    @Parameter(property = "liquibase.sdk.status.state", required = true)
    protected String statusState;

    @Parameter(property = "liquibase.sdk.status.url", required = true)
    protected String statusUrl;

    /**
     * If not set, use commit of installed liquibase
     */
    @Parameter(property = "liquibase.sdk.status.commit", required = true)
    protected String statusCommit;

    public void execute() throws MojoExecutionException {
        try {
            String repo = getRepo();

            GitHubClient github = createGitHubClient();

            String commit;
            if (StringUtils.trimToNull(statusCommit).equals("installed")) {
                final Properties buildInfo = github.getInstalledBuildProperties();

                if (repo.equals("liquibase/liquibase")) {
                    commit = (String) buildInfo.get("build.commit");
                } else {
                    commit = (String) buildInfo.get("build.pro.commit");
                }

                if (commit == null) {
                    throw new MojoExecutionException("Could not find commit in build properties");
                }
            } else {
                commit = statusCommit;
            }

            log.info("Setting commit status for commit " + commit + " on " + repo);

            github.setCommitStatus(repo, commit, GHCommitState.valueOf(statusState.toUpperCase()), statusContext, statusDescription, statusUrl);
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
