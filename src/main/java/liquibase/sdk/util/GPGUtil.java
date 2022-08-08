package liquibase.sdk.util;

import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class GPGUtil {

    public static void sign(String filename, String gpgExecutable) throws IOException {
        File file = new File(filename).getAbsoluteFile();
        ProcessBuilder builder = new ProcessBuilder();

        if (gpgExecutable == null) {
            //assume it is in the path
            gpgExecutable = "gpg";
        }

        if (StringUtils.trimToNull(System.getenv("GPG_PASSWORD")) == null) {
            throw new IOException("GPG_PASSWORD not set");
        }


        builder.command(gpgExecutable, "--batch", "--pinentry-mode", "loopback", "--passphrase-fd", "0", "-ab", file.getName());

        builder.directory(file.getParentFile());
        Process process = builder.start();
        try (OutputStream outputStream = process.getOutputStream()) {
            outputStream.write(System.getenv("GPG_PASSWORD").getBytes());
        }

        StreamGobbler streamGobbler = new StreamGobbler(process.getInputStream(), System.out::println);
        Executors.newSingleThreadExecutor().submit(streamGobbler);
        int exitCode = 0;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            //
        }
        assert exitCode == 0;
    }

    private static class StreamGobbler implements Runnable {
        private InputStream inputStream;
        private Consumer<String> consumer;

        public StreamGobbler(InputStream inputStream, Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer = consumer;
        }

        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines()
                    .forEach(consumer);
        }
    }
}
