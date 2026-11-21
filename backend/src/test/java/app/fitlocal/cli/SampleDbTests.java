package app.fitlocal.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fails the build when {@code schema.sql} changes but the committed {@code data/fitlocal.db.example} was not
 * regenerated (spec C15). Fix: {@code start.bat --create-sample-db}.
 */
class SampleDbTests {

    /** Tests run with {@code backend/} as the working directory. */
    private static final Path COMMITTED_EXAMPLE = Path.of("..", "data", SampleDbCommand.FILE_NAME);

    @TempDir
    Path tempDir;

    @Test
    void committedExampleMatchesSchemaAndIsEmpty() throws Exception {
        assertThat(COMMITTED_EXAMPLE).as("data/fitlocal.db.example must exist; run start.bat --create-sample-db")
                .exists();
        Path fresh = tempDir.resolve("fresh.db");
        SampleDbCommand.create(fresh);

        assertThat(schemaOf(COMMITTED_EXAMPLE))
                .as("data/fitlocal.db.example is out of date; run start.bat --create-sample-db")
                .isEqualTo(schemaOf(fresh));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + COMMITTED_EXAMPLE.toAbsolutePath())) {
            for (String table : tables(c)) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("rows in " + table).isZero();
                }
            }
        }
    }

    private static List<String> schemaOf(Path db) throws Exception {
        List<String> entries = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT type, name, sql FROM sqlite_master ORDER BY type, name")) {
            while (rs.next()) {
                String sql = rs.getString("sql");
                String normalized = sql == null ? "" : sql.replaceAll("\\s+", " ").strip();
                entries.add(rs.getString("type") + " " + rs.getString("name") + " " + normalized);
            }
        }
        return entries;
    }

    private static List<String> tables(Connection c) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
