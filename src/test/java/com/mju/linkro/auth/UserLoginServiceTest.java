package com.mju.linkro.auth;

import com.mju.linkro.auth.client.KakaoClient;
import com.mju.linkro.auth.dto.KakaoUserInfo;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.service.AuthService;
import com.mju.linkro.auth.service.UserLoginService;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
        var result = new UserLoginService(repository).login(new KakaoUserInfo("123", "🚇".repeat(21)));
        assertThat(result.user().getNickname()).isEqualTo("🚇".repeat(20));
        assertThat(result.user().getNickname().codePointCount(0, result.user().getNickname().length())).isEqualTo(20);
        assertThat(result.user().getId().version()).isEqualTo(7);
        assertThat(result.isNewUser()).isTrue();
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
        var result = new AuthService(client, users, jwt).login("test-token");
        assertThat(result.user().id()).isEqualTo(winner.getId());
        assertThat(result.user().nickname()).isEqualTo("existing nickname");
        assertThat(result.isNewUser()).isFalse();
        verify(users).loginExisting("123");
    }
}
