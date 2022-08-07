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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;


@Mojo(name = "download-release-artifacts")
public class DownloadReleaseArtifactsMojo extends AbstractGitHubMojo {

    @Parameter(property = "liquibase.sdk.releaseTag", required = true)
    protected String releaseTag;

    @Parameter(property = "liquibase.sdk.artifactPattern", required = true)
    protected String artifactPattern;

    @Parameter(property = "liquibase.sdk.downloadDirectory", required = true)
    protected String downloadDirectory;

    public void execute() throws MojoExecutionException, MojoFailureException {

        File downloadDirectory = new File(this.downloadDirectory);
        downloadDirectory.mkdirs();

        int downloaded = 0;

        if (releaseTag.matches("\\d\\.\\d\\.\\d")) {
            releaseTag = "v" + releaseTag;
        }

        for (String repo : getRepos()) {
            if (repo.equals("liquibase/liquibase-pro")) {
                log.debug("No releases in liquibase-pro");
                continue;
            }
            try {
                GitHubClient github = new GitHubClient(githubToken, log);

                GHRelease release = github.getRelease(repo, releaseTag);
                if (release == null) {
                    throw new MojoFailureException("Cannot find release " + releaseTag + " in " + repo);
                }

                for (GHAsset asset : release.listAssets()) {
                    if (ArchiveUtil.filenameMatches(asset.getName(), artifactPattern)) {
                        Path finalPath = new File(downloadDirectory, asset.getName()).toPath().normalize().toAbsolutePath();
                        log.info("Downloading " + finalPath + "...");

                        final URL url = new URL(asset.getBrowserDownloadUrl());

                        File tempFile = github.downloadArtifact(url);
                        Files.move(tempFile.toPath(), finalPath, StandardCopyOption.REPLACE_EXISTING);
                        downloaded++;
                    } else {
                        log.debug("Not downloading " + asset.getName());
                    }
                }


            } catch (MojoFailureException e) {
                throw e;
            } catch (Exception e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        if (downloaded == 0) {
            throw new MojoFailureException("Did not find any matching artifacts");
        }
    }
}
