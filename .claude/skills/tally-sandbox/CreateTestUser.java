import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Creates the fixed web login for the sandbox: user {@code tester}, password {@code testtest} (the 8-character minimum in AuthService).
 * Usage: java -cp "$CP" CreateTestUser.java data/sandbox/tally.db
 * Run it after Tally has started once, so the schema exists. If the user is already there, resets its password.
 * Only writes to a database whose path contains "sandbox", never the real data/tally.db.
 * The hash and timestamp match what AuthService.createUser stores: BCrypt strength 10, and
 * yyyy-MM-dd'T'HH:mm:ss local time (IsoFormats.DATE_TIME).
 */
public class CreateTestUser {
    static final String USERNAME = "tester";
    static final String PASSWORD = "testtest";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: CreateTestUser <sandbox db file>");
        }
        String db = args[0].replace('\\', '/');
        if (!db.contains("sandbox")) {
            throw new IllegalArgumentException("refusing " + db + "; only a sandbox database may be written");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=rw&busy_timeout=5000")) {
            try (PreparedStatement find = c.prepareStatement("SELECT 1 FROM users WHERE username = ?")) {
                find.setString(1, USERNAME);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) {
                        try (PreparedStatement reset = c.prepareStatement(
                                "UPDATE users SET password_hash = ? WHERE username = ?")) {
                            reset.setString(1, new BCryptPasswordEncoder().encode(PASSWORD));
                            reset.setString(2, USERNAME);
                            reset.executeUpdate();
                        }
                        System.out.println("User " + USERNAME + " already exists; password reset to " + PASSWORD);
                        return;
                    }
                }
            }
            String createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)")) {
                insert.setString(1, USERNAME);
                insert.setString(2, new BCryptPasswordEncoder().encode(PASSWORD));
                insert.setString(3, createdAt);
                insert.executeUpdate();
            }
            System.out.println("Created user " + USERNAME + " (password " + PASSWORD + ")");
        }
    }
}
