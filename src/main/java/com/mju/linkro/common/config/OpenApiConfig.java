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
}
