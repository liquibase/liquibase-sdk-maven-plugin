package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.kohsuke.github.GHAsset;
import org.kohsuke.github.GHRelease;

import java.net.URL;

@Mojo(name = "install-release")
public class InstallReleaseMojo extends AbstractGitHubMojo {

    @Parameter(property = "liquibase.sdk.releaseTag", required = true)
    protected String releaseTag;

    public void execute() throws MojoExecutionException, MojoFailureException {

        for (String repo : getRepos()) {
            if (repo.equals("liquibase/liquibase-pro")) {
                log.debug("Nothing to install from liquibase-pro");
                continue;
            }

            log.info("Installing release from " + repo);

            try {
                GitHubClient github = new GitHubClient(githubToken, log);
                GHRelease release = github.getRelease(repo, releaseTag);
                for (GHAsset asset : release.listAssets()) {
                    if (!asset.getName().endsWith(".jar")) {
                        log.debug("Not installing " + asset.getName());
                        continue;
                    }

                    log.info("Installing " + asset.getName() + "...");
                    installToMavenCache(github.downloadArtifact(new URL(asset.getBrowserDownloadUrl())));
                }
            } catch (Exception e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }

}
