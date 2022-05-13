package liquibase.sdk.github

import liquibase.sdk.github.GitHubClient
import org.kohsuke.github.*
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.Unroll

class GitHubClientTest extends Specification {

    @Unroll
    def findMatchingBranches() {
        when:
        def testPullRequests = [
                // [ref, label]
                ["master", ":master"],
                ["master", "other:master"],
                ["local-branch", "local-branch"],
                ["remote-branch", "other:remote-branch"],
        ]

        def mockGitHub = Mock(GitHub)
        def mockRepository = Mock(GHRepository)
        mockRepository.getName() >> "liquibase"
        mockRepository.getHtmlUrl() >> new URL("http://example.com/mock")

        mockRepository.getBranch(_ as String) >> { args ->
            def mock = Mock(GHBranch)
            mock.getName() >> args[0]

            return mock
        }

        mockGitHub.getRepository(_ as String) >> mockRepository

        Map<String, GHPullRequest> openPullRequests = [:]

        for (def testPr : testPullRequests) {
            def mockCommitPointer = Mock(GHCommitPointer)
            mockCommitPointer.getRef() >> testPr[0]
            mockCommitPointer.getLabel() >> testPr[1]

            def mock = Mock(GHPullRequest)
            mock.getHead() >> mockCommitPointer

            openPullRequests[testPr[0]] = mock
        }

        def client = new GitHubClient(mockGitHub, LoggerFactory.getLogger(this.class)) {
            @Override
            protected Map<String, GHPullRequest> getAllOpenPullRequests(GHRepository repository) throws IOException {
                return openPullRequests
            }
        }

        then:
        client.findMatchingBranch("liquibase/liquibase", searchBranches as String[]) == expected

        where:
        searchBranches                             | expected
        ["other", "master"]                        | "liquibase:master"
        ["invalid", "other", "master"]             | "liquibase:master"
        ["other:master", "master"]                 | "other:master"
        ["other-master", "master"]                 | "other:master"
        ["other:remote-branch", "master"]          | "other:remote-branch"
        ["remote-branch", "master"]                | "other:remote-branch"
        ["local-branch", "master"]                 | "local-branch"
        ["invalid", "local-branch", "master"]      | "local-branch"
        ["remote-branch", "local-branch", "master"] | "other:remote-branch"
    }
}
