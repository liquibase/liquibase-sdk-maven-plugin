# Support tools for Liquibase development

This project provides Maven-based support tools for developing and testing Liquibase.

## Running

It can be run directly as `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.7:<goal> <args>` OR it can be added to your pom.xml file as a plugin as

```
   <build>
        <plugins>
            <plugin>
                <groupId>org.liquibase.ext</groupId>
                <artifactId>liquibase-sdk-maven-plugin</artifactId>
                <version>0.7</version>
            </plugin>
        </plugins>
    </build>
```

and then ran as `mvn liquibase-sdk:<goal> <args>`

## Github Authentication

If the goal requires access to Github, your personal access token must be set via a `liquibase.sdk.github.token` maven property in whatever manner you see fit.

## Available Goals

#### help

Show help on available goals and options. Detailed help can be shown with `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.7:help -Ddetail=true -Dgoal=<goal-name>`

#### install-snapshot

Downloads the latest build of the given branch, and installs it into your local Maven repository as version `0-SNAPSHOT`.

The branch to use is set via the liquibase.sdk.branch setting. To install a branch from a fork, reference it as `owner:branch-name`

Examples:
- `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.7:install-snapshot "-Dliquibase.sdk.branch=local-branch"` to install the code from `liquibase/liquibase:local-branch`
- `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.7:install-snapshot "-Dliquibase.sdk.branch=fork-owner:their-branch"` to install the code from `fork-owner/liquibase:their-branch`

All available arguments:

- liquibase.sdk.branch
- liquibase.sdk.github.token
- liquibase.sdk.repo.owner
- liquibase.sdk.repo
