package liquibase.dev.maven.plugins;

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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.text.DateFormat;

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
     * Repository name.
     */
    @Parameter(property = "liquibase.dev.repo", defaultValue = "liquibase")
    protected String repo;

    /**
     * Repository owner.
     */
    @Parameter(property = "liquibase.dev.repo.owner", defaultValue = "liquibase")
    protected String owner;

    /**
     * Branch name.
     */
    @Parameter(property = "liquibase.dev.branch", defaultValue = "master")
    protected String branch;

    /**
     * Github authentication token.
     */
    @Parameter(property = "liquibase.dev.github.token")
    protected String githubToken;

    public void execute() throws MojoExecutionException, MojoFailureException {

        if (StringUtils.trimToNull(githubToken) == null) {
            throw new MojoFailureException("Missing github token\n" +
                    "Your github token is not set in liquibase.dev.github.token.\n\n" +
                    "It can be set via any Maven property-setting mechanism, but the best is to add the following to your " + SystemUtils.getUserHome() + "/.m2/settings.xml file in the <profiles></profiles> section:\n" +
                    "\t<profile>\n" +
                    "\t\t<id>liquibase-dev</id>\n" +
                    "\t\t<activation>\n" +
                    "\t\t\t<activeByDefault>true</activeByDefault>\n" +
                    "\t\t</activation>\n" +
                    "\t\t<properties>\n" +
                    "\t\t\t<liquibase.dev.github.token>YOUR_TOKEN</liquibase.dev.github.token>\n" +
                    "\t\t</properties>\n" +
                    "\t</profile>\n\n" +
                    "If you do not have a GitHub personal access token, you can create one at https://github.com/settings/tokens. It needs to be assigned the 'repo' scope");
        }

        log.info("Looking for " + owner + "/" + repo + ":" + branch);

        try {
            GitHub github = GitHub.connectUsingOAuth(githubToken);
            if (github.isCredentialValid()) {
                log.debug("Successfully connected to github");
            } else {
                throw new MojoFailureException("Invalid github credentials. Check your liquibase.dev.token property");
            }

            GHRepository repository = github.getRepository(owner + "/" + repo);
            log.debug("Successfully found repository " + repository.getHtmlUrl());

            final GHWorkflow workflow = repository.getWorkflow("build.yml");
            log.debug("Successfully found workflow " + workflow.getHtmlUrl());
            log.debug("Workflow state: " + workflow.getState());

            log.debug("Fetching workflow runs.... ");
            final PagedIterator<GHWorkflowRun> runIterator = repository.queryWorkflowRuns()
                    .branch(branch)
                    .list()
                    .iterator();
            log.debug("Fetching workflow runs....COMPLETE");

            log.debug("Finding most recent successful run...");
            GHWorkflowRun runToDownload = null;
            while (runIterator.hasNext()) {
                runToDownload = runIterator.next();
                if (runToDownload.getWorkflowId() != workflow.getId()) {
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

            if (runToDownload == null) {
                throw new MojoFailureException("Could not find successful build for " + workflow.getHtmlUrl() + " branch " + branch);
            }

            log.info("Downloading artifacts in build #" + runToDownload.getRunNumber() + " from " + DateFormat.getDateTimeInstance().format(runToDownload.getCreatedAt()) + " -- " + runToDownload.getHtmlUrl());

            for (GHArtifact artifact : runToDownload.listArtifacts()) {
                if (shouldInstall(artifact)) {
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

                            File file = File.createTempFile(artifact.getName(), ".jar");
                            file.deleteOnExit();
                            try (OutputStream out = new FileOutputStream(file)) {
                                response.getEntity().writeTo(out);
                            }

                            log.info("Installing " + artifact.getName() + "...");
                            executeMojo(
                                    plugin(
                                            groupId("org.apache.maven.plugins"),
                                            artifactId("maven-install-plugin"),
                                            version("3.0.0-M1")
                                    ),
                                    goal("install-file"),
                                    configuration(
                                            element(name("file"), file.getAbsolutePath())
                                    ),
                                    executionEnvironment(
                                            mavenSession,
                                            pluginManager
                                    )
                            );
                        }
                    }
                } else {
                    log.debug("Not installing " + artifact.getName());
                }
            }

            log.info("Successfully installed " + owner + "/" + repo + ":" + branch + "#" + runToDownload.getRunNumber() + " as version 0-SNAPSHOT");

        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private boolean shouldInstall(GHArtifact artifact) {
        return artifact.getName().equals("liquibase-jar-" + branch)
                || artifact.getName().equals("liquibase-maven-plugin-" + branch);
    }
}
