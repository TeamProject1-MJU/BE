package com.mju.linkro.auth.jwt;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider {
	private final SecretKey key;
	private final Duration accessTokenTtl;
	private final JwtParser parser;

	public JwtProvider(@Value("${auth.jwt.secret}") String secret,
		@Value("${auth.jwt.access-token-ttl}") Duration accessTokenTtl) {
		if (accessTokenTtl == null || accessTokenTtl.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("JWT access token TTL must be at least one second");
		}
		this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
		this.accessTokenTtl = accessTokenTtl;
		// Pin HS256 before signature verification so alg-swapped tokens never reach the key.
		this.parser = Jwts.parser().keyLocator(new LocatorAdapter<Key>() {
			@Override
			protected Key locate(JwsHeader header) {
				if (!Jwts.SIG.HS256.getId().equals(header.getAlgorithm())) {
					throw new UnsupportedJwtException("Unsupported JWT algorithm");
				}
				return key;
			}
		}).build();
	}

	public String createAccessToken(UUID userId) {
		Instant now = Instant.now();
		return Jwts.builder().subject(userId.toString()).issuedAt(Date.from(now))
			.expiration(Date.from(now.plus(accessTokenTtl))).signWith(key, Jwts.SIG.HS256).compact();
	}

	/** Exception details and raw tokens are intentionally discarded; only the error code reaches clients. */
	public UUID parseUserId(String token) {
		if (token == null || token.isBlank()) {
			throw new BusinessException(ErrorCode.TOKEN_INVALID);
		}
		Claims claims;
		try {
			claims = parser.parseSignedClaims(token).getPayload();
		} catch (ExpiredJwtException exception) {
			throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
		} catch (JwtException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.TOKEN_INVALID);
		}
		return toUserId(claims.getSubject());
	}

	private static UUID toUserId(String subject) {
		if (subject != null) {
			try {
				UUID userId = UUID.fromString(subject);
				// UUID.fromString also accepts non-canonical forms such as "1-1-1-1-1".
				if (userId.toString().equals(subject)) {
					return userId;
				}
			} catch (IllegalArgumentException exception) {
				// Falls through to TOKEN_INVALID.
			}
		}
		throw new BusinessException(ErrorCode.TOKEN_INVALID);
	}
}
