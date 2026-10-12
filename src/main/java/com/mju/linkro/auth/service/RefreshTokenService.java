package com.mju.linkro.auth.service;

import com.mju.linkro.auth.domain.RefreshToken;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int TOKEN_BYTES = 32;

	private final RefreshTokenRepository refreshTokens;
	private final UserRepository users;
	private final Duration refreshTokenTtl;

	public RefreshTokenService(RefreshTokenRepository refreshTokens, UserRepository users,
		@Value("${auth.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
		if (refreshTokenTtl == null || refreshTokenTtl.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("Refresh token TTL must be at least one second");
		}
		this.refreshTokens = refreshTokens;
		this.users = users;
		this.refreshTokenTtl = refreshTokenTtl;
	}

	/** Returns the raw token; only its hash is persisted. */
	@Transactional
	public String issue(UUID userId, UUID familyId) {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		refreshTokens.save(new RefreshToken(userId, familyId, hash(rawToken), Instant.now().plus(refreshTokenTtl)));
		return rawToken;
	}

	/**
	 * Failures are returned rather than thrown so that a family revocation for reuse commits
	 * with this transaction instead of being rolled back by the TOKEN_INVALID exception.
	 */
	@Transactional
	public Rotation rotate(String rawToken) {
		Instant now = Instant.now();
		RefreshToken token = refreshTokens.findByTokenHash(hash(rawToken)).orElse(null);
		if (token == null) {
			return Rotation.failure(ErrorCode.TOKEN_INVALID);
		}
		if (!token.getExpiresAt().isAfter(now)) {
			return Rotation.failure(ErrorCode.TOKEN_EXPIRED);
		}
		// Already revoked, or a concurrent request with the same token won the conditional update.
		if (token.getRevokedAt() != null || refreshTokens.revokeIfActive(token.getId(), now) == 0) {
			refreshTokens.revokeFamily(token.getFamilyId(), now);
			return Rotation.failure(ErrorCode.TOKEN_INVALID);
		}
		if (users.findById(token.getUserId()).filter(user -> user.getDeletedAt() == null).isEmpty()) {
			return Rotation.failure(ErrorCode.TOKEN_INVALID);
		}
		return new Rotation(token.getUserId(), issue(token.getUserId(), token.getFamilyId()), null);
	}

	/** Unknown or already revoked tokens are ignored so logout always succeeds. */
	@Transactional
	public void revokeFamily(String rawToken) {
		refreshTokens.findByTokenHash(hash(rawToken))
			.ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), Instant.now()));
	}

	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	/** Either a new raw refresh token for userId, or the error to report. */
	public record Rotation(UUID userId, String refreshToken, ErrorCode error) {
		static Rotation failure(ErrorCode error) {
			return new Rotation(null, null, error);
		}
	}
}
