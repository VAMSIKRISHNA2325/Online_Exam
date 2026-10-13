package com.onlineexam.repository;

import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private static final String COLUMNS =
            "id, username, display_name, password_hash, role, enabled, created_at";
    private static final RowMapper<User> MAPPER = UserRepository::map;
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(User user) {
        return JdbcValues.insert(jdbc,
                "INSERT INTO users(username, display_name, password_hash, role, enabled, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                user.username(), user.displayName(), user.passwordHash(), user.role().name(),
                user.enabled(), JdbcValues.utc(user.createdAt()));
    }

    public Optional<User> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE username = ?", MAPPER, username)
                .stream().findFirst();
    }

    public int updateEnabled(long id, boolean enabled) {
        return jdbc.update("UPDATE users SET enabled = ? WHERE id = ?", enabled, id);
    }

    private static User map(ResultSet rows, int rowNumber) throws SQLException {
        return new User(rows.getLong("id"), rows.getString("username"),
                rows.getString("display_name"), rows.getString("password_hash"),
                Role.valueOf(rows.getString("role")), rows.getBoolean("enabled"),
                JdbcValues.instant(rows, "created_at"));
    }
}
