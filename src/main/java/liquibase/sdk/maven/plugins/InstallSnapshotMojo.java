package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import org.apache.commons.io.IOUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;


/**
 * <p>Installs a snapshot build from the given branch as "0-SNAPSHOT".</p>
 */
@Mojo(name = "install-snapshot")
public class InstallSnapshotMojo extends AbstractGitHubMojo {

    @Component
    private MavenSession mavenSession;

    @Component
    private BuildPluginManager pluginManager;

    /**
     * Branch name. If a pull request from a fork, use the syntax `fork_owner:branch`
     */
    @Parameter(property = "liquibase.sdk.branchSearch", defaultValue = "master")
    protected String branchSearch;

    @Parameter(property = "liquibase.sdk.skipFailedBuilds", defaultValue = "false")
    protected Boolean skipFailedBuilds;

    public void execute() throws MojoExecutionException, MojoFailureException {

        log.info("Looking for " + branchSearch + " from a run in " +  repo);

        try {
            GitHubClient github = new GitHubClient(githubToken, log);

            String matchingLabel = github.findMatchingBranch(repo, branchSearch);
            log.info("Found matching branch: " + matchingLabel);

            String headBranchFilename = matchingLabel.replaceFirst(".*:", "").replaceAll("[^a-zA-Z0-9\\-_]", "_");

            final String artifactName = "liquibase-artifacts-" + headBranchFilename;
            File file = github.downloadArtifact(repo, matchingLabel, artifactName, skipFailedBuilds);

            if (file == null) {
                throw new MojoFailureException("Cannot find " + artifactName + ".zip");
            }
            file.deleteOnExit();

            try (java.util.zip.ZipFile zipFile = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".jar")) {
                        log.info("Installing " + entry.getName() + "...");

                        File entryFile = File.createTempFile(entry.getName(), ".jar");
                        entryFile.deleteOnExit();
                        try (InputStream in = zipFile.getInputStream(entry);
                             OutputStream out = new FileOutputStream(entryFile)) {
                            IOUtils.copy(in, out);
                        }
                        log.debug("Saved " + entry.getName() + " as " + entryFile.getAbsolutePath());

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
            }

            log.info("Successfully installed " + branchSearch + " as version 0-SNAPSHOT from " + repo);
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
