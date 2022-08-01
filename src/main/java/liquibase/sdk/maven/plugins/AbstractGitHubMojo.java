package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
     * Github action repository name. Can set multiple values by comma separating them. Default org is "liquibase"
     */
    @Parameter(property = "liquibase.sdk.repo", defaultValue = "liquibase/liquibase")
    protected String repo;

    protected GitHubClient createGitHubClient() throws IOException {
        return new GitHubClient(githubToken, log);
    }

    /**
     * Returns the value from "repo" but split on commas.
     */
    protected List<String> getRepos() {
        List<String> returnList = new ArrayList<>();
        for (String name : repo.split(",")) {

            returnList.add(name);
        }
        return returnList;
    }

    /**
     * Returns the value from "repo"
     *
     * @throws IllegalArgumentException if multiple repos were specified
     */
    protected String getRepo() {
        List<String> repos = getRepos();
        if (repos.size() > 1) {
            throw new IllegalArgumentException("Goal does not support multiple repos");
        }
        return repos.get(0);
    }
}
