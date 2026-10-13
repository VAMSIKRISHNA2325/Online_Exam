package com.onlineexam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.service.AccountProvisioningService;
import java.net.URI;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class AccountProvisioningIT {
    private static final String TEST_PASSWORD = "IntegrationOnlyPassword-29!";
    private static AccountProvisioningService provisioning;
    private static UserRepository users;
    private static PasswordEncoder encoder;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void connectToDisposableSchema() throws Exception {
        String url = System.getenv().getOrDefault("MYSQL_IT_DB_URL",
                "jdbc:mysql://127.0.0.1:3307/online_exam_it?connectionTimeZone=UTC");
        URI parsed = URI.create(url.substring(5));
        if (!url.startsWith("jdbc:mysql://") || !"mysql".equals(parsed.getScheme())
                || parsed.getHost() == null || !"/online_exam_it".equals(parsed.getPath())
                || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("Provisioning tests require online_exam_it");
        }
        String password = System.getenv("MYSQL_IT_PASSWORD");
        if (password == null || password.isBlank()) password = System.getenv("MYSQL_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("MySQL IT password required");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("MYSQL_IT_USER", "online_exam"), password);
        try (Connection connection = dataSource.getConnection()) {
            if (!"online_exam_it".equals(connection.getCatalog())) {
                throw new IllegalStateException("Unexpected integration test schema");
            }
        }
        Flyway.configure().dataSource(dataSource).load().migrate();
        users = new UserRepository(new JdbcTemplate(dataSource));
        encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        provisioning = new AccountProvisioningService(users, encoder,
                Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void demoProfileAccountsPersistWithEncodedPasswordsAndCanBeSeededAgain() {
        transactions.executeWithoutResult(transaction -> {
            List<String> names = List.of("demo_instructor1", "demo_instructor2",
                    "demo_student1", "demo_student2", "demo_student3");
            for (String name : names) assertThat(users.findByUsername(name)).isEmpty();
            provisioning.seedDemoAccounts(TEST_PASSWORD);
            provisioning.seedDemoAccounts(TEST_PASSWORD);
            for (String name : names) {
                User account = users.findByUsername(name).orElseThrow();
                assertThat(account.passwordHash()).startsWith("{bcrypt}").doesNotContain(TEST_PASSWORD);
                assertThat(encoder.matches(TEST_PASSWORD, account.passwordHash())).isTrue();
                assertThat(account.enabled()).isTrue();
            }
            assertThat(users.findByUsername("demo_instructor1").orElseThrow().role())
                    .isEqualTo(Role.INSTRUCTOR);
            assertThat(users.findByUsername("demo_student3").orElseThrow().role())
                    .isEqualTo(Role.STUDENT);
            assertThatThrownBy(() -> provisioning.seedDemoAccounts("DifferentPassword-29!"))
                    .isInstanceOf(IllegalStateException.class);
            transaction.setRollbackOnly();
        });
        assertThat(users.findByUsername("demo_student1")).isEmpty();
    }

    @Test
    void localProvisioningNormalizesUsernameAndRejectsDuplicateOrShortPassword() {
        String username = "account_" + UUID.randomUUID().toString().replace("-", "");
        transactions.executeWithoutResult(transaction -> {
            long id = provisioning.provision("  " + username.toUpperCase() + "  ",
                    "  Local Student  ", Role.STUDENT, TEST_PASSWORD);
            User stored = users.findById(id).orElseThrow();
            assertThat(stored.username()).isEqualTo(username);
            assertThat(stored.displayName()).isEqualTo("Local Student");
            assertThat(stored.role()).isEqualTo(Role.STUDENT);
            assertThat(stored.passwordHash()).startsWith("{bcrypt}");
            assertThat(encoder.matches(TEST_PASSWORD, stored.passwordHash())).isTrue();
            assertThatThrownBy(() -> provisioning.provision(username, "Another", Role.STUDENT,
                    TEST_PASSWORD)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provisioning.provision("other_user", "Another",
                    Role.STUDENT, "short")).isInstanceOf(IllegalArgumentException.class);
            transaction.setRollbackOnly();
        });
        assertThat(users.findByUsername(username)).isEmpty();
    }
}
