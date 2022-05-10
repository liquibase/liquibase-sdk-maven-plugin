package liquibase.sdk.maven.plugins;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.text.DateFormat;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;


/**
 * <p>Installs a snapshot build from the given branch as "0-SNAPSHOT".</p>
 */
@Mojo(name = "install-snapshot")
public class InstallSnapshotMojo extends AbstractMojo {

    private static final Logger log = LoggerFactory.getLogger(InstallSnapshotMojo.class);

    @Component
    private MavenSession mavenSession;

    @Component
    private BuildPluginManager pluginManager;

    /**
     * Github action repository name.
     */
    @Parameter(property = "liquibase.sdk.repo", defaultValue = "liquibase")
    protected String repo;

    /**
     * Github action repository owner.
     */
    @Parameter(property = "liquibase.sdk.repo.owner", defaultValue = "liquibase")
    protected String owner;

    /**
     * Branch name. If a pull request from a fork, use the syntax `fork_owner:branch`
     */
    @Parameter(property = "liquibase.sdk.branch", defaultValue = "master")
    protected String branch;

    /**
     * Github authentication token.
     */
    @Parameter(property = "liquibase.sdk.github.token")
    protected String githubToken;

    public void execute() throws MojoExecutionException, MojoFailureException {

        if (StringUtils.trimToNull(githubToken) == null) {
            throw new MojoFailureException("Missing github token\n" +
                    "Your github token is not set in liquibase.sdk.github.token.\n\n" +
                    "It can be set via any Maven property-setting mechanism, but the best is to add the following to your " + SystemUtils.getUserHome() + "/.m2/settings.xml file in the <profiles></profiles> section:\n" +
                    "\t<profile>\n" +
                    "\t\t<id>liquibase-sdk</id>\n" +
                    "\t\t<activation>\n" +
                    "\t\t\t<activeByDefault>true</activeByDefault>\n" +
                    "\t\t</activation>\n" +
                    "\t\t<properties>\n" +
                    "\t\t\t<liquibase.sdk.github.token>YOUR_TOKEN</liquibase.sdk.github.token>\n" +
                    "\t\t</properties>\n" +
                    "\t</profile>\n\n" +
                    "If you do not have a GitHub personal access token, you can create one at https://github.com/settings/tokens. It needs to be assigned the 'repo' scope");
        }

        log.info("Looking for " + branch + " from a run in " + owner + "/" + repo);

        try {
            GitHub github = GitHub.connectUsingOAuth(githubToken);
            if (github.isCredentialValid()) {
                log.debug("Successfully connected to github");
            } else {
                throw new MojoFailureException("Invalid github credentials. Check your liquibase.sdk.token property");
            }

            GHRepository repository = github.getRepository(owner + "/" + repo);
            log.debug("Successfully found repository " + repository.getHtmlUrl());

            final GHWorkflow workflow = repository.getWorkflow("build.yml");
            log.debug("Successfully found workflow " + workflow.getHtmlUrl());
            log.debug("Workflow state: " + workflow.getState());

            String headBranch = branch;
            String headOwner = "liquibase";
            if (headBranch.contains(":")) {
                final String[] split = headBranch.split(":", 2);
                headOwner = split[0];
                headBranch = split[1];
            }

            String headBranchFilename = headBranch.replaceAll("[^a-zA-Z0-9\\-_]", "_");

            GHWorkflowRun runToDownload = findRun(repository, workflow, headBranch, headOwner, true);

            if (runToDownload == null) {
                throw new MojoFailureException("Could not find successful build for " + workflow.getHtmlUrl() + " branch " + branch);
            }

            log.info("Downloading artifacts in build #" + runToDownload.getRunNumber() + " from " + DateFormat.getDateTimeInstance().format(runToDownload.getCreatedAt()) + " -- " + runToDownload.getHtmlUrl());

            File file = File.createTempFile("liquibase-artifacts-" + headBranchFilename, ".zip");
            file.deleteOnExit();
            boolean foundArchive = false;

            for (GHArtifact artifact : runToDownload.listArtifacts()) {
                if (artifact.getName().equals("liquibase-artifacts-" + headBranchFilename)) {
                    log.info("Downloading " + artifact.getName() + "...");

                    final URL url = artifact.getArchiveDownloadUrl();

                    //archive.download() threw timeout errors too often. So using httpClient instead
                    try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
                        HttpGet httpGet = new HttpGet(url.toURI());
                        httpGet.addHeader("Authorization", "token " + githubToken);

                        try (CloseableHttpResponse response = httpclient.execute(httpGet)) {
                            if (response.getCode() != 200) {
                                throw new MojoExecutionException("Non-200 response: " + response.getCode() + " " + response.getReasonPhrase());
                            }

                            try (OutputStream out = new FileOutputStream(file)) {
                                response.getEntity().writeTo(out);
                            }
                        }
                    }
                    foundArchive = true;
                } else {
                    log.debug("Not downloading " + artifact.getName());
                }
            }

            if (!foundArchive) {
                throw new MojoFailureException("Cannot find liquibase-artifacts-" + branch + ".zip");
            }

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


            log.info("Successfully installed " + branch + "#" + runToDownload.getRunNumber() + " as version 0-SNAPSHOT from " + owner + "/" + repo);

        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private GHWorkflowRun findRun(GHRepository repository, GHWorkflow workflow, String headBranch, String headOwner, boolean recentOnly) throws IOException {
        log.debug("Fetching recent workflow runs " + (recentOnly ? "(recent)" : "") + ".... ");
        GHWorkflowRunQueryBuilder queryBuilder = repository.queryWorkflowRuns();

        if (!recentOnly) {
            queryBuilder = queryBuilder.branch(headBranch);
        }

        final PagedIterator<GHWorkflowRun> runIterator = queryBuilder.list()
                .withPageSize(25)
                .iterator();
        log.debug("Fetching workflow runs....COMPLETE");

        log.debug("Finding most recent successful run...");
        GHWorkflowRun runToDownload = null;
        int page = 0;
        while (runIterator.hasNext()) {
            if (page++ > 1 && recentOnly) {
                //fall back to non-recent runs
                return findRun(repository, workflow, headBranch, headOwner, false);
            }

            runToDownload = runIterator.next();
            if (runToDownload.getWorkflowId() != workflow.getId()) {
                continue;
            }

            if (!runToDownload.getHeadBranch().equals(headBranch)) {
                continue;
            }

            if (!runToDownload.getHeadRepository().getOwnerName().equals(headOwner)) {
                log.info("Skipping " + headBranch + " from " + runToDownload.getHeadRepository().getOwnerName() + " because it's not from " + headOwner + "'s fork " + runToDownload.getHtmlUrl());
                continue;
            }

            if (runToDownload.getStatus() != GHWorkflowRun.Status.COMPLETED) {
                log.info("Skipping " + runToDownload.getStatus() + " build #" + runToDownload.getRunNumber() + " " + runToDownload.getHtmlUrl());
                continue;
            }

            if (runToDownload.getConclusion() != GHWorkflowRun.Conclusion.SUCCESS) {
                log.info("Skipping " + runToDownload.getConclusion() + " build #" + runToDownload.getRunNumber() + " " + runToDownload.getHtmlUrl());
                continue;
            }

            log.debug("Found run " + runToDownload.getName() + ": " + runToDownload.getStatus() + " -- " + runToDownload.getConclusion() + " " + " build #" + runToDownload.getRunNumber() + " " + runToDownload.getHtmlUrl());
            break;
        }
        return runToDownload;
    }

    private boolean shouldInstall(GHArtifact artifact) {
        return artifact.getName().equals("liquibase-jar-" + branch)
                || artifact.getName().equals("liquibase-maven-plugin-" + branch);
    }
}
