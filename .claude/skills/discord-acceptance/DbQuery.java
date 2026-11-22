import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/**
 * Prints the result of one read-only SQL query against a Tally SQLite file, tab-separated with a header row.
 * Usage: java -cp "$CP" DbQuery.java data/acceptance/tally.db "SELECT ..."
 * Refuses anything but SELECT, and refuses the real data/tally.db.
 */
public class DbQuery {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: DbQuery <db file> <select statement>");
        }
        String db = args[0].replace('\\', '/');
        if (db.endsWith("data/tally.db") && !db.contains("acceptance")) {
            throw new IllegalArgumentException("refusing the real data/tally.db; acceptance runs use data/acceptance");
        }
        if (!args[1].strip().toLowerCase().startsWith("select")) {
            throw new IllegalArgumentException("SELECT only");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(args[1])) {
            ResultSetMetaData meta = rs.getMetaData();
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                header.append(i > 1 ? "\t" : "").append(meta.getColumnLabel(i));
            }
            System.out.println(header);
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.append(i > 1 ? "\t" : "").append(rs.getString(i));
                }
                System.out.println(row);
            }
        }
    }
}
