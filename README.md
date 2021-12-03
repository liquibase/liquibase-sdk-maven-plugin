# Support tools for Liquibase development

This project provides Maven-based support tools for developing and testing Liquibase.

## Running

It can be run directly as `mvn org.liquibase.ext:liquibase-dev-maven-plugin:0.5:<goal> <args>` OR it can be added to your pom.xml file as a plugin as

```
   <build>
        <plugins>
            <plugin>
                <groupId>org.liquibase.ext</groupId>
                <artifactId>liquibase-dev-maven-plugin</artifactId>
                <version>0.5</version>
            </plugin>
        </plugins>
    </build>
```

and then ran as `mvn liquibase-dev:<goal> <args>`

## Github Authentication

If the goal requires access to Github, your personal access token must be set via a `liquibase.dev.github.token` maven property in whatever manner you see fit.

## Available Goals

#### install-snapshot

Downloads latest build of the given branch, and installs it into your local Maven repository as version `0-SNAPSHOT`
