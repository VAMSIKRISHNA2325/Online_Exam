package com.onlineexam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.onlineexam.domain.Role;
import com.onlineexam.domain.User;
import com.onlineexam.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {
    @Test
    void thresholdCooldownWindowAndSuccessResetAreBounded() {
        MutableClock clock = new MutableClock();
        UserRepository users = mock(UserRepository.class);
        User account = new User(10, "alice", "Alice", "encoded", Role.STUDENT,
                true, clock.instant());
        when(users.findByUsername("alice")).thenReturn(Optional.of(account));
        when(users.findByUsername("ALICE")).thenReturn(Optional.of(account));
        LoginThrottle throttle = new LoginThrottle(users, clock, 5,
                Duration.ofMinutes(1), Duration.ofSeconds(30));

        for (int attempt = 0; attempt < 5; attempt++) {
            LoginThrottle.Attempt current = throttle.begin("alice", "127.0.0.1");
            assertThat(current.throttled()).isFalse();
            throttle.credentialFailure(current);
        }
        assertThat(throttle.begin("ALICE", "127.0.0.1").retryAfterSeconds()).isEqualTo(30);
        assertThat(throttle.begin("alice", "127.0.0.2").throttled()).isFalse();
        clock.advance(Duration.ofSeconds(30));
        LoginThrottle.Attempt recovered = throttle.begin("alice", "127.0.0.1");
        assertThat(recovered.throttled()).isFalse();
        throttle.credentialFailure(recovered);
        throttle.success(recovered);
        assertThat(throttle.begin("alice", "127.0.0.1").throttled()).isFalse();

        for (int attempt = 0; attempt < 4; attempt++) {
            throttle.credentialFailure(throttle.begin("alice", "127.0.0.1"));
        }
        clock.advance(Duration.ofSeconds(61));
        throttle.credentialFailure(throttle.begin("alice", "127.0.0.1"));
        assertThat(throttle.begin("alice", "127.0.0.1").throttled()).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T00:00:00Z");

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
