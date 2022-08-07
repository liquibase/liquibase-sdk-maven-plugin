package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import liquibase.sdk.util.ArchiveUtil;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.kohsuke.github.GHAsset;
import org.kohsuke.github.GHRelease;

import java.io.File;
import java.net.URL;


/**
 * <p>Installs or replaces a CLI with the given release.</p>
 */
@Mojo(name = "install-release-cli")
public class InstallReleaseCliMojo extends AbstractGitHubMojo {

    @Parameter(property = "liquibase.sdk.releaseTag", required = true)
    protected String releaseTag;
    @Parameter(property = "liquibase.sdk.liquibaseHome", required = true)
    protected String liquibaseHome;

    @Parameter(property = "liquibase.sdk.allowInstall", defaultValue = "false")
    protected boolean allowInstall;

    public void execute() throws MojoExecutionException, MojoFailureException {
        File liquibaseHomeDir = new File(liquibaseHome);
        if (liquibaseHomeDir.exists()) {
            if (!liquibaseHomeDir.isDirectory()) {
                throw new MojoFailureException("LiquibaseHome " + liquibaseHome + " is not a directory");
            }

            if (!new File(liquibaseHomeDir, "liquibase.bat").exists()) {
                throw new MojoFailureException("LiquibaseHome " + liquibaseHome + " is not a liquibase home");
            }
        } else {
            if (allowInstall) {
                log.info("LiquibaseHome " + liquibaseHome + " does not exist. Installing new version");
                liquibaseHomeDir.mkdirs();
            } else {
                throw new MojoFailureException("LiquibaseHome " + liquibaseHome + " does not exist. To install to a new directory, set allowInstall=true");
            }
        }

        if (releaseTag.matches("\\d\\.\\d\\.\\d")) {
            releaseTag = "v" + releaseTag;
        }

        String version = releaseTag.replaceFirst("^v", "");

        for (String repo : getRepos()) {
            if (repo.equals("liquibase/eliquibase-pro")) {
                log.debug("Nothing to install from liquibase-pro");
                continue;
            }

            try {
                GitHubClient github = new GitHubClient(githubToken, log);

                GHRelease release = github.getRelease(repo, releaseTag);
                if (release == null) {
                    throw new MojoFailureException("Cannot find release " + releaseTag + " in " + repo);
                }

                String wantedAsset = "liquibase-" + version + ".zip";
                GHAsset zipAsset = null;
                for (GHAsset asset : release.listAssets()) {
                    if (asset.getName().equals(wantedAsset)) {
                        zipAsset = asset;
                    } else {
                        log.debug("Not installing " + asset.getName());
                    }
                }

                if (zipAsset == null) {
                    throw new MojoFailureException("Could not find " + wantedAsset);
                }

                File file = github.downloadArtifact(new URL(zipAsset.getBrowserDownloadUrl()));
                ArchiveUtil.unzipCli(file, liquibaseHomeDir, log, null, null);
            } catch (Exception e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }
}
