import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Explicit operator-only import for an approved student roster.
 * Input: private UTF-8 TSV, display name then eight-digit roll number, no header.
 * Roll number is username and password; normal provisioning policy is unchanged.
 * Existing matching accounts are retained, conflicts abort the whole transaction.
 * Credentials, display names and password hashes are never printed.
 */
public class SeedStudentRoster {
    private record Entry(String displayName, String rollNumber, int row) {}

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Provide one private roster TSV path");
            importRoster(readRoster(Path.of(args[0])));
        } catch (SQLException ignored) {
            // JDBC messages can contain a conflicting username, itself a password.
            System.err.println("FAIL: student roster database operation failed; no changes committed");
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("FAIL: " + failure.getMessage());
            System.exit(1);
        }
    }

    private static List<Entry> readRoster(Path path) throws Exception {
        List<Entry> entries = new ArrayList<>();
        Set<String> rolls = new HashSet<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int row = 0; row < lines.size(); row++) {
            String line = lines.get(row);
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Expected two TSV fields at row " + (row + 1));
            String name = fields[0].strip();
            String roll = fields[1].strip();
            if (name.isBlank() || name.length() > 120 || !roll.matches("[0-9]{8}")) {
                throw new IllegalArgumentException("Invalid name or roll-number format at row " + (row + 1));
            }
            if (!rolls.add(roll)) throw new IllegalArgumentException("Duplicate roll number at row " + (row + 1));
            entries.add(new Entry(name, roll, row + 1));
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Roster must not be empty");
        entries.sort((left, right) -> left.rollNumber().compareTo(right.rollNumber()));
        return entries;
    }

    private static void importRoster(List<Entry> entries) throws Exception {
        String url = requiredEnvironment("DB_URL");
        if (!url.startsWith("jdbc:mysql:") || !"/online_exam".equals(URI.create(url.substring(5)).getPath())) {
            throw new IllegalArgumentException("This explicit roster importer targets only the online_exam database");
        }
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        try (Connection connection = DriverManager.getConnection(url,
                requiredEnvironment("DB_USER"), requiredEnvironment("DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                List<Entry> missing = new ArrayList<>();
                // Validate every existing match before inserting any account.
                for (Entry entry : entries) {
                    if (!validateStored(connection, encoder, entry, true)) missing.add(entry);
                }
                String sql = "INSERT INTO users(username, display_name, password_hash, role, enabled, created_at) "
                        + "VALUES (?, ?, ?, 'STUDENT', TRUE, UTC_TIMESTAMP(6))";
                try (PreparedStatement insert = connection.prepareStatement(sql)) {
                    for (Entry entry : missing) {
                        insert.setString(1, entry.rollNumber());
                        insert.setString(2, entry.displayName());
                        insert.setString(3, encoder.encode(entry.rollNumber()));
                        if (insert.executeUpdate() != 1) throw new SQLException("Unexpected insert count");
                    }
                }
                for (Entry entry : entries) {
                    if (!validateStored(connection, encoder, entry, false)) {
                        throw new IllegalStateException("Stored roster verification failed at row " + entry.row());
                    }
                }
                connection.commit();
                System.out.println("PASS: roster=" + entries.size() + "; created=" + missing.size()
                        + "; retained=" + (entries.size() - missing.size())
                        + "; verified enabled STUDENT names and bcrypt password matches; atomic commit complete");
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static boolean validateStored(Connection connection, PasswordEncoder encoder,
                                          Entry entry, boolean lock) throws SQLException {
        String sql = "SELECT display_name, password_hash, role, enabled FROM users WHERE username=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, entry.rollNumber());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return false;
                String hash = row.getString("password_hash");
                if (!entry.displayName().equals(row.getString("display_name"))
                        || !"STUDENT".equals(row.getString("role")) || !row.getBoolean("enabled")
                        || hash == null || !hash.startsWith("{bcrypt}")
                        || !encoder.matches(entry.rollNumber(), hash)) {
                    throw new IllegalStateException("Existing account conflict at roster row "
                            + entry.row() + "; no account has been reset");
                }
                return true;
            }
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
