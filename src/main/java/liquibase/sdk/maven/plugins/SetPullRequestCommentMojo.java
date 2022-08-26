package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;


/**
 * <p>Finds the branch.</p>
 */
@Mojo(name = "set-pull-request-comment", requiresProject = false)
public class SetPullRequestCommentMojo extends AbstractGitHubMojo {

    private static final Logger log = LoggerFactory.getLogger(SetPullRequestCommentMojo.class);

    @Parameter(defaultValue = "${mojo.version}")
    private String mojoVersion;

    @Parameter(property = "liquibase.sdk.pr.newComment", required = true)
    protected String newComment;

    @Parameter(property = "liquibase.sdk.pr.id", required = true)
    protected Integer pullRequestId;

    @Parameter(property = "liquibase.sdk.pr.replaceCommentPattern")
    protected String replaceCommentPattern;

    public void execute() throws MojoExecutionException {

        try {
            String repo = getRepo();

            GitHubClient github = createGitHubClient();

            Pattern replaceComment = null;
            if (replaceCommentPattern != null) {
                replaceComment = Pattern.compile(replaceCommentPattern);
            }

            github.setPullRequestComment(repo, newComment, pullRequestId, replaceComment, mojoVersion);
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
