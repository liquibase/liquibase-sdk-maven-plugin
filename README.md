# Support tools for Liquibase development

This project provides Maven-based support tools for developing and testing Liquibase.

## Running

It can be run directly as `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.9:<goal> <args>` OR it can be added to your pom.xml file as a plugin as

```
   <build>
        <plugins>
            <plugin>
                <groupId>org.liquibase.ext</groupId>
                <artifactId>liquibase-sdk-maven-plugin</artifactId>
                <version>0.9</version>
            </plugin>
        </plugins>
    </build>
```

and then ran as `mvn liquibase-sdk:<goal> <args>`

## Github Authentication

If the goal requires access to Github, your personal access token must be set via a `liquibase.sdk.github.token` maven property in whatever manner you see fit.

## Available Goals

#### help

Show help on available goals and options. Detailed help can be shown with `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.9:help -Ddetail=true -Dgoal=<goal-name>`

#### install-snapshot-cli

Downloads the latest build of the given branch, and either installs a new Liquibase or upgrades an existing installation with it.

By default, it expects it to be an upgrade, unless `allowInstall` / `liquibase.sdk.allowInstall` is set to true.

The branch to use is set via the liquibase.sdk.branchSearch setting. To install a branch from a fork, reference it as `owner:branch-name`

Examples:
- `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.9:install-cli "-Dliquibase.sdk.branchSearch=local-branch,master"` to install the code from `liquibase/liquibase:local-branch` and if that branch doesn't exist fall back to `master`
- `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.9:install-cli "-Dliquibase.sdk.branchSearch=fork-owner:their-branch"` to install the code from `fork-owner/liquibase:their-branch`

All available arguments:

- liquibase.sdk.branchSearch
- liquibase.sdk.skipFailedBuilds (default: false)
- liquibase.sdk.allowInstall (default: false) Allow installation to a new liquibase_home. When false, it throws an exception if the target directory is not an existing liquibase installation.
- liquibase.sdk.github.token
- liquibase.sdk.repo can be `liquibase/liquibase` or `liquibase/liquibase-pro`. Without an org, it assumes `liquibase`. It can be a comma separated list like `liquibase,liquibase-pro`


#### install-snapshot

Downloads the latest build of the given branch, and installs it into your local Maven repository as version `0-SNAPSHOT`.

The branch to use is set via the liquibase.sdk.branchSearch setting. To install a branch from a fork, reference it as `owner:branch-name`

Examples:
- `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.9:install-snapshot "-Dliquibase.sdk.branchSearch=local-branch,master"` to install the code from `liquibase/liquibase:local-branch` and if that branch doesn't exist fall back to `master`
- `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.9:install-snapshot "-Dliquibase.sdk.branchSearch=fork-owner:their-branch"` to install the code from `fork-owner/liquibase:their-branch`

All available arguments:

- liquibase.sdk.branchSearch
- liquibase.sdk.skipFailedBuilds (default: false)
- liquibase.sdk.github.token
- liquibase.sdk.repo can be `liquibase/liquibase` or `liquibase/liquibase-pro`. Without an org, it assumes `liquibase`. It can be a comma separated list like `liquibase,liquibase-pro`

#### find-matching-branch

Finds the first PR/branch that matches one of the entries in the `liquibase.sdk.branchSearch` list. 
It will only return branches that have builds associated with them. That means branches with an open PR or the default branch.

To find PRs of a branch from a fork, reference it as `owner:branch-name`

Examples:
- `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.9:find-matching-branch "-Dliquibase.sdk.branchSearch=otheruser:feature-branch,master"` to search for what matches the branch "feature-branch" in an "otheruser" fork and otherwise return "master".

Any branches in the "fork:branch" format will be search for:
1. A PR with from the fork with the given branch
2. A local branch with the name`fork-branch`
3. A local branch with the name `branch`

All available arguments:

- liquibase.sdk.branchSearch
- liquibase.sdk.github.token
- liquibase.sdk.repo

#### get-build-info

Returns the information about the currently installed maven build to stdout. 

If the `outputKey` argument is set, it will return the value of the given key instead of a json object.

- liquibase.sdk.buildInfo.outputKey
- liquibase.sdk.github.token

#### set-commit-status

Sets a commit status on Github for the commit of the currently installed build.

Examples:
- `mvn org.liquibase.ext:liquibase-sdk-maven-plugin:0.9:set-commit-status -Dliquibase.sdk.status.context="My Check" -Dliquibase.sdk.status.state=SUCCESS "-Dliquibase.sdk.status.url=http://example.com" -Dliquibase.sdk.status.description="Info about my check"`


All available arguments:

- liquibase.sdk.status.context
- liquibase.sdk.status.state
- liquibase.sdk.status.url
- liquibase.sdk.status.description
- liquibase.sdk.github.token
- liquibase.sdk.repo
