package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

abstract class AbstractGitHubMojo extends AbstractMojo {

    protected final Logger log;


    public AbstractGitHubMojo() {
        this.log = LoggerFactory.getLogger(getClass());
    }

    @Component
    private MavenSession mavenSession;

    @Component
    private BuildPluginManager pluginManager;

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
     * DISABLED: liquibase-pro repos are filtered out
     */
    protected List<String> getRepos() {
        List<String> returnList = new ArrayList<>();
        for (String name : repo.split(",")) {
            if (!name.contains("/")) {
                name = "liquibase/" + name;
            }
            // DISABLED: Filter out liquibase-pro repositories
            if (name.equals("liquibase/liquibase-pro") || name.equals("liquibase-pro")) {
                log.warn("Skipping liquibase-pro repository (liquibase-pro operations are disabled)");
                continue;
            }
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

    protected void installToMavenCache(File entryFile) throws MojoExecutionException {
        executeMojo(
                plugin(
                        groupId("org.apache.maven.plugins"),
                        artifactId("maven-install-plugin"),
                        version("3.0.0-M1")
                ),
                goal("install-file"),
                configuration(
                        element(name("file"), entryFile.getAbsolutePath())
                ),
                executionEnvironment(
                        mavenSession,
                        pluginManager
                )
        );
    }
}
