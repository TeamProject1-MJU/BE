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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Checks the filter inside the full application context, including real Swagger endpoints. */
@SpringBootTest
@ActiveProfiles("test")
class AuthWebIntegrationTest {
	@Autowired
	WebApplicationContext context;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity()).build();
	}

	@Test
	void apiDocsAreServedWithoutToken() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
		mvc.perform(get("/swagger-ui/swagger-ui.css")).andExpect(status().isOk());
		mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
		mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
	}

	@Test
	void openApiDocumentsBearerOnlyForProtectedOperations() throws Exception {
		var document = mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
			.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		var root = tools.jackson.databind.json.JsonMapper.builder().build().readTree(document);
		java.nio.file.Files.writeString(java.nio.file.Path.of("build/openapi-test.json"), document);
		for (String path : java.util.List.of("/api/v1/rooms", "/api/v1/rooms/{roomId}",
			"/api/v1/rooms/{roomId}/members", "/api/v1/rooms/{roomId}/invite-code", "/api/v1/users/me")) {
			for (var operation : root.get("paths").get(path)) {
				org.assertj.core.api.Assertions.assertThat(operation.get("security").get(0).has("bearerAuth")).isTrue();
				var parameters = operation.get("parameters");
				if (parameters != null) {
					org.assertj.core.api.Assertions.assertThat(parameters.size()).isEqualTo(1);
					org.assertj.core.api.Assertions.assertThat(parameters.get(0).get("name").asString()).isEqualTo("roomId");
					org.assertj.core.api.Assertions.assertThat(parameters.get(0).get("in").asString()).isEqualTo("path");
					org.assertj.core.api.Assertions.assertThat(parameters.get(0).get("required").asBoolean()).isTrue();
					org.assertj.core.api.Assertions.assertThat(parameters.get(0).get("schema").get("format").asString()).isEqualTo("uuid");
				} else {
					org.assertj.core.api.Assertions.assertThat(path).doesNotContain("{roomId}");
				}
			}
		}
		for (String path : java.util.List.of("kakao", "refresh", "logout")) {
			var security = root.get("paths").get("/api/v1/auth/" + path).get("post").get("security");
			org.assertj.core.api.Assertions.assertThat(security == null || security.isEmpty()).isTrue();
		}
		org.assertj.core.api.Assertions.assertThat(root.has("security")).isFalse();
	}

	@Test
	void openApiSchemasAndStatusesMatchControllers() throws Exception {
		var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
		var root = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
		var schemas = root.get("components").get("schemas");
		assertLocalReferencesResolve(root, root);
		org.assertj.core.api.Assertions.assertThat(schemas.has("JsonNode")).isFalse();
		org.assertj.core.api.Assertions.assertThat(schemas.has("RoomWriteRequest")).isFalse();
		var paths = root.get("paths");
		var create = paths.get("/api/v1/rooms").get("post");
		org.assertj.core.api.Assertions.assertThat(create.get("responses").has("200")).isFalse();
		org.assertj.core.api.Assertions.assertThat(create.get("responses").has("201")).isTrue();
		org.assertj.core.api.Assertions.assertThat(create.get("requestBody").get("required").asBoolean()).isTrue();
		var createSchema = schemas.get("RoomCreateRequest");
		org.assertj.core.api.Assertions.assertThat(createSchema.get("required").toString()).isEqualTo("[\"name\"]");
		org.assertj.core.api.Assertions.assertThat(createSchema.get("properties").get("name").get("type").asString()).isEqualTo("string");
		var patchSchema = schemas.get("RoomPatchRequest");
		org.assertj.core.api.Assertions.assertThat(patchSchema.has("required")).isFalse();
		org.assertj.core.api.Assertions.assertThat(patchSchema.get("properties").get("name").get("type").asString()).isEqualTo("string");
		org.assertj.core.api.Assertions.assertThat(patchSchema.get("properties").get("appointmentAt").get("type").toString()).contains("string", "null");
		org.assertj.core.api.Assertions.assertThat(patchSchema.get("properties").get("name").get("description").asString()).contains("생략", "null", "VALIDATION_ERROR");
		org.assertj.core.api.Assertions.assertThat(patchSchema.get("properties").get("appointmentAt").get("description").asString()).contains("생략", "null", "해제");
		for (String[] contract : java.util.List.of(
			new String[]{"/api/v1/rooms", "post", "201", "RoomCreated"},
			new String[]{"/api/v1/rooms", "get", "200", "RoomList"},
			new String[]{"/api/v1/rooms/{roomId}", "get", "200", "RoomDetail"},
			new String[]{"/api/v1/rooms/{roomId}", "patch", "200", "EmptySuccess"},
			new String[]{"/api/v1/rooms/{roomId}/members", "get", "200", "RoomMembers"},
			new String[]{"/api/v1/rooms/{roomId}/invite-code", "get", "200", "RoomInviteCode"},
			new String[]{"/api/v1/auth/kakao", "post", "200", "Login"},
			new String[]{"/api/v1/auth/refresh", "post", "200", "Tokens"},
			new String[]{"/api/v1/auth/logout", "post", "200", "EmptySuccess"},
			new String[]{"/api/v1/users/me", "get", "200", "Profile"},
			new String[]{"/api/v1/users/me", "patch", "200", "Nickname"},
			new String[]{"/api/v1/users/me", "delete", "200", "EmptySuccess"})) {
			var response = paths.get(contract[0]).get(contract[1]).get("responses").get(contract[2]);
			org.assertj.core.api.Assertions.assertThat(response.get("content").get("application/json").get("schema").get("$ref").asString())
				.isEqualTo("#/components/schemas/" + contract[3]);
			org.assertj.core.api.Assertions.assertThat(schemas.get(contract[3]).get("properties").has("data")).isTrue();
		}
		for (String envelope : java.util.List.of("RoomList", "RoomMembers")) {
			org.assertj.core.api.Assertions.assertThat(schemas.get(envelope).get("properties").get("data").get("type").asString()).isEqualTo("array");
		}
		org.assertj.core.api.Assertions.assertThat(schemas.get("EmptySuccess").get("properties").get("data").get("type").asString()).isEqualTo("null");
		for (String payload : java.util.List.of("Summary", "Detail", "Member")) {
			org.assertj.core.api.Assertions.assertThat(schemas.get(payload).get("properties").has("roomCode")).isFalse();
		}
		org.assertj.core.api.Assertions.assertThat(schemas.get("Created").get("properties").has("roomCode")).isTrue();
		org.assertj.core.api.Assertions.assertThat(schemas.get("Created").get("properties").get("appointmentAt").get("type").toString()).contains("null");
		org.assertj.core.api.Assertions.assertThat(schemas.get("Member").get("properties").get("departureStation").get("anyOf")).isNotNull();
		for (String model : java.util.List.of("Summary", "Detail", "Member")) {
			var station = schemas.get(model).get("properties").get(model.equals("Member") ? "departureStation" : "selectedStation");
			org.assertj.core.api.Assertions.assertThat(station.has("$ref")).isFalse();
			org.assertj.core.api.Assertions.assertThat(station.get("anyOf").get(0).get("$ref").asString()).isEqualTo("#/components/schemas/Station");
			org.assertj.core.api.Assertions.assertThat(station.get("anyOf").get(1).get("type").asString()).isEqualTo("null");
		}
		org.assertj.core.api.Assertions.assertThat(paths.get("/api/v1/rooms/{roomId}").get("patch").get("responses").has("409")).isTrue();
		org.assertj.core.api.Assertions.assertThat(paths.get("/api/v1/auth/kakao").get("post").get("responses").has("502")).isTrue();
	}

	private static void assertLocalReferencesResolve(tools.jackson.databind.JsonNode root, tools.jackson.databind.JsonNode node) {
		if (node.isObject() && node.has("$ref")) {
			String reference = node.get("$ref").asString();
			org.assertj.core.api.Assertions.assertThat(reference).startsWith("#/");
			org.assertj.core.api.Assertions.assertThat(root.at(reference.substring(1)).isMissingNode())
				.as("Unresolved OpenAPI reference: %s", reference).isFalse();
		}
		if (node.isContainer()) for (var child : node) assertLocalReferencesResolve(root, child);
	}

	@Test
	void kakaoLoginReachesControllerWithoutToken() throws Exception {
		// VALIDATION_ERROR proves the request passed the filter without calling Kakao.
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
