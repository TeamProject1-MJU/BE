package com.mju.linkro.auth;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderParseTest {
	// 64 bytes so HS512 can be signed with the same key in the algorithm-swap test.
	private final byte[] keyBytes = "test-only-secret-0123456789abcdefghijklmnopqrstuvwxyz-0123456789".getBytes(StandardCharsets.UTF_8);
	private final SecretKey key = Keys.hmacShaKeyFor(keyBytes);
	private final JwtProvider provider = new JwtProvider(Base64.getEncoder().encodeToString(keyBytes), Duration.ofMinutes(15));

	@Test
	void validTokenReturnsSubjectUserId() {
		UUID userId = UUID.randomUUID();
		assertThat(provider.parseUserId(provider.createAccessToken(userId))).isEqualTo(userId);
	}

	@Test
	void expiredTokenFailsWithTokenExpired() {
		String token = Jwts.builder().subject(UUID.randomUUID().toString())
			.expiration(Date.from(Instant.now().minusSeconds(60))).signWith(key, Jwts.SIG.HS256).compact();
		assertFails(token, ErrorCode.TOKEN_EXPIRED);
	}

	@Test
	void tamperedPayloadFailsWithTokenInvalid() {
		String[] parts = provider.createAccessToken(UUID.randomUUID()).split("\\.");
		String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
			("{\"sub\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8));
		assertFails(parts[0] + "." + forged + "." + parts[2], ErrorCode.TOKEN_INVALID);
	}

	@Test
	void tokenSignedWithOtherKeyFailsWithTokenInvalid() {
		SecretKey otherKey = Keys.hmacShaKeyFor(new byte[64]);
		assertFails(signed(otherKey, UUID.randomUUID().toString()), ErrorCode.TOKEN_INVALID);
	}

	@Test
	void expiredTokenSignedWithOtherKeyIsInvalidNotExpired() {
		String token = Jwts.builder().subject(UUID.randomUUID().toString())
			.expiration(Date.from(Instant.now().minusSeconds(60))).signWith(Keys.hmacShaKeyFor(new byte[64]), Jwts.SIG.HS256).compact();
		assertFails(token, ErrorCode.TOKEN_INVALID);
	}

	@Test
	void unsecuredAlgNoneTokenFailsWithTokenInvalid() {
		String token = Jwts.builder().subject(UUID.randomUUID().toString()).compact();
		assertThat(token).endsWith(".");
		assertFails(token, ErrorCode.TOKEN_INVALID);
	}

	@Test
	void otherAlgorithmWithSameKeyFailsWithTokenInvalid() {
		String token = Jwts.builder().subject(UUID.randomUUID().toString()).signWith(key, Jwts.SIG.HS512).compact();
		assertFails(token, ErrorCode.TOKEN_INVALID);
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", ""})
	void nonUuidSubjectFailsWithTokenInvalid(String subject) {
		assertFails(signed(key, subject), ErrorCode.TOKEN_INVALID);
	}

	@Test
	void missingSubjectFailsWithTokenInvalid() {
		assertFails(Jwts.builder().signWith(key, Jwts.SIG.HS256).compact(), ErrorCode.TOKEN_INVALID);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "not.a.jwt", "garbage"})
	void blankOrMalformedTokenFailsWithTokenInvalid(String token) {
		assertFails(token, ErrorCode.TOKEN_INVALID);
	}

	private static String signed(SecretKey signingKey, String subject) {
		return Jwts.builder().subject(subject).signWith(signingKey, Jwts.SIG.HS256).compact();
	}

	private void assertFails(String token, ErrorCode code) {
		assertThatThrownBy(() -> provider.parseUserId(token))
			.isInstanceOfSatisfying(BusinessException.class, exception -> {
				assertThat(exception.getErrorCode()).isEqualTo(code);
				// Parser messages and tokens must not leak through causes or details.
				assertThat(exception.getCause()).isNull();
				assertThat(exception.getDetails()).isEmpty();
			});
	}
}
