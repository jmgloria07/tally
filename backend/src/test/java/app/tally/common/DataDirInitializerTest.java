package app.tally.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirInitializerTest {

    @TempDir
    Path dataDir;

    @Test
    void legacyFitLocalDatabaseAndSideFilesAreRenamedToTally() throws Exception {
        Files.writeString(dataDir.resolve("fitlocal.db"), "old data");
        Files.writeString(dataDir.resolve("fitlocal.db-wal"), "wal");

        assertThat(DataDirInitializer.migrateLegacyDatabase(dataDir)).isTrue();

        assertThat(dataDir.resolve("fitlocal.db")).doesNotExist();
        assertThat(dataDir.resolve("fitlocal.db-wal")).doesNotExist();
        assertThat(Files.readString(dataDir.resolve("tally.db"))).isEqualTo("old data");
        assertThat(Files.readString(dataDir.resolve("tally.db-wal"))).isEqualTo("wal");
    }

    @Test
    void existingTallyDatabaseIsNeverOverwritten() throws Exception {
        Files.writeString(dataDir.resolve("fitlocal.db"), "old data");
        Files.writeString(dataDir.resolve("tally.db"), "current data");

        assertThat(DataDirInitializer.migrateLegacyDatabase(dataDir)).isFalse();

        assertThat(Files.readString(dataDir.resolve("tally.db"))).isEqualTo("current data");
        assertThat(Files.readString(dataDir.resolve("fitlocal.db"))).isEqualTo("old data");
    }

    @Test
    void freshInstallDoesNothing() throws Exception {
        assertThat(DataDirInitializer.migrateLegacyDatabase(dataDir)).isFalse();
        assertThat(dataDir.resolve("tally.db")).doesNotExist();
    }
}
