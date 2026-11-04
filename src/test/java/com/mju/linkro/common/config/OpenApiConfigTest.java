package com.mju.linkro.common.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class OpenApiConfigTest {
    @ParameterizedTest
    @ValueSource(strings = {"path", "operation", "responses", "status", "content", "json"})
    void optionalMissingDocumentationDoesNotBreakGenerationOrFillBodylessResponses(String missing) {
        var document = new OpenAPI().paths(new Paths());
        var path = new PathItem();
        var operation = new Operation();
        var responses = new ApiResponses();
        var bodyless = new ApiResponse().description("No content");
        responses.addApiResponse("204", bodyless);
        var forbidden = new ApiResponse().description("Optional response");
        if (!missing.equals("content")) forbidden.content(new Content());
        if (!missing.equals("content") && !missing.equals("json")) {
            forbidden.getContent().addMediaType("application/json", new MediaType());
        }
        if (!missing.equals("status")) responses.addApiResponse("403", forbidden);
        if (!missing.equals("responses")) operation.responses(responses);
        if (!missing.equals("operation")) path.patch(operation).delete(operation);
        if (!missing.equals("path")) document.getPaths().addPathItem("/api/v1/rooms/{roomId}/members/me", path);

        assertThatCode(() -> new OpenApiConfig().roomMembershipErrorExamples().customise(document))
                .doesNotThrowAnyException();
        assertThat(bodyless.getContent()).isNull();
        if (missing.equals("content")) assertThat(forbidden.getContent()).isNull();
        if (missing.equals("json")) assertThat(forbidden.getContent()).isEmpty();
    }
}
