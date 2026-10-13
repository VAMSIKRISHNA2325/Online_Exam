package com.onlineexam.security;

import com.onlineexam.repository.UserRepository;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded, single-instance credential-failure counters; no passwords or tokens are retained. */
@Service
public class LoginThrottle {
    private static final int MAX_TRACKED_KEYS = 4096;
    private final UserRepository users;
    private final Clock clock;
    private final int maxFailures;
    private final Duration window;
    private final Duration cooldown;
    private final Map<String, FailureState> failures = new LinkedHashMap<>(16, 0.75f, true);

    public LoginThrottle(UserRepository users, Clock clock,
                         @Value("${exam.auth.throttle.max-failures:5}") int maxFailures,
                         @Value("${exam.auth.throttle.window:PT1M}") Duration window,
                         @Value("${exam.auth.throttle.cooldown:PT30S}") Duration cooldown) {
        if (maxFailures < 2 || maxFailures > 20 || window.isNegative() || window.isZero()
                || cooldown.isNegative() || cooldown.isZero()) {
            throw new IllegalArgumentException("Invalid login throttle configuration");
        }
        this.users = users;
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
        this.cooldown = cooldown;
    }

    public Attempt begin(String username, String remoteAddress) {
        String key = key(username, remoteAddress);
        synchronized (failures) {
            Instant now = clock.instant();
            pruneExpired(now);
            FailureState state = failures.get(key);
            if (state == null || state.blockedUntil == null || !now.isBefore(state.blockedUntil)) {
                return new Attempt(key, 0);
            }
            long milliseconds = Duration.between(now, state.blockedUntil).toMillis();
            int retrySeconds = Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                    (milliseconds + 999L) / 1000L));
            return new Attempt(key, retrySeconds);
        }
    }

    public void credentialFailure(Attempt attempt) {
        if (attempt == null || attempt.retryAfterSeconds() != 0) return;
        synchronized (failures) {
            Instant now = clock.instant();
            pruneExpired(now);
            FailureState state = failures.get(attempt.key());
            if (state == null) {
                if (failures.size() >= MAX_TRACKED_KEYS) {
                    Iterator<String> oldest = failures.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
                state = new FailureState();
                failures.put(attempt.key(), state);
            }
            dropOldFailures(state, now);
            state.recent.addLast(now);
            if (state.recent.size() >= maxFailures) {
                state.recent.clear();
                state.blockedUntil = now.plus(cooldown);
            }
        }
    }

    public void success(Attempt attempt) {
        if (attempt == null) return;
        synchronized (failures) {
            failures.remove(attempt.key());
        }
    }

    private String key(String username, String remoteAddress) {
        String bounded = username == null ? "" : username.substring(0, Math.min(80, username.length()));
        String normalized = Normalizer.normalize(bounded.trim(),
                Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        // A known user's database ID joins equivalent spellings under MySQL's collation.
        String account = username != null && username.length() <= 80 && normalized.length() <= 80
                ? users.findByUsername(username).map(user -> "id:" + user.id())
                        .orElse("name:" + normalized)
                : "name:" + normalized.substring(0, Math.min(80, normalized.length()));
        String source = remoteAddress == null ? "unknown" : remoteAddress;
        return source + '|' + account;
    }

    private void pruneExpired(Instant now) {
        Iterator<FailureState> iterator = failures.values().iterator();
        while (iterator.hasNext()) {
            FailureState state = iterator.next();
            dropOldFailures(state, now);
            if (state.blockedUntil != null && !now.isBefore(state.blockedUntil)) {
                state.blockedUntil = null;
            }
            if (state.blockedUntil == null && state.recent.isEmpty()) iterator.remove();
        }
    }

    private void dropOldFailures(FailureState state, Instant now) {
        Instant cutoff = now.minus(window);
        while (!state.recent.isEmpty() && !state.recent.peekFirst().isAfter(cutoff)) {
            state.recent.removeFirst();
        }
    }

    private static final class FailureState {
        private final Deque<Instant> recent = new ArrayDeque<>();
        private Instant blockedUntil;
    }

    public record Attempt(String key, int retryAfterSeconds) {
        public boolean throttled() {
            return retryAfterSeconds > 0;
        }
    }
}
