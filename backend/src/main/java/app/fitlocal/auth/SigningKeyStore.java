package app.fitlocal.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The HMAC key for auth tokens: 32 random bytes, base64, in {@code data/signing.key}. Created on first start;
 * deleting the file logs out every device. A file that exists but is unreadable or malformed stops startup
 * instead of silently being replaced (which would also log everyone out without explanation).
 */
@Component
public class SigningKeyStore {

    static final String FILE_NAME = "signing.key";
    private static final int KEY_BYTES = 32;

    private final byte[] key;

    public SigningKeyStore(@Value("${fitlocal.data-dir}") Path dataDir) {
        this.key = loadOrCreate(dataDir.resolve(FILE_NAME));
    }

    byte[] key() {
        return key.clone();
    }

    private static byte[] loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                return read(file);
            }
            byte[] fresh = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(fresh);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "signing", ".tmp");
            Files.writeString(temp, Base64.getEncoder().encodeToString(fresh), StandardCharsets.US_ASCII);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file);
            }
            return fresh;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read or create the signing key at " + file, e);
        }
    }

    private static byte[] read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.US_ASCII).strip();
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(malformed(file));
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(malformed(file));
        }
        return decoded;
    }

    private static String malformed(Path file) {
        return "The signing key at " + file + " is malformed. Delete it to generate a new one "
                + "(this logs out every device).";
    }
}
