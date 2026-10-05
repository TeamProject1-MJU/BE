package com.mju.linkro.auth.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
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

	public JwtProvider(@Value("${auth.jwt.secret}") String secret,
		@Value("${auth.jwt.access-token-ttl}") Duration accessTokenTtl) {
		if (accessTokenTtl == null || accessTokenTtl.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("JWT access token TTL must be at least one second");
		}
		this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
		this.accessTokenTtl = accessTokenTtl;
	}

	public String createAccessToken(UUID userId) {
		Instant now = Instant.now();
		return Jwts.builder().subject(userId.toString()).issuedAt(Date.from(now))
			.expiration(Date.from(now.plus(accessTokenTtl))).signWith(key, Jwts.SIG.HS256).compact();
	}
}
