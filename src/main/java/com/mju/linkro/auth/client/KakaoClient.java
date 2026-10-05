package com.mju.linkro.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mju.linkro.auth.dto.KakaoUserInfo;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class KakaoClient {
	private final RestClient restClient;
	private final long appId;

	@Autowired
	public KakaoClient(RestClient.Builder builder,
		@Value("${auth.kakao.app-id}") long appId,
		@Value("${auth.kakao.base-url}") String baseUrl,
		@Value("${auth.kakao.connect-timeout}") Duration connectTimeout,
		@Value("${auth.kakao.read-timeout}") Duration readTimeout) {
		if (appId <= 0) { throw new IllegalArgumentException("Kakao App ID must be positive"); }
		this.appId = appId;
		// Intentionally replace Boot's default HTTP client to enforce these timeouts.
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(readTimeout);
		this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
	}

	KakaoClient(RestClient restClient, long appId) {
		this.restClient = restClient;
		this.appId = appId;
	}

	public KakaoUserInfo getUserInfo(String accessToken) {
		try {
			TokenInfo token = fetch(accessToken, "/v1/user/access_token_info", TokenInfo.class);
			if (token == null || token.id() == null || token.id() <= 0
				|| token.expiresIn() == null || token.expiresIn() <= 0
				|| token.appId() == null || token.appId() != appId) {
				throw new BusinessException(ErrorCode.KAKAO_AUTH_FAILED);
			}
			KakaoResponse body = fetch(accessToken, "/v2/user/me", KakaoResponse.class);
			if (body == null || body.id() == null || body.id() <= 0) {
				throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
			}
			if (!token.id().equals(body.id())) {
				throw new BusinessException(ErrorCode.KAKAO_AUTH_FAILED);
			}
			String nickname = body.account() != null && body.account().profile() != null
				? body.account().profile().nickname() : null;
			return new KakaoUserInfo(body.id().toString(), nickname);
		} catch (RestClientException | IllegalArgumentException exception) {
			// Upstream messages/bodies and nested causes may contain credentials. Preserve
			// the failure type and stack location for #14 logging, without that raw text.
			var safeCause = new RestClientException("Kakao request failed: " + exception.getClass().getSimpleName());
			safeCause.setStackTrace(exception.getStackTrace());
			throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, safeCause);
		}
	}

	private <T> T fetch(String accessToken, String path, Class<T> type) {
		return restClient.get().uri(path).headers(headers -> headers.setBearerAuth(accessToken)).retrieve()
			.onStatus(status -> status.value() == 401 || status.value() == 403,
				(request, response) -> { throw new BusinessException(ErrorCode.KAKAO_AUTH_FAILED); })
			.onStatus(status -> !status.is2xxSuccessful(),
				(request, response) -> { throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR); })
			.body(type);
	}

	public record TokenInfo(Long id, @JsonProperty("expires_in") Long expiresIn,
		@JsonProperty("app_id") Long appId) { }

	public record KakaoResponse(Long id, @JsonProperty("kakao_account") Account account) { }
	public record Account(Profile profile) { }
	public record Profile(String nickname) { }
}
