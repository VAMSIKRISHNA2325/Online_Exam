package com.onlineexam;

import java.net.URI;

final class IntegrationDb {
    private IntegrationDb() {
    }

    static String url() {
        String url = System.getenv().getOrDefault("MYSQL_IT_DB_URL",
                "jdbc:mysql://127.0.0.1:3307/online_exam_it?connectionTimeZone=UTC");
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("MySQL test schema required");
        URI parsed = URI.create(url.substring("jdbc:".length()));
        if (!"mysql".equals(parsed.getScheme()) || parsed.getHost() == null
                || !"/online_exam_it".equals(parsed.getPath())
                || parsed.getUserInfo() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("MySQL test schema required");
        }
        return url;
    }

    static String username() {
        return System.getenv().getOrDefault("MYSQL_IT_USER", "online_exam");
    }

    static String password() {
        String password = System.getenv("MYSQL_IT_PASSWORD");
        if (password == null || password.isBlank()) password = System.getenv("MYSQL_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("MySQL IT password required");
        return password;
    }
}
