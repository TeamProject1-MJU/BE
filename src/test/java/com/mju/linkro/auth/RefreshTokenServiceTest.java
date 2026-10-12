package com.mju.linkro.auth;

import com.mju.linkro.auth.client.KakaoClient;
import com.mju.linkro.auth.domain.RefreshToken;
import com.mju.linkro.auth.dto.KakaoUserInfo;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.auth.service.AuthService;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Runs against the real repository so committed state is checked, not just method calls. */
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenServiceTest {
	@Autowired AuthService authService;
	@Autowired JwtProvider jwtProvider;
	@Autowired UserRepository users;
	@MockitoSpyBean RefreshTokenRepository refreshTokens;
	@MockitoBean KakaoClient kakaoClient;

	User user;

	@BeforeEach
	void setUp() {
		refreshTokens.deleteAll();
		users.deleteAll();
		user = users.saveAndFlush(new User("123", "인준"));
	}

	@Test
	void loginStoresOnlySha256OfRandomBase64UrlToken() {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "인준"));
		var first = authService.login("test-token");
		var second = authService.login("test-token");

		assertThat(first.refreshToken()).matches("[A-Za-z0-9_-]{43}");
		assertThat(first.accessTokenExpiresIn()).isEqualTo(Duration.ofMinutes(15).toSeconds());
		var stored = find(first.refreshToken());
		assertThat(stored.getTokenHash()).isEqualTo(sha256(first.refreshToken())).hasSize(64);
		assertThat(stored.getUserId()).isEqualTo(user.getId());
		assertThat(stored.getRevokedAt()).isNull();
		assertThat(stored.getCreatedAt()).isNotNull();
		assertThat(stored.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofDays(14)), within(Duration.ofMinutes(1)));
		assertThat(refreshTokens.findAll()).extracting(RefreshToken::getTokenHash).doesNotContain(first.refreshToken());
		// Each login starts its own family.
		assertThat(find(second.refreshToken()).getFamilyId()).isNotEqualTo(stored.getFamilyId());
	}

	@Test
	void refreshRotatesTokenWithinSameFamily() {
		String old = issue(user.getId(), UUID.randomUUID());
		var oldToken = find(old);

		var result = authService.refresh(old);

		assertThat(result.refreshToken()).isNotEqualTo(old);
		assertThat(jwtProvider.parseUserId(result.accessToken())).isEqualTo(user.getId());
		assertThat(result.accessTokenExpiresIn()).isEqualTo(Duration.ofMinutes(15).toSeconds());
		assertThat(find(old).getRevokedAt()).isNotNull();
		var rotated = find(result.refreshToken());
		assertThat(rotated.getFamilyId()).isEqualTo(oldToken.getFamilyId());
		assertThat(rotated.getRevokedAt()).isNull();
		assertThat(rotated.getExpiresAt()).isCloseTo(Instant.now().plus(Duration.ofDays(14)), within(Duration.ofMinutes(1)));
		// The new token can be rotated again.
		assertThat(authService.refresh(result.refreshToken()).refreshToken()).isNotEqualTo(result.refreshToken());
	}

	@Test
	void unknownTokenIsInvalid() {
		assertError(() -> authService.refresh("unknown-token"), ErrorCode.TOKEN_INVALID);
	}

	@Test
	void expiredTokenIsExpiredAndKeepsFamily() {
		UUID family = UUID.randomUUID();
		String sibling = issue(user.getId(), family);
		String expired = "expired-token";
		refreshTokens.saveAndFlush(new RefreshToken(user.getId(), family, sha256(expired), Instant.now().minusSeconds(1)));

		assertError(() -> authService.refresh(expired), ErrorCode.TOKEN_EXPIRED);

		assertThat(find(expired).getRevokedAt()).isNull();
		assertThat(find(sibling).getRevokedAt()).isNull();
		assertThat(authService.refresh(sibling).refreshToken()).isNotBlank();
	}

	@Test
	void reusedTokenRevokesWholeFamilyAndRevocationIsCommitted() {
		UUID family = UUID.randomUUID();
		String old = issue(user.getId(), family);
		String otherFamily = issue(user.getId(), UUID.randomUUID());
		String rotated = authService.refresh(old).refreshToken();

		assertError(() -> authService.refresh(old), ErrorCode.TOKEN_INVALID);

		// Read back in new transactions: the TOKEN_INVALID exception did not roll the revocation back.
		assertThat(find(rotated).getRevokedAt()).isNotNull();
		assertThat(find(otherFamily).getRevokedAt()).isNull();
		assertError(() -> authService.refresh(rotated), ErrorCode.TOKEN_INVALID);
	}

	@Test
	void concurrentRequestLosingConditionalUpdateRevokesFamily() {
		UUID family = UUID.randomUUID();
		String token = issue(user.getId(), family);
		// This request's lookup saw the token before another request with the same token used it.
		var staleLookup = find(token);
		String winnersToken = authService.refresh(token).refreshToken();
		doReturn(Optional.of(staleLookup)).when(refreshTokens).findByTokenHash(sha256(token));

		// revokedAt is null in the lookup, so only the real conditional UPDATE (0 rows) can reject it.
		assertError(() -> authService.refresh(token), ErrorCode.TOKEN_INVALID);

		assertThat(staleLookup.getRevokedAt()).isNull();
		verify(refreshTokens, times(2)).revokeIfActive(eq(staleLookup.getId()), any());
		verify(refreshTokens).revokeFamily(eq(family), any());
		assertThat(find(winnersToken).getRevokedAt()).isNotNull();
	}

	@Test
	void deletedUserTokenIsInvalid() {
		UUID family = UUID.randomUUID();
		String token = issue(user.getId(), family);
		String sibling = issue(user.getId(), family);
		user.softDelete();
		users.saveAndFlush(user);
		long rows = refreshTokens.count();

		assertError(() -> authService.refresh(token), ErrorCode.TOKEN_INVALID);

		// The user check runs after the conditional UPDATE, so the presented token is used up
		// while the rest of the family is left to account deletion (API spec 4.6).
		assertThat(refreshTokens.count()).isEqualTo(rows);
		assertThat(find(token).getRevokedAt()).isNotNull();
		assertThat(find(sibling).getRevokedAt()).isNull();
	}

	@Test
	void logoutRevokesWholeFamilyOnly() {
		UUID family = UUID.randomUUID();
		String first = issue(user.getId(), family);
		String second = issue(user.getId(), family);
		String otherFamily = issue(user.getId(), UUID.randomUUID());

		authService.logout(first);

		assertThat(find(first).getRevokedAt()).isNotNull();
		assertThat(find(second).getRevokedAt()).isNotNull();
		assertThat(find(otherFamily).getRevokedAt()).isNull();
		// Logging out again with an already revoked token still succeeds.
		authService.logout(first);
	}

	@Test
	void logoutWithUnknownTokenSucceedsWithoutChanges() {
		String token = issue(user.getId(), UUID.randomUUID());

		authService.logout("unknown-token");

		assertThat(find(token).getRevokedAt()).isNull();
	}

	private String issue(UUID userId, UUID familyId) {
		String raw = UUID.randomUUID().toString();
		refreshTokens.saveAndFlush(new RefreshToken(userId, familyId, sha256(raw), Instant.now().plus(Duration.ofDays(14))));
		return raw;
	}

	private RefreshToken find(String raw) {
		return refreshTokens.findByTokenHash(sha256(raw)).orElseThrow();
	}

	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
		assertThatThrownBy(call).isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException) exception).getErrorCode()).isEqualTo(code);
	}

	private static String sha256(String raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}
}
