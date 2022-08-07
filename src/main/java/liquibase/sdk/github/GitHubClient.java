package liquibase.sdk.github;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.kohsuke.github.*;
import org.slf4j.Logger;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.DateFormat;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

public class GitHubClient {

    private final GitHub github;
    private final Logger log;
    private final String githubToken;

    /**
     * Creates testing client
     */
    GitHubClient(GitHub github, Logger log) {
        this.github = github;
        this.log = log;
        this.githubToken = null;
    }


    public GitHubClient(String githubToken, Logger log) throws IOException {
        this.log = log;
        this.githubToken = githubToken;
        if (StringUtils.trimToNull(githubToken) == null) {
            throw new IOException("Missing github token\n" +
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

        github = GitHub.connectUsingOAuth(githubToken);
        if (github.isCredentialValid()) {
            log.debug("Successfully connected to github");
        } else {
            throw new IOException("Invalid github credentials. Check your liquibase.sdk.token property");
        }
    }

    public GHRelease getRelease(String repo, String tagName) throws IOException {
        GHRepository repository = getRepository(repo);
        log.debug("Successfully found repository " + repository.getHtmlUrl());
        return repository.getReleaseByTagName(tagName);
    }

    /**
     * Returns null if no builds match
     */
    public String findMatchingBranch(String repo, String... branches) throws IOException {
        if (branches.length == 1 && branches[0].contains(",")) {
            branches = branches[0].split("\\s*,\\s*");
        }

        GHRepository repository = getRepository(repo);
        log.debug("Successfully found repository " + repository.getHtmlUrl());

        Map<String, GHPullRequest> pullRequests = getAllOpenPullRequests(repository);

        for (String branch : branches) {
            Set<String> branchVariations = new LinkedHashSet<>();
            branchVariations.add(branch);

            if (branch.contains(":")) {
                if (branch.endsWith(":master") || !branch.endsWith(":main")) {
                    branchVariations.add(branch.replace(":", "-")); //match what gets created by github CLI instructions
                    branchVariations.add(branch.replace(".+:", "")); //match branch without fork name
                }
            }

            for (String branchVariation : branchVariations) {
                if (useLocalBranch(branchVariation)) {
                    try {
                        return repository.getName() + ":" + repository.getBranch(branchVariation).getName();
                    } catch (GHFileNotFoundException e) {
                        log.info("No branch '" + branchVariation + "' in " + repository.getHtmlUrl());
                    }
                } else {
                    //check for corresponding PR
                    GHPullRequest pr = pullRequests.get(branchVariation);
                    if (pr == null) {
                        for (GHPullRequest otherPr : pullRequests.values()) {
                            final String otherBranchName = otherPr.getHead().getRef();
                            final String otherLabel = otherPr.getHead().getLabel();
                            if (otherBranchName.equals(branchVariation)
                                    || otherLabel.replace(":", "-").equals(branchVariation)
                            ) {
                                return otherPr.getHead().getLabel();
                            }
                        }

                        log.info("No PR for branch '" + branchVariation + "' in " + repository.getHtmlUrl());
                    } else {
                        return pr.getHead().getLabel();
                    }
                }
            }
        }

        return null;
    }

    private GHRepository getRepository(String repo) throws IOException {
        if (!repo.contains("/")) {
            repo = "liquibase/" + repo;
        }

        return github.getRepository(repo);
    }

    protected Map<String, GHPullRequest> getAllOpenPullRequests(GHRepository repository) throws IOException {
        Map<String, GHPullRequest> pullRequests = new HashMap<>();

        repository.queryPullRequests().state(GHIssueState.OPEN).list().withPageSize(50).toList().forEach(pr -> {
            pullRequests.put(pr.getHead().getLabel(), pr);
        });

        return pullRequests;
    }

    private boolean useLocalBranch(String branchVariation) {
        return branchVariation.equals("master") || branchVariation.equals("main");
    }

    /**
     * Returns null if no builds match
     */
    public GHWorkflowRun findLastBuild(String repo, BuildFilter buildFilter) throws IOException {
        GHRepository repository = getRepository(repo);
        log.debug("Successfully found repository " + repository.getHtmlUrl());

        final GHWorkflow workflow = repository.getWorkflow("build.yml");
        log.debug("Successfully found workflow " + workflow.getHtmlUrl());
        log.debug("Workflow state: " + workflow.getState());

        return findRun(repository, workflow, buildFilter, true, null);
    }

    private GHWorkflowRun findRun(GHRepository repository, GHWorkflow workflow, BuildFilter buildFilter, boolean recentOnly, GHWorkflowRun foundFailedRun) throws IOException {

        log.debug("Fetching recent workflow runs " + (recentOnly ? "(recent)" : "") + ".... ");
        GHWorkflowRunQueryBuilder queryBuilder = repository.queryWorkflowRuns();

        if (!recentOnly) {
            queryBuilder = queryBuilder.branch(buildFilter.getBranch());
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
                return findRun(repository, workflow, buildFilter, false, foundFailedRun);
            }

            runToDownload = runIterator.next();
            if (foundFailedRun != null && foundFailedRun.getId() == runToDownload.getId()) {
                continue;
            }

            if (runToDownload.getWorkflowId() != workflow.getId()) {
                continue;
            }

            if (!runToDownload.getHeadBranch().equals(buildFilter.getBranch())) {
                continue;
            }

            if (!repository.getName().equals("liquibase-pro") && !runToDownload.getHeadRepository().getOwnerName().equals(buildFilter.getFork())) {
                log.info("Skipping " + buildFilter.getBranch() + " from " + runToDownload.getHeadRepository().getOwnerName() + " because it's not from " + buildFilter.fork + "'s fork " + runToDownload.getHtmlUrl());
                continue;
            }

            if (runToDownload.getStatus() != GHWorkflowRun.Status.COMPLETED) {
                log.info("Skipping " + runToDownload.getStatus() + " build #" + runToDownload.getRunNumber() + " from " + DateFormat.getDateTimeInstance().format(runToDownload.getCreatedAt()) + " " + runToDownload.getHtmlUrl());
                continue;
            }

            if (runToDownload.getConclusion() == GHWorkflowRun.Conclusion.SUCCESS) {
                return runToDownload;
            } else {
                if (buildFilter.skipFailedBuilds) {
                    log.debug("Found run " + runToDownload.getName() + ": " + runToDownload.getStatus() + " -- " + runToDownload.getConclusion() + " " + " build #" + runToDownload.getRunNumber() + " " + runToDownload.getHtmlUrl());
                    log.info("Skipping unsuccessful " + runToDownload.getConclusion() + " build #" + runToDownload.getRunNumber() + " " + runToDownload.getHtmlUrl());
                } else {
                    //somtimes there are multiple runs for a single build, find one of them that failed by continuing to search builds until we get one that failed from a different run
                    if (foundFailedRun == null) {
                        //first failure we've seen from this run. Mark that we've seen it
                        log.debug("Found failed run " + runToDownload.getId() + " but seeing if there is another build in the same run that passed...");
                        foundFailedRun = runToDownload;
                    } else if (foundFailedRun.getRunNumber() == runToDownload.getRunNumber()) {
                        if (foundFailedRun.getHeadCommit().getId().equals(runToDownload.getHeadCommit().getId())) {
                            break; //moved on to older builds
                        } else {
                            log.debug("Found another failed run for " + runToDownload.getRunNumber());
                        }
                    }
                }
            }
        }

        if (foundFailedRun == null) {
            return null;
        } else {
            if (buildFilter.skipFailedBuilds) {
                throw new IOException("Latest build #" + foundFailedRun + " " + foundFailedRun.getHtmlUrl() + " failed");
            } else {
                log.debug("Found run " + foundFailedRun.getName() + ": " + foundFailedRun.getStatus() + " -- " + foundFailedRun.getConclusion() + " " + " build #" + foundFailedRun.getRunNumber() + " " + foundFailedRun.getHtmlUrl());
                return foundFailedRun;
            }
        }
    }

    public File downloadArtifact(String repo, String branchLabel, String artifactName, boolean skipFailedBuilds) throws IOException {
        GHWorkflowRun runToDownload = this.findLastBuild(repo, new GitHubClient.BuildFilter(repo, branchLabel, skipFailedBuilds));

        if (runToDownload == null) {
            throw new IOException("Could not find successful build for branch " + branchLabel);
        }


        log.info("Downloading artifacts in build #" + runToDownload.getRunNumber() + " originally ran at " + DateFormat.getDateTimeInstance().format(runToDownload.getCreatedAt()) + " -- " + runToDownload.getHtmlUrl());

        for (GHArtifact artifact : runToDownload.listArtifacts()) {
            if (artifact.getName().equals(artifactName)) {
                log.info("Downloading " + artifact.getName() + "...");

                final URL url = artifact.getArchiveDownloadUrl();

                return downloadArtifact(url);
            } else {
                log.debug("Not downloading " + artifact.getName());
            }
        }

        return null;
    }

    public File downloadArtifact(URL url) throws IOException {
        String extension = url.getPath().replaceFirst(".*\\.", "");
        if (extension.equals(url.getPath())) {
            if (url.getPath().endsWith("/zip")) {
                extension = "zip";
            } else {
                extension = "tmp";
            }
        }
        File file = File.createTempFile("liquibase-sdk-" + url.getPath().replaceFirst(".*/", "").replaceAll("\\W", "_") + "-", "." + extension);

        //archive.download() threw timeout errors too often. So using httpClient instead
        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(url.toURI());
            httpGet.addHeader("Authorization", "token " + githubToken);

            try (CloseableHttpResponse response = httpclient.execute(httpGet)) {
                if (response.getCode() != 200) {
                    throw new IOException("Non-200 response: " + response.getCode() + " " + response.getReasonPhrase());
                }

                try (OutputStream out = new FileOutputStream(file)) {
                    response.getEntity().writeTo(out);
                }
            }
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        return file;
    }

    public void setCommitStatus(String repo, String sha1, GHCommitState statusState, String statusContext, String statusDescription, String statusUrl) throws IOException {
        GHRepository repository = getRepository(repo);
        log.debug("Successfully found repository " + repository.getHtmlUrl());

        repository.createCommitStatus(sha1, statusState, statusUrl, statusDescription, statusContext);
    }

    public Properties getInstalledBuildProperties() throws IOException {
        File libraryJar = new File(System.getProperty("user.home") + "/.m2/repository/org/liquibase/liquibase-core/0-SNAPSHOT/liquibase-core-0-SNAPSHOT.jar");
        if (!libraryJar.exists()) {
            throw new IOException("Could not find jar for liquibase-core at " + libraryJar.getAbsolutePath());
        }

        try (final FileInputStream fileInputStream = new FileInputStream(libraryJar);
             final JarInputStream jarInputStream = new JarInputStream(fileInputStream)) {
            JarEntry entry = jarInputStream.getNextJarEntry();
            while (entry != null) {
                if (entry.getName().equals("liquibase.build.properties")) {
                    final Properties properties = new Properties();
                    properties.load(jarInputStream);

                    for (Map.Entry<Object, Object> property : properties.entrySet()) {
                        log.debug("Found property " + property.getKey() + "=" + property.getValue());
                    }

                    return properties;

                }
                entry = jarInputStream.getNextJarEntry();
            }
        }

        return null;
    }


    public static class BuildFilter {
        private String fork;
        private String branch;
        private final boolean skipFailedBuilds;

        /**
         * Branch can be either the branch name without a fork, or in `fork:branchName` format.
         */
        public BuildFilter(String repo, String branch, boolean skipFailedBuilds) {
            this.skipFailedBuilds = skipFailedBuilds;
            this.branch = branch;
            this.fork = repo;
            if (this.fork.contains("/")) {
                this.fork = repo.split("/")[1];
            }
            if (!this.fork.equals("liquibase-pro")) {
                this.fork = "liquibase";
            }

            if (this.branch.contains(":")) {
                final String[] split = this.branch.split(":", 2);
                this.fork = split[0];
                this.branch = split[1];
            }
        }

        public String getBranch() {
            return branch;
        }

        public String getFork() {
            return fork;
        }
    }

    public enum BuildStatusFilter {
        SUCCESS;
    }
}
