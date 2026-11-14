package com.mju.linkro.auth;

import com.mju.linkro.auth.client.KakaoClient;
import com.mju.linkro.auth.dto.KakaoUserInfo;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.service.AuthService;
import com.mju.linkro.auth.service.RefreshTokenService;
import com.mju.linkro.auth.service.UserLoginService;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserLoginServiceTest {
    @Test
    void supplementaryNicknameIsLimitedByCodePointsBeforePersistence() {
        // H2 varchar length counts UTF-16 units, unlike the production code-point contract.
        var repository = mock(UserRepository.class);
        when(repository.findByKakaoId("123")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result = new UserLoginService(repository, null).login(new KakaoUserInfo("123", "🚇".repeat(21)));
        assertThat(result.user().getNickname()).isEqualTo("🚇".repeat(20));
        assertThat(result.user().getNickname().codePointCount(0, result.user().getNickname().length())).isEqualTo(20);
        assertThat(result.user().getId().version()).isEqualTo(7);
        assertThat(result.isNewUser()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000닉네임", "닉\u0000네임", "닉네임\u0000", "닉네임"})
    void initialNicknameRemovesNulBeforePersistence(String nickname) {
        assertThat(loginNewUser(nickname).getNickname()).isEqualTo("닉네임");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"\u0000", "\u0000 \t\u0000", "", "   "})
    void blankNicknameAfterRemovingNulUsesDefault(String nickname) {
        assertThat(loginNewUser(nickname).getNickname()).matches("사용자[1-9][0-9]{3}");
    }

    @Test
    void nulIsRemovedBeforeCodePointTruncation() {
        assertThat(loginNewUser("\u0000" + "😀".repeat(21)).getNickname()).isEqualTo("😀".repeat(20));
        assertThat(loginNewUser("가".repeat(21)).getNickname()).isEqualTo("가".repeat(20));
    }

    private User loginNewUser(String nickname) {
        var repository = mock(UserRepository.class);
        when(repository.findByKakaoId("123")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result = new UserLoginService(repository, null).login(new KakaoUserInfo("123", nickname));
        verify(repository).saveAndFlush(result.user());
        assertThat(result.isNewUser()).isTrue();
        return result.user();
    }

    @Test
    void insertConflictRetriesUsingExistingUserTransaction() {
        var client = mock(KakaoClient.class);
        var users = mock(UserLoginService.class);
        var jwt = mock(JwtProvider.class);
        var info = new KakaoUserInfo("123", "new nickname");
        var winner = new User("123", "existing nickname");
        when(client.getUserInfo("test-token")).thenReturn(info);
        when(users.login(info)).thenThrow(new DataIntegrityViolationException("duplicate"));
        when(users.loginExisting("123")).thenReturn(new UserLoginService.LoginResult(winner, false));
        when(jwt.createAccessToken(winner.getId())).thenReturn("test-jwt");
        var refreshTokens = mock(RefreshTokenService.class);
        when(refreshTokens.issue(eq(winner.getId()), any())).thenReturn("test-refresh");
        var result = new AuthService(client, users, jwt, refreshTokens).login("test-token");
        assertThat(result.user().id()).isEqualTo(winner.getId());
        assertThat(result.user().nickname()).isEqualTo("existing nickname");
        assertThat(result.isNewUser()).isFalse();
        verify(users).loginExisting("123");
    }
}
