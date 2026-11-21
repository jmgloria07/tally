package app.fitlocal.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Creates the data directory before the DataSource opens {@code fitlocal.db} inside it. Registered in
 * {@code META-INF/spring.factories} so it runs for the app, the CLI commands and tests alike.
 */
public class DataDirInitializer implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        String dataDir = event.getEnvironment().getProperty("fitlocal.data-dir");
        if (dataDir == null || dataDir.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(Path.of(dataDir));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create data directory " + dataDir, e);
        }
    }
}
