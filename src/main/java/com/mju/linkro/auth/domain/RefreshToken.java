package com.mju.linkro.auth.domain;

import com.mju.linkro.common.entity.BaseUuidEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Only the SHA-256 hex of the raw token is stored; the raw value is returned to the client once. */
@Entity
@Table(name = "refresh_tokens",
	uniqueConstraints = @UniqueConstraint(name = "uk_refresh_tokens_token_hash", columnNames = "token_hash"),
	indexes = {
		@Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
		@Index(name = "idx_refresh_tokens_family_id", columnList = "family_id")})
@EntityListeners(AuditingEntityListener.class)
@Getter
public class RefreshToken extends BaseUuidEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;
	@Column(name = "family_id", nullable = false, updatable = false)
	private UUID familyId;
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;
	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;
	@Column(name = "revoked_at")
	private Instant revokedAt;
	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected RefreshToken() {
	}

	public RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant expiresAt) {
		this.userId = userId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
	}
}
