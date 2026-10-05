package com.mju.linkro.user.repository;

import com.mju.linkro.user.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
	Optional<User> findByKakaoId(String kakaoId);
}
