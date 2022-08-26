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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

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
            branch = this.simplifyBranch(branch);
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
                        return repository.getOwnerName() + ":" + repository.getBranch(branchVariation).getName();
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

    public static String simplifyBranch(String branch) {
        if (branch == null) {
            return null;
        }
        return branch.replace("refs/heads/", "")
                .replace("refs/heads/tags", "");
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
    public GHWorkflowRun findLastBuild(String repo, BuildFilter buildFilter, String workflowId) throws IOException {
        GHRepository repository = getRepository(repo);
        log.debug("Successfully found repository " + repository.getHtmlUrl());

        final GHWorkflow workflow = repository.getWorkflow(workflowId);
        log.debug("Successfully found workflow " + workflow.getHtmlUrl());
        log.debug("Workflow state: " + workflow.getState());

        return findRun(repository, workflow, buildFilter, true, null);
    }

    /**
     * Returns null if no builds match
     */
    public GHWorkflowRun findBuild(String repo, long runId) throws IOException {
        GHRepository repository = getRepository(repo);
        log.debug("Successfully found repository " + repository.getHtmlUrl());

        GHWorkflowRun workflowRun = repository.getWorkflowRun(runId);
        log.debug("Successfully found run " + runId);

        return workflowRun;
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

            if (!runToDownload.getHeadRepository().getOwnerName().equals(buildFilter.fork)) {
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

    public File downloadArtifact(String repo, String branchLabel, String artifactName, String workflowId, boolean skipFailedBuilds) throws IOException {
        GHWorkflowRun runToDownload = this.findLastBuild(repo, new GitHubClient.BuildFilter(repo, branchLabel, skipFailedBuilds), workflowId);

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

    public void setPullRequestComment(String repo, String newComment, String pullRef, Pattern replaceComment, String mojoVersion) throws IOException {
        newComment = newComment
                .replace("\\n", "\n")
                .replace("\\t", "\t");

        GHRepository repository = getRepository(repo);
        log.debug("Successfully found repository " + repository.getHtmlUrl());

        GHPullRequest pullRequest;
        if (pullRef.startsWith("#")) {
            pullRequest = repository.getPullRequest(Integer.parseInt(pullRef.substring(1)));
        } else {
            List<GHPullRequest> prs = repository.queryPullRequests().head(pullRef).state(GHIssueState.OPEN).list().toList();
            if (prs.size() == 0) {
                throw new RuntimeException("Cannot find open PR for branch " + pullRef);
            } else if (prs.size() > 1) {
                throw new RuntimeException("Found " + prs.size() + " PRs for branch " + pullRef);
            } else {
                pullRequest = prs.get(0);
            }
        }

        if (newComment.equals("BUILD_TESTING")) {
            log.info("Generating 'BUILD_TESTING' comment...");
            GHWorkflowRun lastBuild = this.findLastBuild(repo, new BuildFilter(repo, pullRequest.getBase().getRef(), false), getWorkflowId(repo, null));

            newComment = "### Testing These Changes\n" +
                    "To test this PR, use the artifacts attached to the [latest CI build](" + lastBuild.getHtmlUrl() + "#artifacts)\n" +
                    "\n" +
                    "#### Artifacts Available:\n" +
                    "- __" + repository.getName() + "-artifacts:__ Zip containing the .jar file to test\n" +
                    "- __test-reports-*:__ Detailed automated test results\n" +
                    "\n" +
                    "#### Download with liquibase-sdk-maven-plugin\n" +
                    "Alternately, you can use the [Liquibase SDK Maven Plugin](https://mvnrepository.com/artifact/org.liquibase.ext/liquibase-sdk-maven-plugin)\n\n" +
                    "##### Download the artifacts\n" +
                    "```" +
                    "mvn org.liquibase.ext:liquibase-sdk-maven-plugin:" + mojoVersion + ":download-snapshot-artifacts -Dliquibase.sdk.repo=" + repository.getFullName() + " -Dliquibase.sdk.branchSearch=" + pullRequest.getHead().getLabel() + " -Dliquibase.sdk.downloadDirectory=download -Dliquibase.sdk.artifactPattern=*-artifacts -Dliquibase.sdk.unzipArtifacts=true" +
                    "```\n" +
                    "##### Install to your local maven cache\n" +
                    "```" +
                    "mvn  org.liquibase.ext:liquibase-sdk-maven-plugin:" + mojoVersion + ":install-snapshot -Dliquibase.sdk.repo=" + repository.getFullName() + " -Dliquibase.sdk.branchSearch=" + pullRequest.getHead().getLabel() +
                    "```\n" +
                    "";
            replaceComment = Pattern.compile("^#+ Testing These Changes");
        }


        if (replaceComment == null) {
            log.info("Creating new comment on " + pullRequest.getHtmlUrl());
            pullRequest.comment(newComment);
        } else {
            AtomicBoolean updated = new AtomicBoolean(false);
            String finalNewComment = newComment;

            Pattern finalReplaceComment = replaceComment;
            pullRequest.listComments().toList().forEach(comment -> {
                if (finalReplaceComment.matcher(comment.getBody()).find()) {
                    try {
                        log.info("Updating comment on " + pullRequest.getHtmlUrl());
                        comment.update(finalNewComment);
                        updated.set(true);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            if (!updated.get()) {
                log.info("No existing matching comment, creating new comment on " + pullRequest.getHtmlUrl());
                pullRequest.comment(newComment);
            }
        }

    }

    public static String getWorkflowId(String repo, String workflowId) {
        if (workflowId != null) {
            return workflowId;
        }
        if (repo.endsWith("/liquibase") || repo.endsWith("/liquibase-pro")) {
            return "build.yml";
        }
        return "ci.yml";
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
            this.branch = GitHubClient.simplifyBranch(branch);
            this.fork = repo;
            if (this.fork.contains("/")) {
                this.fork = repo.split("/")[0];
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
