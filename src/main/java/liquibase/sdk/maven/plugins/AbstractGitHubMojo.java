package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

abstract class AbstractGitHubMojo extends AbstractMojo {

    protected final Logger log;


    public AbstractGitHubMojo() {
        this.log = LoggerFactory.getLogger(getClass());
    }

    /**
     * Github authentication token.
     */
    @Parameter(property = "liquibase.sdk.github.token")
    protected String githubToken;

    /**
     * Github action repository name.
     */
    @Parameter(property = "liquibase.sdk.repo", defaultValue = "liquibase/liquibase")
    protected String repo;

    protected GitHubClient createGitHubClient() throws IOException {
        return new GitHubClient(githubToken, log);
    }

}
