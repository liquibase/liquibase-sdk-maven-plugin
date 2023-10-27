package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import liquibase.sdk.util.ArchiveUtil;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;


/**
 * <p>Installs or replaces a CLI with the given branch search.</p>
 */
@Mojo(name = "install-snapshot-cli", requiresProject = false)
public class InstallSnapshotCliMojo extends AbstractGitHubMojo {

    /**
     * Branch name. If a pull request from a fork, use the syntax `fork_owner:branch`
     */
    @Parameter(property = "liquibase.sdk.branchSearch", defaultValue = "master")
    protected String branchSearch;

    @Parameter(property = "liquibase.sdk.skipFailedBuilds", defaultValue = "false")
    protected Boolean skipFailedBuilds;

    @Parameter(property = "liquibase.sdk.liquibaseHome", required = true)
    protected String liquibaseHome;

    @Parameter(property = "liquibase.sdk.allowInstall", defaultValue = "false")
    protected boolean allowInstall;

    @Parameter(property = "liquibase.sdk.workflowId")
    protected String workflowId;

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

        for (String repo : getRepos()) {
            log.info("Looking for " + branchSearch + " from a run in " + repo);

            try {
                GitHubClient github = new GitHubClient(githubToken, log);

                String matchingLabel = github.findMatchingBranch(repo, branchSearch);
                if (matchingLabel == null) {
                    throw new MojoFailureException("Could not find matching branch(es): " + branchSearch + " in " + repo);
                }
                log.info("Found matching branch: " + matchingLabel);

                if (repo.endsWith("/liquibase")) {
                    //replace everything in the CLI except liquibase-commercial.jar
                    String headBranchFilename = matchingLabel.replaceFirst(".*:", "").replaceAll("[^a-zA-Z0-9\\-_.]", "_");

                    File file = downloadArtifact(github, repo, matchingLabel, "liquibase-zip-" + headBranchFilename);

                    ArchiveUtil.unzipCli(file, liquibaseHomeDir, log, path -> {
                        if (path.getName().equals("internal/lib/liquibase-commercial.jar")) {
                            return !InstallSnapshotCliMojo.this.repo.contains("liquibase-pro");
                        }
                        return true;
                    }, null);
                } else {
                    //upgrading an extension
                    if (repo.equals("liquibase/liquibase-pro")) {
                        File file = downloadArtifact(github, repo, matchingLabel, "liquibase-commercial-modules");
                        ArchiveUtil.unzipCli(file, liquibaseHomeDir, log,
                                path -> path.getName().endsWith("liquibase-commercial-0-SNAPSHOT.jar"),
                                path -> {
                                    if (path.equals("liquibase-commercial-0-SNAPSHOT.jar")) {
                                        return "internal/lib/liquibase-commercial.jar";
                                    }
                                    return path;
                                });
                    } else {
                        throw new MojoExecutionException("Unknown repo: " + repo);
                    }
                }

            } catch (MojoExecutionException | MojoFailureException e) {
                throw e;
            } catch (Exception e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }

    private File downloadArtifact(GitHubClient github, String repo, String matchingLabel, String artifactName) throws IOException, MojoFailureException {
        File file = github.downloadArtifact(repo, matchingLabel, artifactName, GitHubClient.getWorkflowId(repo, workflowId), skipFailedBuilds);

        if (file == null) {
            throw new MojoFailureException("Cannot find " + artifactName + ".zip");
        }
        file.deleteOnExit();
        return file;
    }
}
