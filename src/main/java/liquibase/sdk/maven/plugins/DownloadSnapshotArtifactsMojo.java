package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import liquibase.sdk.util.ArchiveUtil;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.kohsuke.github.GHArtifact;
import org.kohsuke.github.GHWorkflowRun;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;


@Mojo(name = "download-snapshot-artifacts")
public class DownloadSnapshotArtifactsMojo extends AbstractGitHubMojo {

    /**
     * Branch name. If a pull request from a fork, use the syntax `fork_owner:branch`
     */
    @Parameter(property = "liquibase.sdk.branchSearch", defaultValue = "master")
    protected String branchSearch;

    @Parameter(property = "liquibase.sdk.skipFailedBuilds", defaultValue = "false")
    protected Boolean skipFailedBuilds;

    @Parameter(property = "liquibase.sdk.artifactPattern", required = true)
    protected String artifactPattern;

    @Parameter(property = "liquibase.sdk.downloadDirectory", required = true)
    protected String downloadDirectory;

    public void execute() throws MojoExecutionException, MojoFailureException {

        File downloadDirectory = new File(this.downloadDirectory);
        downloadDirectory.mkdirs();

        int downloaded = 0;
        for (String repo : getRepos()) {
            log.info("Looking for " + branchSearch + " from a run in " + repo);

            try {
                GitHubClient github = new GitHubClient(githubToken, log);

                String matchingLabel = github.findMatchingBranch(repo, branchSearch);
                if (matchingLabel == null) {
                    throw new MojoFailureException("Could not find matching branch(es): " + branchSearch + " in " + repo);
                }
                log.info("Found matching branch: " + matchingLabel);

                GHWorkflowRun runToDownload = github.findLastBuild(repo, new GitHubClient.BuildFilter(repo, matchingLabel, skipFailedBuilds));

                if (runToDownload == null) {
                    throw new IOException("Could not find successful build for branch " + matchingLabel);
                }

                log.info("Downloading artifacts in build #" + runToDownload.getRunNumber() + " originally ran at " + DateFormat.getDateTimeInstance().format(runToDownload.getCreatedAt()) + " -- " + runToDownload.getHtmlUrl());

                for (GHArtifact artifact : runToDownload.listArtifacts()) {
                    String finalArtifactName = artifact.getName();
                    if (!finalArtifactName.endsWith(".zip")) {
                        finalArtifactName = finalArtifactName + ".zip";
                    }

                    if (ArchiveUtil.filenameMatches(artifact.getName(), artifactPattern) || ArchiveUtil.filenameMatches(finalArtifactName, artifactPattern)) {
                        Path finalPath = new File(downloadDirectory, finalArtifactName).toPath().normalize().toAbsolutePath();
                        log.info("Downloading " + finalPath + "...");

                        final URL url = artifact.getArchiveDownloadUrl();

                        File tempFile = github.downloadArtifact(url);
                        Files.move(tempFile.toPath(), finalPath, StandardCopyOption.REPLACE_EXISTING);
                        downloaded++;
                    } else {
                        log.debug("Not downloading " + artifact.getName());
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
