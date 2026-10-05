package com.mju.linkro.auth;

import com.mju.linkro.auth.jwt.JwtProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {
    private final byte[] testKey = new byte[32];

    @Test
    void decodedSecretUnder32BytesFails() {
        assertThatThrownBy(() -> new JwtProvider(Base64.getEncoder().encodeToString(new byte[31]), Duration.ofMinutes(1)))
                .isInstanceOf(io.jsonwebtoken.security.WeakKeyException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 999})
    void ttlUnderOneSecondFails(long millis) {
        assertThatThrownBy(() -> new JwtProvider(Base64.getEncoder().encodeToString(testKey), Duration.ofMillis(millis)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validSecretAndTtlProduceSignedToken() {
        var provider = new JwtProvider(Base64.getEncoder().encodeToString(testKey), Duration.ofSeconds(90));
        UUID id = UUID.randomUUID();
        var token = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(testKey)).build().parseSignedClaims(provider.createAccessToken(id));
        assertThat(token.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(token.getPayload().getSubject()).isEqualTo(id.toString());
        assertThat(token.getPayload().getExpiration().getTime() - token.getPayload().getIssuedAt().getTime()).isEqualTo(90000);
    }
}
