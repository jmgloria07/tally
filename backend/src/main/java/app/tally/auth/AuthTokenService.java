package app.tally.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies auth tokens without a JWT library (spec §6.2):
 *
 * <pre>
 * token     = base64url(payload) "." base64url(signature)
 * payload   = "v1" "|" username "|" expiresAtEpochSeconds
 * signature = HMAC-SHA256(key, payload + "|" + passwordHash)
 * </pre>
 *
 * The password hash is part of the MAC input but not of the token, so changing a password invalidates every
 * token issued for that user.
 */
@Service
public class AuthTokenService {

    public static final Duration LIFETIME = Duration.ofDays(30);

    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SigningKeyStore keyStore;
    private final Clock clock;

    public AuthTokenService(SigningKeyStore keyStore, Clock clock) {
        this.keyStore = keyStore;
        this.clock = clock;
    }

    public String issue(String username, String passwordHash) {
        long expiresAt = clock.instant().plus(LIFETIME).getEpochSecond();
        String payload = VERSION + "|" + username + "|" + expiresAt;
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + ENCODER.encodeToString(sign(payload, passwordHash));
    }

    /**
     * Returns the username if the token is well-formed, unexpired, for an existing user, and correctly
     * signed. {@code passwordHashLookup} maps a username to its current hash (empty if no such user).
     */
    public Optional<String> verify(String token, Function<String, Optional<String>> passwordHashLookup) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        String payload;
        byte[] signature;
        try {
            payload = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8);
            signature = DECODER.decode(parts[1]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 3 || !VERSION.equals(fields[0]) || fields[1].isEmpty()) {
            return Optional.empty();
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(fields[2]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (expiresAt <= clock.instant().getEpochSecond()) {
            return Optional.empty();
        }
        String username = fields[1];
        Optional<String> passwordHash = passwordHashLookup.apply(username);
        if (passwordHash.isEmpty()) {
            return Optional.empty();
        }
        byte[] expected = sign(payload, passwordHash.get());
        return MessageDigest.isEqual(expected, signature) ? Optional.of(username) : Optional.empty();
    }

    private byte[] sign(String payload, String passwordHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyStore.key(), "HmacSHA256"));
            return mac.doFinal((payload + "|" + passwordHash).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
