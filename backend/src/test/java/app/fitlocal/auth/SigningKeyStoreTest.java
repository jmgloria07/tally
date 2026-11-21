package app.fitlocal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.fitlocal.MutableClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeyStoreTest {

    @TempDir
    Path dataDir;

    @Test
    void keyIsCreatedOnFirstStartAndReusedSoTokensSurviveARestart() {
        SigningKeyStore first = new SigningKeyStore(dataDir);
        assertThat(dataDir.resolve(SigningKeyStore.FILE_NAME)).exists();
        MutableClock clock = new MutableClock(ZoneId.systemDefault());
        String token = new AuthTokenService(first, clock).issue("tester", "hash");

        SigningKeyStore second = new SigningKeyStore(dataDir);
        assertThat(second.key()).isEqualTo(first.key());
        assertThat(new AuthTokenService(second, clock).verify(token, u -> Optional.of("hash")))
                .contains("tester");
    }

    @Test
    void malformedKeyFileStopsStartupInsteadOfBeingReplaced() throws Exception {
        Path file = dataDir.resolve(SigningKeyStore.FILE_NAME);
        Files.writeString(file, "not base64 !!");
        assertThatThrownBy(() -> new SigningKeyStore(dataDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
        assertThat(Files.readString(file)).isEqualTo("not base64 !!");
    }
}
