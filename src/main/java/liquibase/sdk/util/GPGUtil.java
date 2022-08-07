package liquibase.sdk.util;

import org.bouncycastle.openpgp.examples.DetachedSignatureProcessor;

import java.io.File;
import java.io.IOException;

public class GPGUtil {

    public static void sign(String filename) throws IOException {
        File keyFile = new File(System.getenv("HOME"), ".gnupg/pubring.gpg");
        if (!keyFile.exists()) {
            keyFile = new File(System.getenv("APPDATA"), "gnupg/pubring.gpg");
        }
        if (!keyFile.exists()) {
            throw new IOException("Cannot find keystore");
        }

        try {
            DetachedSignatureProcessor.main(new String[] {"-s", "-a", filename, keyFile.getAbsolutePath(), System.getenv("GPG_PASSWORD")});
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
