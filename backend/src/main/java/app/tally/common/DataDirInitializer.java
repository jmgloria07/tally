package app.tally.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Creates the data directory before the DataSource opens {@code tally.db} inside it, and renames a database
 * left over from when the app was called FitLocal. Registered in {@code META-INF/spring.factories} so it runs
 * for the app, the CLI commands and tests alike.
 */
public class DataDirInitializer implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final String DB_FILE = "tally.db";
    static final String LEGACY_DB_FILE = "fitlocal.db";
    /** SQLite side files that belong to the database and must move with it. */
    private static final String[] DB_SUFFIXES = {"", "-wal", "-shm", "-journal"};

    private static final Logger log = LoggerFactory.getLogger(DataDirInitializer.class);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        String dataDir = event.getEnvironment().getProperty("tally.data-dir");
        if (dataDir == null || dataDir.isBlank()) {
            return;
        }
        Path dir = Path.of(dataDir);
        try {
            Files.createDirectories(dir);
            migrateLegacyDatabase(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not prepare data directory " + dataDir, e);
        }
    }

    /**
     * Renames {@code fitlocal.db} (and its SQLite side files) to {@code tally.db} when only the old database
     * exists. Never overwrites: if {@code tally.db} is already there, the old file is left untouched.
     *
     * @return true if a database was renamed
     */
    static boolean migrateLegacyDatabase(Path dir) throws IOException {
        Path legacy = dir.resolve(LEGACY_DB_FILE);
        Path current = dir.resolve(DB_FILE);
        if (!Files.exists(legacy) || Files.exists(current)) {
            return false;
        }
        for (String suffix : DB_SUFFIXES) {
            Path from = dir.resolve(LEGACY_DB_FILE + suffix);
            if (Files.exists(from)) {
                Files.move(from, dir.resolve(DB_FILE + suffix));
            }
        }
        log.info("Renamed {} to {} (FitLocal is now Tally)", legacy, current);
        return true;
    }
}
