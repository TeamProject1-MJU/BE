package com.mju.linkro.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Set;
import com.mju.linkro.room.dto.RoomResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.common.response.ApiResponse;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import java.util.Map;

/** Documentation only; authentication and authorization remain in SecurityConfig. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI().components(new Components().addSecuritySchemes(BEARER_AUTH,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("로그인 응답의 Access Token만 입력하세요. Bearer 접두사는 자동으로 추가됩니다.")));
    }

    @Bean
    OpenApiCustomizer nullableRoomStationSchemas() {
        return document -> {
            // OAS 3.1 requires a union. A $ref sibling would still reject null.
            // springdoc 3.1.1 does not emit a referenced null-only model from annotations.
            var schemas = document.getComponents().getSchemas();
            for (String model : new String[]{RoomResponses.SUMMARY_SCHEMA, RoomResponses.DETAIL_SCHEMA, RoomResponses.MEMBER_SCHEMA}) {
                Schema<?> schema = schemas.get(model);
                if (schema == null) continue;
                Schema<Object> nullValue = new Schema<>();
                nullValue.setTypes(Set.of("null"));
                schema.getProperties().put(model.equals(RoomResponses.MEMBER_SCHEMA) ? "departureStation" : "selectedStation",
                        new ComposedSchema().addAnyOfItem(new Schema<>().$ref("#/components/schemas/" + RoomResponses.STATION_SCHEMA))
                                .addAnyOfItem(nullValue));
            }
        };
    }

    @Bean
    OpenApiCustomizer roomMembershipErrorExamples() {
        return document -> {
            var selection = document.getPaths().get("/api/v1/rooms/{roomId}/selected-station");
            if (selection != null) {
                addMembershipErrorExamples(selection.getPut(), ErrorCode.NOT_ROOM_MEMBER, ErrorCode.NOT_ROOM_OWNER,
                        ErrorCode.ROOM_NOT_FOUND, ErrorCode.STATION_NOT_FOUND, ErrorCode.STATION_UNSUPPORTED, ErrorCode.ROOM_BUSY);
                addMembershipErrorExamples(selection.getDelete(), ErrorCode.NOT_ROOM_MEMBER, ErrorCode.NOT_ROOM_OWNER,
                        ErrorCode.ROOM_NOT_FOUND, ErrorCode.ROOM_BUSY);
            }
            var withdrawal = document.getPaths().get("/api/v1/users/me");
            if (withdrawal != null) addMembershipErrorExamples(withdrawal.getDelete(), ErrorCode.ROOM_BUSY);
            if (withdrawal != null) addMembershipErrorExamples(withdrawal.getPatch(), ErrorCode.ROOM_BUSY);
            for (String path : new String[]{"/api/v1/auth/kakao", "/api/v1/auth/refresh"}) {
                var auth = document.getPaths().get(path);
                if (auth != null) addErrorExamples(auth.getPost(), ErrorCode.ROOM_BUSY);
            }
            var join = document.getPaths().get("/api/v1/rooms/join");
            if (join != null) addMembershipErrorExamples(join.getPost(), ErrorCode.ROOM_CODE_INVALID,
                    ErrorCode.ALREADY_JOINED, ErrorCode.ROOM_FULL, ErrorCode.ROOM_BUSY);
            var myMembership = document.getPaths().get("/api/v1/rooms/{roomId}/members/me");
            if (myMembership != null) addMembershipErrorExamples(myMembership.getDelete(), ErrorCode.NOT_ROOM_MEMBER,
                    ErrorCode.ROOM_NOT_FOUND, ErrorCode.ROOM_BUSY);
            if (myMembership != null) addMembershipErrorExamples(myMembership.getPatch(), ErrorCode.NOT_ROOM_MEMBER,
                    ErrorCode.ROOM_NOT_FOUND, ErrorCode.STATION_NOT_FOUND, ErrorCode.ROOM_BUSY,
                    ErrorCode.STATION_UNSUPPORTED);
        };
    }

    private static void addMembershipErrorExamples(Operation operation, ErrorCode... codes) {
        addErrorExamples(operation, ErrorCode.VALIDATION_ERROR, ErrorCode.AUTH_REQUIRED,
                ErrorCode.TOKEN_INVALID, ErrorCode.TOKEN_EXPIRED, ErrorCode.INTERNAL_ERROR);
        addErrorExamples(operation, codes);
    }

    private static void addErrorExamples(Operation operation, ErrorCode... codes) {
        if (operation == null || operation.getResponses() == null) return;
        for (ErrorCode code : codes) {
            var response = operation.getResponses().get(Integer.toString(code.getStatus().value()));
            // Optional/bodyless responses need no examples. Required contracts are checked on /v3/api-docs.
            if (response == null || response.getContent() == null
                    || response.getContent().get("application/json") == null) continue;
            response.getContent().get("application/json").addExamples(code.getCode(),
                    new Example().value(ApiResponse.failure(code.getCode(), code.getMessage(), Map.of())));
        }
    }
}
