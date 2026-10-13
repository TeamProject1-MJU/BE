package com.mju.linkro.user;

import com.mju.linkro.auth.domain.RefreshToken;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import com.mju.linkro.user.service.UserService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class UserServiceTest {
	@Autowired UserService userService;
	@Autowired UserRepository users;
	@Autowired RefreshTokenRepository refreshTokens;
	@Autowired TransactionTemplate transaction;

	User user;

	@BeforeEach
	void setUp() {
		refreshTokens.deleteAll();
		users.deleteAll();
		user = users.saveAndFlush(new User("123", "인준"));
	}

	@Test
	void getMeReturnsProfileWithSeoulOffset() {
		var me = userService.getMe(user.getId());

		assertThat(me.id()).isEqualTo(user.getId());
		assertThat(me.nickname()).isEqualTo("인준");
		assertThat(me.createdAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
		assertThat(me.createdAt().toInstant()).isEqualTo(user.getCreatedAt().truncatedTo(ChronoUnit.SECONDS));
	}

	@Test
	void updateNicknameStripsSurroundingWhitespaceAndPersists() {
		var result = userService.updateNickname(user.getId(), "  장선호 \t");

		assertThat(result.id()).isEqualTo(user.getId());
		assertThat(result.nickname()).isEqualTo("장선호");
		assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("장선호");
	}

	@Test
	void duplicateNicknameIsAllowed() {
		users.saveAndFlush(new User("456", "같은이름"));
		assertThat(userService.updateNickname(user.getId(), "같은이름").nickname()).isEqualTo("같은이름");
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 20})
	void boundaryLengthsAreAccepted(int length) {
		String nickname = "가".repeat(length);
		assertThat(userService.updateNickname(user.getId(), " " + nickname + " ").nickname()).isEqualTo(nickname);
	}

	@Test
	void lengthIsCountedInCodePoints() {
		// H2 varchar length counts UTF-16 units, unlike PostgreSQL, so the repositories are mocked here.
		var repository = mock(UserRepository.class);
		var target = new User("789", "기존");
		when(repository.findById(target.getId())).thenReturn(Optional.of(target));
		var service = new UserService(repository, mock(RefreshTokenRepository.class));

		assertThat(service.updateNickname(target.getId(), "🚇".repeat(20)).nickname()).isEqualTo("🚇".repeat(20));
		assertError(() -> service.updateNickname(target.getId(), "🚇".repeat(21)), ErrorCode.VALIDATION_ERROR);
	}

	@ParameterizedTest
	@ValueSource(strings = {"가", " 가 ", "가가가가가가가가가가가가가가가가가가가가가", "", "   ", "\t\n"})
	void invalidNicknamesAreValidationErrors(String nickname) {
		assertError(() -> userService.updateNickname(user.getId(), nickname), ErrorCode.VALIDATION_ERROR);
		assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("인준");
	}

	@Test
	void nullNicknameIsValidationErrorWithNicknameDetail() {
		assertThatThrownBy(() -> userService.updateNickname(user.getId(), null))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> {
				var business = (BusinessException) exception;
				assertThat(business.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
				assertThat(business.getDetails())
					.isEqualTo(Map.of("nickname", "닉네임은 앞뒤 공백을 제외하고 2~20자여야 합니다."));
			});
	}

	@Test
	void withdrawSoftDeletesAndRevokesOnlyThisUsersActiveTokens() {
		var other = users.saveAndFlush(new User("456", "다른사람"));
		var first = token(user, null);
		var second = token(user, null);
		Instant earlier = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
		var alreadyRevoked = token(user, earlier);
		var othersToken = token(other, null);

		userService.withdraw(user.getId());

		assertThat(users.findById(user.getId()).orElseThrow().getDeletedAt()).isNotNull();
		assertThat(revokedAt(first)).isNotNull();
		assertThat(revokedAt(second)).isNotNull();
		// Already revoked rows keep their original revocation time.
		assertThat(revokedAt(alreadyRevoked)).isEqualTo(earlier);
		assertThat(revokedAt(othersToken)).isNull();
		assertThat(users.findById(other.getId()).orElseThrow().getDeletedAt()).isNull();
	}

	@Test
	void withdrawnUserGetsTokenInvalidOnAllThreeOperations() {
		userService.withdraw(user.getId());

		assertError(() -> userService.getMe(user.getId()), ErrorCode.TOKEN_INVALID);
		// The account check comes before nickname validation.
		assertError(() -> userService.updateNickname(user.getId(), "새닉네임"), ErrorCode.TOKEN_INVALID);
		assertError(() -> userService.updateNickname(user.getId(), "가"), ErrorCode.TOKEN_INVALID);
		assertError(() -> userService.withdraw(user.getId()), ErrorCode.TOKEN_INVALID);
		assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("인준");
	}

	@Test
	void missingUserGetsTokenInvalid() {
		UUID missing = UUID.randomUUID();
		assertError(() -> userService.getMe(missing), ErrorCode.TOKEN_INVALID);
		assertError(() -> userService.updateNickname(missing, "새닉네임"), ErrorCode.TOKEN_INVALID);
		assertError(() -> userService.withdraw(missing), ErrorCode.TOKEN_INVALID);
	}

	private RefreshToken token(User owner, Instant revokedAt) {
		var token = refreshTokens.saveAndFlush(new RefreshToken(owner.getId(), UUID.randomUUID(),
			UUID.randomUUID().toString().replace("-", "") + "0".repeat(32), Instant.now().plus(Duration.ofDays(14))));
		if (revokedAt != null) {
			transaction.executeWithoutResult(status -> refreshTokens.revokeFamily(token.getFamilyId(), revokedAt));
		}
		return token;
	}

	private Instant revokedAt(RefreshToken token) {
		return refreshTokens.findById(token.getId()).orElseThrow().getRevokedAt();
	}

	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
		assertThatThrownBy(call).isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException) exception).getErrorCode()).isEqualTo(code);
	}
}
