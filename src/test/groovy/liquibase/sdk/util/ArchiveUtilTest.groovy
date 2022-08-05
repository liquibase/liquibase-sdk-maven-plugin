package liquibase.sdk.util


import spock.lang.Specification
import spock.lang.Unroll

class ArchiveUtilTest extends Specification {

    @Unroll
    def filenameMatches() {
        expect:
        ArchiveUtil.filenameMatches(filename, pattern) == expected

        where:
        filename             | pattern                 | expected
        "liquibase.zip"      | "liquibase.zip"         | true
        "liquibaseXzip"      | "liquibase.zip"         | false
        "liquibase-test.zip" | "liquibase.zip"         | false
        "liquibase-test.zip" | "liquibase-*.zip"       | true
        "liquibase.zip"      | "*"                     | true
        "weird+file.zip"     | "weird+file.zip"        | true
    }
}
