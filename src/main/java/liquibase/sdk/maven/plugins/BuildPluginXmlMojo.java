package liquibase.sdk.maven.plugins;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.SortedMap;
import java.util.SortedSet;

@Mojo(name = "build-plugin-xml")
public class BuildPluginXmlMojo extends AbstractMojo {

    @Parameter(required = true)
    private File liquibaseClassesDir;

    @Parameter(required = true)
    private File outputFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            if (!liquibaseClassesDir.exists()) {
                throw new MojoFailureException(liquibaseClassesDir.getAbsolutePath() + " does not exist");
            }

            Document pluginXml = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element pluginElement = pluginXml.createElement("plugin");
            pluginXml.appendChild(pluginElement);

            addNode("name", "liquibase-maven-plugin", pluginElement, pluginXml);
            addNode("description", "A Maven plugin wraps up some of the functionality of Liquibase", pluginElement, pluginXml);
            addNode("groupId", "org.liquibase", pluginElement, pluginXml);
            addNode("artifactId", "liquibase-maven-plugin", pluginElement, pluginXml);
            addNode("version", "0-SNAPSHOT", pluginElement, pluginXml);
            addNode("goalPrefix", "liquibase", pluginElement, pluginXml);
            addNode("isolatedRealm", "false", pluginElement, pluginXml);
            addNode("inheritedByDefault", "true", pluginElement, pluginXml);

            Element mojosElement = pluginXml.createElement("mojos");
            pluginElement.appendChild(mojosElement);

            URLClassLoader classloader = new URLClassLoader(new URL[]{
                    liquibaseClassesDir.toURI().toURL()
            });

            Thread.currentThread().setContextClassLoader(classloader);
            Object scope = classloader.loadClass("liquibase.Scope")
                    .getMethod("getCurrentScope").invoke(null);

            Class<?> commandFactoryClass = classloader.loadClass("liquibase.command.CommandFactory");

            Object commandFactory = scope.getClass().getMethod("getSingleton", Class.class).invoke(scope, commandFactoryClass);

            SortedSet commands = (SortedSet) commandFactory.getClass().getMethod("getCommands", boolean.class).invoke(commandFactory, false);
            for (Object commandDef : commands) {
                String[] commandName = (String[]) commandDef.getClass().getMethod("getName").invoke(commandDef);
                System.out.println("See command " + StringUtils.join(commandName, " "));

                Element mojoElement = pluginXml.createElement("mojo");
                mojosElement.appendChild(mojoElement);

                for (int i = 0; i < commandName.length; i++) {
                    if (i > 0) {
                        commandName[i] = commandName[i].substring(0, 1).toUpperCase() + commandName[i].substring(1);
                    }
                }
                String finalName = StringUtils.join(commandName);

                addNode("goal", finalName, mojoElement, pluginXml);
                addNode("description", (String) commandDef.getClass().getMethod("getLongDescription").invoke(commandDef), mojoElement, pluginXml);
                addNode("requiresDependencyResolution", "test", mojoElement, pluginXml);
                addNode("requiresDirectInvocation", "false", mojoElement, pluginXml);
                addNode("requiresProject", "true", mojoElement, pluginXml);
                addNode("requiresReports", "false", mojoElement, pluginXml);
                addNode("aggregator", "false", mojoElement, pluginXml);
                addNode("requiresOnline", "false", mojoElement, pluginXml);
                addNode("inheritedByDefault", "true", mojoElement, pluginXml);
                addNode("implementation", "org.liquibase.maven.plugins.LiquibaseCommandMojo", mojoElement, pluginXml);
                addNode("language", "java", mojoElement, pluginXml);
                addNode("instantiationStrategy", "per-lookup", mojoElement, pluginXml);
                addNode("executionStrategy", "once-per-session", mojoElement, pluginXml);
                addNode("threadSafe", "false", mojoElement, pluginXml);
                addNode("configurator", "map-oriented", mojoElement, pluginXml);

                Element parametersElement = pluginXml.createElement("parameters");
                mojoElement.appendChild(parametersElement);

                Element configurationElement = pluginXml.createElement("configuration");
                mojoElement.appendChild(configurationElement);

                addMavenProperty("mojoExecution", "org.apache.maven.plugin.MojoExecution", "${mojoExecution}", parametersElement, configurationElement, pluginXml);
                addMavenProperty("session", "org.apache.maven.execution.MavenSession", "${session}", parametersElement, configurationElement, pluginXml);
                addMavenProperty("project", "org.apache.maven.project.MavenProject", "${project}", parametersElement, configurationElement, pluginXml);


                SortedMap arguments = (SortedMap) commandDef.getClass().getMethod("getArguments").invoke(commandDef);
                for (Object argDef : arguments.values()) {
                    Element parameterElement = pluginXml.createElement("parameter");
                    parametersElement.appendChild(parameterElement);

                    String argName = (String) argDef.getClass().getMethod("getName").invoke(argDef);
                    String dataType = ((Class) argDef.getClass().getMethod("getDataType").invoke(argDef)).getName();

                    addNode("name", argName, parameterElement, pluginXml);
                    addNode("type", dataType, parameterElement, pluginXml);
                    addNode("required", String.valueOf(argDef.getClass().getMethod("isRequired").invoke(argDef)), parameterElement, pluginXml);
                    addNode("editable", "true", parameterElement, pluginXml);
                    addNode("description", (String) argDef.getClass().getMethod("getDescription").invoke(argDef), parameterElement, pluginXml);

                    Element confElement = pluginXml.createElement(argName);
                    configurationElement.appendChild(confElement);
                    confElement.setAttribute("implementation", dataType);
                    confElement.setTextContent("${liquibase.command." + StringUtils.join(commandName, ".") + "." + argName + "}");
                    String defaultValue = (String) argDef.getClass().getMethod("getDefaultValueDescription").invoke(argDef);
                    if (defaultValue != null) {
                        confElement.setAttribute("default-value", defaultValue);
                    }
                }
            }

            writeXml(pluginXml);
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }

        System.out.println("GENERATE PLUGIN.XML");
    }

    private void addMavenProperty(String name, String type, String defaultValue, Element parametersElement, Element configurationElement, Document pluginXml) {
        Element thisParameterElement = pluginXml.createElement("parameter");
        parametersElement.appendChild(thisParameterElement);
        addNode("name", name, thisParameterElement, pluginXml);
        addNode("type", type, thisParameterElement, pluginXml);
        addNode("required", "true", thisParameterElement, pluginXml);
        addNode("editable", "false", thisParameterElement, pluginXml);

        Element thisConfigElement = pluginXml.createElement(name);
        configurationElement.appendChild(thisConfigElement);
        thisConfigElement.setAttribute("implementation", type);
        thisConfigElement.setAttribute("default-value", defaultValue);

    }

    private void writeXml(Document pluginXml) throws IOException, TransformerException {
        DOMSource domSource = new DOMSource(pluginXml);
        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer = factory.newTransformer();
        factory.setAttribute("indent-number", 4);

        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        try (FileWriter fileWriter = new FileWriter(outputFile)) {
            StreamResult sr = new StreamResult(fileWriter);
            transformer.transform(domSource, sr);
        }
    }

    private Element addNode(String nodeName, String text, Element parent, Document document) {
        Element newElement = document.createElement(nodeName);
        newElement.setTextContent(text);
        parent.appendChild(newElement);

        return newElement;
    }
}
