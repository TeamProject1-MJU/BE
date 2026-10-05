package com.mju.linkro.user.domain;

import com.mju.linkro.common.entity.BaseUuidTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_kakao_id", columnNames = "kakao_id"))
@Getter
public class User extends BaseUuidTimeEntity {

	@Column(name = "kakao_id", nullable = false, length = 64)
	private String kakaoId;
	@Column(nullable = false, length = 20)
	private String nickname;
	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected User() {
	}

	public User(String kakaoId, String nickname) {
		this.kakaoId = kakaoId;
		this.nickname = nickname;
	}

	public void restore() {
		deletedAt = null;
	}

	public void softDelete() {
		deletedAt = Instant.now();
	}
}
