import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** Read-only host-side connectivity check. Never prints credentials. */
public class DbProbe {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");
        if (url == null || user == null || password == null) {
            throw new IllegalArgumentException("DB_URL, DB_USER and DB_PASSWORD are required");
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT VERSION(), @@default_storage_engine, @@time_zone")) {
                result.next();
                String engine = result.getString(2);
                String zone = result.getString(3);
                if (!"InnoDB".equals(engine) || !"+00:00".equals(zone)) {
                    throw new IllegalStateException("Unexpected database engine or time zone");
                }
                System.out.println("MySQL " + result.getString(1) + "; engine=" + engine + "; timeZone=" + zone);
            }
            Set<String> schemas = new HashSet<>();
            try (ResultSet result = statement.executeQuery("SHOW DATABASES")) {
                while (result.next()) schemas.add(result.getString(1));
            }
            for (String name : new String[]{"online_exam", "online_exam_it", "online_exam_e2e"}) {
                if (!schemas.contains(name)) throw new IllegalStateException("Missing isolated schema " + name);
            }
            System.out.println("Connected to development, integration and browser-test schemas");
        }
    }
}
