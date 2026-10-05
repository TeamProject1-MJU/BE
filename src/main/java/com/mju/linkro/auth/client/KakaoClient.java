package com.mju.linkro.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mju.linkro.auth.dto.KakaoUserInfo;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class KakaoClient {
	private final RestClient restClient;

	@Autowired
	public KakaoClient(RestClient.Builder builder) {
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(3));
		factory.setReadTimeout(Duration.ofSeconds(5));
		this.restClient = builder.baseUrl("https://kapi.kakao.com").requestFactory(factory).build();
	}

	KakaoClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public KakaoUserInfo getUserInfo(String accessToken) {
		try {
			KakaoResponse body = restClient.get().uri("/v2/user/me")
				.headers(headers -> headers.setBearerAuth(accessToken)).retrieve()
				.onStatus(status -> status.value() == 401 || status.value() == 403,
					(request, response) -> { throw new BusinessException(ErrorCode.KAKAO_AUTH_FAILED); })
				.onStatus(status -> !status.is2xxSuccessful(),
					(request, response) -> { throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR); })
				.body(KakaoResponse.class);
			if (body == null || body.id() == null || body.id() <= 0) {
				throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
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

	public record KakaoResponse(Long id, @JsonProperty("kakao_account") Account account) { }
	public record Account(Profile profile) { }
	public record Profile(String nickname) { }
}
