package liquibase.sdk.util;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ArchiveUtil {

    public static void unzipCli(File file, File liquibaseHome, Logger log, UnzipFilter filter, UnzipTransform transformer) throws IOException {
        try (ZipFile zipFile = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (filter == null || filter.include(entry)) {
                    String outputFileName = entry.getName();
                    if (transformer != null) {
                        outputFileName = transformer.transform(outputFileName);
                    }
                    File outFile = new File(liquibaseHome, outputFileName);
                    boolean newFile = !outFile.exists();

                    outFile.getParentFile().mkdirs();
                    try (InputStream in = zipFile.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(outFile.toPath())) {
                        IOUtils.copy(in, out);
                    }

                    if (newFile) {
                        log.info("Created " + outFile.getAbsolutePath());
                    } else {
                        log.info("Replaced " + outFile.getAbsolutePath());
                        if (!entry.getName().equals("liquibase")) {
                            outFile.setExecutable(true);
                        }

                    }
                }
            }
        }

    }

    public static boolean filenameMatches(String name, String artifactPattern) {
        String regexpPattern = artifactPattern
                .replace(".", "\\.")
                .replace("+", "\\+")
                .replace("*", ".*");
        return name.matches(regexpPattern);
    }

    public interface UnzipFilter {
        boolean include(ZipEntry path);
    }

    public interface UnzipTransform {
        String transform(String inputPath);
    }

}
