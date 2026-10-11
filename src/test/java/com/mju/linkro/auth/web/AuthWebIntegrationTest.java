package com.mju.linkro.auth.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Checks the interceptor inside the full application context, including real Swagger endpoints. */
@SpringBootTest
@ActiveProfiles("test")
class AuthWebIntegrationTest {
	@Autowired
	WebApplicationContext context;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	void apiDocsAreServedWithoutToken() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
	}

	@Test
	void kakaoLoginReachesControllerWithoutToken() throws Exception {
		// VALIDATION_ERROR proves the request passed the interceptor without calling Kakao.
		mvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void unknownApiPathRequiresToken() throws Exception {
		mvc.perform(get("/api/v1/rooms"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
	}
}
