package app.fitlocal.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * {@code --create-sample-db}: deletes and recreates {@code data/fitlocal.db.example} from {@code schema.sql}
 * (spec §4.4). Uses the same {@link ScriptUtils} path as Spring SQL init, so the DDL stored in
 * {@code sqlite_master} matches what the app creates. A test checks the committed file against it.
 */
@Component
public class SampleDbCommand implements CliCommand {

    public static final String FILE_NAME = "fitlocal.db.example";

    private final Path dataDir;

    public SampleDbCommand(@Value("${fitlocal.data-dir}") Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public String name() {
        return "--create-sample-db";
    }

    @Override
    public int run(List<String> args) {
        Path target = dataDir.resolve(FILE_NAME);
        try {
            create(target);
            System.out.println("Created " + target.toAbsolutePath().normalize() + " (schema only, all tables empty).");
            return 0;
        } catch (Exception e) {
            System.err.println("Could not create the sample database: " + e.getMessage());
            return 1;
        }
    }

    public static void create(Path target) throws Exception {
        Files.deleteIfExists(target);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target.toAbsolutePath())) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
    }
}
