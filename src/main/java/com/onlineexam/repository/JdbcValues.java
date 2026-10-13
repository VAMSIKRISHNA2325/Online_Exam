package com.onlineexam.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** MySQL DATETIME columns represent UTC wall time without a stored zone. */
final class JdbcValues {
    private JdbcValues() {
    }

    static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rows, String column) throws SQLException {
        LocalDateTime value = rows.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static long insert(JdbcTemplate jdbc, String sql, Object... values) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        }, keys);
        if (keys.getKey() == null) {
            throw new IllegalStateException("Insert did not return an ID");
        }
        return keys.getKey().longValue();
    }
}
