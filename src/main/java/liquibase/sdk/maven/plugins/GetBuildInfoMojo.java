package liquibase.sdk.maven.plugins;

import liquibase.sdk.github.GitHubClient;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;


/**
 * <p>Finds the branch.</p>
 */
@Mojo(name = "get-build-info", requiresProject = false)
public class GetBuildInfoMojo extends AbstractGitHubMojo {

    private static final Logger log = LoggerFactory.getLogger(GetBuildInfoMojo.class);

    @Parameter(property = "liquibase.sdk.buildInfo.outputKey")
    protected String outputKey;


    public void execute() throws MojoExecutionException {
        try {
            GitHubClient github = createGitHubClient();

            final Properties buildInfo = github.getInstalledBuildProperties();
            buildInfo.put("overview", "OSS: " + buildInfo.get("build.branch") + "::" + buildInfo.get("build.commit") + " @ " + buildInfo.get("build.timestamp") +
                    " " +
                    "Pro: " + buildInfo.get("build.pro.branch") + "::" + buildInfo.get("build.pro.commit") + " @ " + buildInfo.get("build.pro.timestamp"))
            ;

            if (outputKey == null) {
                StringBuilder out = new StringBuilder();
                out.append("{\n");
                for (String key : buildInfo.stringPropertyNames()) {
                    out.append("  \"").append(key).append("\": \"").append(buildInfo.getProperty(key)).append("\",\n");
                }
                out.deleteCharAt(out.length() - 2); // remove last comma but preserve newline

                out.append("}");

                System.out.println(out);
            } else {
                System.out.println(buildInfo.get(outputKey));
            }
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
