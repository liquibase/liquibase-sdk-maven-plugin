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

import java.io.*;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


/**
 * <p>Installs or replaces a CLI with the given branch search.</p>
 */
@Mojo(name = "install-snapshot-cli")
public class InstallSnapshotCliMojo extends AbstractGitHubMojo {

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

        for (String repo : getRepos()) {
            log.info("Looking for " + branchSearch + " from a run in " + repo);

            try {
                GitHubClient github = new GitHubClient(githubToken, log);

                String matchingLabel = github.findMatchingBranch(repo, branchSearch);
                if (matchingLabel == null) {
                    throw new MojoFailureException("Could not find matching branch(es): " + branchSearch + " in " + repo);
                }
                log.info("Found matching branch: " + matchingLabel);

                if (repo.equals("liquibase")) {
                    //replace everything in the CLI except liquibase-commercial.jar
                    String headBranchFilename = matchingLabel.replaceFirst(".*:", "").replaceAll("[^a-zA-Z0-9\\-_]", "_");

                    File file = downloadArtifact(github, repo, matchingLabel, "liquibase-zip-" + headBranchFilename);

                    try (ZipFile zipFile = new ZipFile(file)) {
                        Enumeration<? extends ZipEntry> entries = zipFile.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            File outFile = new File(liquibaseHomeDir, entry.getName());
                            boolean newFile = !outFile.exists();
                            if (entry.isDirectory()) {
                                outFile.mkdirs();
                            } else {
                                if (entry.getName().equals("internal/lib/liquibase-commercial.jar") && this.repo.contains("liquibase-pro")) {
                                    log.info("Skipped " + outFile.getAbsolutePath());
                                    continue; //don't overwrite what might already have been installed just now
                                }
                                try (InputStream in = zipFile.getInputStream(entry);
                                     OutputStream out = Files.newOutputStream(outFile.toPath())) {
                                    IOUtils.copy(in, out);
                                }

                                if (newFile) {
                                    log.info("Created " + outFile.getAbsolutePath());
                                } else {
                                    log.info("Replaced " + outFile.getAbsolutePath());
                                    if (!outFile.getName().equals("liquibase")) {
                                        outFile.setExecutable(true);
                                    }
                                }

                            }
                        }
                    }
                } else {
                    String artifactName;
                    String replaceSourceFile;
                    String replaceTargetFile;

                    //upgrading an extension
                    if (repo.equals("liquibase-pro")) {
                        artifactName = "liquibase-commercial-modules";
                        replaceSourceFile = "liquibase-commercial-0-SNAPSHOT.jar";
                        replaceTargetFile = "liquibase-commercial.jar";

                        File file = downloadArtifact(github, repo, matchingLabel, artifactName);

                        try (ZipFile zipFile = new ZipFile(file)) {
                            Enumeration<? extends ZipEntry> entries = zipFile.entries();
                            while (entries.hasMoreElements()) {
                                ZipEntry entry = entries.nextElement();
                                if (entry.getName().equals(replaceSourceFile)) {
                                    File outFile = new File(liquibaseHomeDir, "internal/lib/" + replaceTargetFile);
                                    boolean newFile = !outFile.exists();

                                    outFile.getParentFile().mkdirs();
                                    try (InputStream in = zipFile.getInputStream(entry);
                                         OutputStream out = Files.newOutputStream(outFile.toPath())) {
                                        IOUtils.copy(in, out);
                                    }

                                    if (newFile) {
                                        log.info("Created " + outFile.getAbsolutePath());
                                    } else {
                                        log.info("Replaced " + outFile.getAbsolutePath());
                                    }
                                }
                            }
                        }
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
        File file = github.downloadArtifact(repo, matchingLabel, artifactName, skipFailedBuilds);

        if (file == null) {
            throw new MojoFailureException("Cannot find " + artifactName + ".zip");
        }
        file.deleteOnExit();
        return file;
    }
}
