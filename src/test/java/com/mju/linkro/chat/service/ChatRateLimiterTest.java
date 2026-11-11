package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mju.linkro.chat.ChatProperties;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatRateLimiterTest {
    static final long SECOND = 1_000_000_000L;
    ChatProperties properties = new ChatProperties();
    long[] now = {5 * SECOND};
    ChatRateLimiter limiter = new ChatRateLimiter(properties, () -> now[0]);
    UUID user = UUID.randomUUID();

    @Test
    void theDefaultIsOneCallPerTenSeconds() {
        assertThat(properties.getAiCallInterval()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theSecondCallInsideTheIntervalIsRefusedWithTheSecondsLeftRoundedUp() {
        limiter.claim(user);
        now[0] += 2_500_000_000L;

        assertThatThrownBy(() -> limiter.claim(user)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
            assertThat(e.getDetails().get("retryAfterSeconds")).isEqualTo(8L);
        });
    }

    @Test
    void aRefusedCallDoesNotRestartTheInterval() {
        limiter.claim(user);
        now[0] += 9 * SECOND;
        assertThatThrownBy(() -> limiter.claim(user)).isInstanceOf(BusinessException.class);
        now[0] += SECOND;

        assertThatCode(() -> limiter.claim(user)).doesNotThrowAnyException();
    }

    @Test
    void usersAreCountedSeparately() {
        limiter.claim(user);

        assertThatCode(() -> limiter.claim(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void releaseGivesTheTurnBack() {
        limiter.claim(user);
        limiter.release(user);

        assertThatCode(() -> limiter.claim(user)).doesNotThrowAnyException();
    }

    @Test
    void zeroTurnsTheLimitOff() {
        properties.setAiCallInterval(Duration.ZERO);

        for (int i = 0; i < 5; i++) limiter.claim(user);
    }

    @Test
    void theIntervalIsReadFromTheSettingEachTime() {
        properties.setAiCallInterval(Duration.ofSeconds(1));
        limiter.claim(user);
        now[0] += SECOND;

        assertThatCode(() -> limiter.claim(user)).doesNotThrowAnyException();
    }
}
