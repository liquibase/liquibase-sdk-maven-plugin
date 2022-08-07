package liquibase.sdk.maven.plugins;

import liquibase.sdk.util.GPGUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Mojo(name = "create-release-artifacts")
public class CreateReleaseArtifactsMojo extends AbstractMojo {

    @Parameter(property = "liquibase.sdk.inputDirectory", required = true)
    protected String inputDirectory;

    @Parameter(property = "liquibase.sdk.outputDirectory", required = true)
    protected String outputDirectory;

    @Parameter(property = "liquibase.sdk.newVersion", required = true)
    protected String newVersion;

    @Parameter(property = "liquibase.sdk.repo", required = true)
    protected String repo;

    @Parameter(property = "liquibase.sdk.reversion.requireCaseSensitiveFilesystem", defaultValue = "true")
    protected boolean requireCaseSensitiveFilesystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (repo.contains(",")) {
            throw new MojoFailureException("Goal does not support multiple repos");
        }
        if (repo.contains("/")) {
            repo = repo.split("/")[1];
        }

        if (newVersion.startsWith("v")) {
            newVersion = newVersion.substring(1);
        }

        try {
            File inputDirectory = new File(this.inputDirectory);
            if (!inputDirectory.exists()) {
                throw new MojoFailureException("inputDirectory " + inputDirectory.getAbsolutePath() + " does not exist");
            }

            File outputDirectory = new File(this.outputDirectory);
            outputDirectory.mkdirs();

            if (requireCaseSensitiveFilesystem) {
                checkFileSystemCaseSensitivity();
            }

            File[] inputJarFiles = inputDirectory.listFiles(pathname -> pathname.getName().endsWith(".jar") && pathname.getName().contains("0-SNAPSHOT"));

            for (File inputFile : inputJarFiles) {
                File outputFile = new File(outputDirectory, inputFile.getName().replace("0-SNAPSHOT", newVersion));
                File workDir = File.createTempFile("liquibase-reversion-workdir-", ".dir");
                workDir.delete();
                workDir.mkdirs();

                getLog().info("Re-versioning " + inputFile.getAbsolutePath() + " to " + outputFile.getAbsolutePath());

                Map<String, FileTime> lastModified = new HashMap<>();

                extractAndFixJars(outputDirectory, inputFile, outputFile, workDir, lastModified);
                rebuildJars(outputFile, workDir, lastModified);

                cleanupWorkDir(workDir);

                signFiles(outputDirectory);

                createAdditionalZip(outputDirectory);
            }

        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void createAdditionalZip(File outputDirectory) throws IOException {
        File additionalFileObj = new File(outputDirectory, repo + "-additional-" + newVersion + ".zip");
        getLog().info("Creating " + additionalFileObj.getAbsolutePath() + "...");
        try (ZipOutputStream additionalFiles = new JarOutputStream(Files.newOutputStream(additionalFileObj.toPath()))) {
            for (File file : outputDirectory.listFiles()) {
                if (file.getName().contains("-sources")
                        || file.getName().contains("-javadoc")
                        || file.getName().endsWith(".asc")
                        || file.getName().endsWith(".md5")
                        || file.getName().endsWith(".sha1")
                        || file.getName().endsWith(".pom")
                ) {
                    ZipEntry entry = new ZipEntry(file.getName());
                    additionalFiles.putNextEntry(entry);
                    try (InputStream fileContent = Files.newInputStream(file.toPath())) {
                        IOUtils.copy(fileContent, additionalFiles);
                    }
                    additionalFiles.closeEntry();
                    file.delete();
                }
            }
        }
    }

    private static void rebuildJars(File outputFile, File workDir, Map<String, FileTime> lastModified) throws IOException {
        Path workdirPath = workDir.toPath();

        try (InputStream manifestStream = Files.newInputStream(workdirPath.resolve("META-INF/MANIFEST.MF"))) {
            Manifest manifest = new Manifest(manifestStream);
            try (JarOutputStream target = new JarOutputStream(Files.newOutputStream(outputFile.toPath()), manifest)) {
                Files.walkFileTree(workdirPath, new FileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (dir.equals(workdirPath)) {
                            return FileVisitResult.CONTINUE;
                        }

                        String entryName = workdirPath.relativize(dir).toString().replace("\\", "/");
                        FileTime fileModified = lastModified.get(entryName);
                        if (fileModified == null) {
                            fileModified = lastModified.get(entryName + "/");
                        }
                        if (fileModified == null) {
                            fileModified = FileTime.fromMillis(new Date().getTime());
                        }

                        if (!entryName.endsWith("/")) { //jar directories have to end in /
                            entryName = entryName + "/";
                        }
                        JarEntry entry = new JarEntry(entryName);
                        entry.setTime(fileModified.toMillis());
                        target.putNextEntry(entry);
                        target.closeEntry();

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toString().endsWith("MANIFEST.MF")) {
                            return FileVisitResult.CONTINUE;
                        }

                        String entryName = workdirPath.relativize(file).toString().replace("\\", "/");

                        JarEntry entry = new JarEntry(entryName);

                        FileTime fileModified = lastModified.get(entryName);
                        if (fileModified == null) {
                            fileModified = FileTime.fromMillis(new Date().getTime());
                        }

                        entry.setTime(fileModified.toMillis());


                        target.putNextEntry(entry);
                        try (InputStream fileContent = Files.newInputStream(file)) {
                            IOUtils.copy(fileContent, target);
                        }
                        target.closeEntry();

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        throw exc;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    private void extractAndFixJars(File outputDirectory, File inputFile, File outputFile, File workDir, Map<String, FileTime> lastModified) throws IOException, MojoFailureException {
        try (ZipFile zipFile = new ZipFile(inputFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                lastModified.put(entry.getName().replace("\\", "/"), entry.getLastModifiedTime());
                if (entry.isDirectory()) {
                    continue;
                }
                File outFile = new File(workDir, entry.getName());
                outFile.getParentFile().mkdirs();
                try (InputStream in = zipFile.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(outFile.toPath())) {
                    IOUtils.copy(in, out);
                }

                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    fixManifest(outFile);
                } else if (entry.getName().endsWith("/pom.xml")
                        || entry.getName().endsWith("/pom.properties")
                        || entry.getName().matches("/plugin.*\\.xml")
                        || entry.getName().endsWith(".html")
                        || entry.getName().endsWith(".xml")
                ) {
                    simpleSnapshotReplace(outFile);
                } else if (entry.getName().endsWith("liquibase.build.properties")) {
                    fixBuildProperties(outFile);
                }

                checkForSnapshot(outFile);

                if (entry.getName().endsWith("/pom.xml") && !(inputFile.getName().contains("-javadoc-") || inputFile.getName().contains("-sources-"))) {
                    String outputPomName = outputFile.getName().replace(".jar", ".pom");
                    getLog().info("Extracting " + outputPomName);
                    Files.copy(outFile.toPath(), Paths.get(outputDirectory.getAbsolutePath(), outputPomName), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void signFiles(File outputDirectory) throws IOException {
        File[] filesToSign = outputDirectory.listFiles(pathname -> !pathname.getName().endsWith(".md5") && !pathname.getName().endsWith(".sha1") && !pathname.getName().endsWith(".asc"));

        if (filesToSign == null) {
            throw new IOException("Cannot list files in " + outputDirectory.getAbsolutePath());
        }

        for (File file : filesToSign) {
            try (InputStream content = Files.newInputStream(file.toPath())) {
                File md5File = new File(file.getAbsoluteFile() + ".md5");
                FileUtils.write(md5File, DigestUtils.md5Hex(content), StandardCharsets.UTF_8);
                getLog().info("Created " + md5File);
            }

            try (InputStream content = Files.newInputStream(file.toPath())) {
                File sha1File = new File(file.getAbsoluteFile() + ".sha1");
                FileUtils.write(sha1File, DigestUtils.sha1Hex(content), StandardCharsets.UTF_8);
                getLog().info("Created " + sha1File);
            }

            GPGUtil.sign(file.getAbsolutePath());
        }
    }


    private void checkForSnapshot(File outFile) throws IOException, MojoFailureException {
        try (InputStream input = Files.newInputStream(outFile.toPath())) {
            String content = IOUtils.toString(input, StandardCharsets.UTF_8);

            if (content.contains("0-SNAPSHOT")) {
                throw new MojoFailureException(outFile.getAbsolutePath() + " still contains 0-SNAPSHOT");
            }
            if (content.contains("0.0.0.SNAPSHOT")) {
                throw new MojoFailureException(outFile.getAbsolutePath() + " still contains 0.0.0.SNAPSHOT");
            }
        }
    }

    private void simpleSnapshotReplace(File outFile) throws IOException {
        try (InputStream input = Files.newInputStream(outFile.toPath())) {
            String content = IOUtils.toString(input, StandardCharsets.UTF_8);

            content = content.replaceAll("([^.])0-SNAPSHOT", "$1" + newVersion);

            FileUtils.write(outFile, content, StandardCharsets.UTF_8);
        }
    }

    private void fixBuildProperties(File outFile) throws IOException {
        try (InputStream input = Files.newInputStream(outFile.toPath())) {
            String content = IOUtils.toString(input, StandardCharsets.UTF_8);

            content = content.replaceAll("build.version=.*", "build.version=" + newVersion);

            FileUtils.write(outFile, content, StandardCharsets.UTF_8);
        }
    }

    private void fixManifest(File outFile) throws IOException {
        Manifest manifest;
        try (InputStream input = Files.newInputStream(outFile.toPath())) {
            manifest = new Manifest(input);
        }
        final Attributes attributes = manifest.getMainAttributes();

        attributes.putValue("Liquibase-Version", newVersion);

        final String bundleVersion = attributes.getValue("Bundle-Version");
        if (bundleVersion != null) {
            attributes.putValue("Bundle-Version", newVersion);
        }

        final String importPackage = attributes.getValue("Import-Package");
        if (importPackage != null) {
            attributes.putValue("Import-Package", importPackage.replaceAll("version=\"\\[0\\.0,1\\)\"", "version=\"" + newVersion + "\""));
        }

        final String exportPackage = attributes.getValue("Export-Package");
        if (exportPackage != null) {
            attributes.putValue("Export-Package", exportPackage.replaceAll(";version=\"0\\.0\\.0\"", ";version=\"" + newVersion + "\""));
        }

        try (OutputStream out = Files.newOutputStream(outFile.toPath())) {
            manifest.write(out);
        }

    }

    private void cleanupWorkDir(File workDir) {
        try {
            FileUtils.deleteDirectory(workDir);
        } catch (IOException e) {
            getLog().warn("Cannot delete " + workDir);
        }
    }

    private static void checkFileSystemCaseSensitivity() throws MojoExecutionException, IOException {
        File tempFile = File.createTempFile("liquibase-case-test-", ".TMP");
        tempFile.deleteOnExit();

        File otherCaseFile = new File(tempFile.getAbsolutePath().replace(".TMP", ".tmp"));

        if (otherCaseFile.exists()) {
            throw new MojoExecutionException("reversion-jar requires a case sensitive filesystem");
        }
    }
}
