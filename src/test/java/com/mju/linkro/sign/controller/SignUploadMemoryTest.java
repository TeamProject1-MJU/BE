package com.mju.linkro.sign.controller;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.SignReading;
import com.mju.linkro.ai.api.SignResult;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.sign.SignTestImages;
import com.mju.linkro.subway.api.LineLookup;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A real upload over HTTP to the embedded Tomcat (9.1: images are not stored). The multipart location is an empty
 * directory of its own, and it is listed from inside the AI call, i.e. while the request is still being handled:
 * with the default threshold of 0 Tomcat would already have written the upload there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SignUploadMemoryTest {
    static final Path LOCATION = location();

    @DynamicPropertySource
    static void multipartLocation(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.location", LOCATION::toString);
    }

    private static Path location() {
        try {
            return Files.createTempDirectory("sign-multipart-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired JwtProvider jwtProvider;
    @MockitoBean AiClient ai;
    @MockitoBean StationLookup stations;
    @MockitoBean LineLookup lines;

    @Test
    void anAcceptedUploadStaysInMemoryAndNothingIsWrittenToTheMultipartLocation() throws Exception {
        refreshTokens.deleteAll();
        users.deleteAll();
        User user = users.saveAndFlush(new User("upload-user", "업로드"));
        when(stations.require(105)).thenReturn(new StationInfo(105, "강남", 37.49, 127.02, true));
        when(lines.name(2)).thenReturn(Optional.of("2호선"));
        List<String> seenDuringRequest = new ArrayList<>();
        when(ai.readSign(any(), any())).thenAnswer(call -> {
            seenDuringRequest.addAll(listing());
            return new SignReading(SignResult.CORRECT, null, "강남", "2호선", "사당 방면");
        });

        // ~4MB: well above Tomcat's own small buffers, below the 5MB limit.
        byte[] big = SignTestImages.padded(SignTestImages.jpeg(), 4 * 1024 * 1024);
        String boundary = "----signtest";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, boundary, "image", "sign.jpg", "image/jpeg", big);
        part(body, boundary, "route", null, "application/json",
                SignControllerTest.ROUTE.getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + SignControllerTest.URL))
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(user.getId()))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"result\":\"CORRECT\"");
        assertThat(seenDuringRequest).as("files in the multipart location during the AI call").isEmpty();
        assertThat(listing()).isEmpty();
    }

    private static List<String> listing() throws java.io.IOException {
        try (Stream<Path> files = Files.list(LOCATION)) {
            return files.map(f -> f.getFileName().toString()).toList();
        }
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, String filename, String type, byte[] bytes)
            throws java.io.IOException {
        String disposition = "form-data; name=\"" + name + "\"" + (filename == null ? "" : "; filename=\"" + filename + "\"");
        out.write(("--" + boundary + "\r\nContent-Disposition: " + disposition + "\r\nContent-Type: " + type + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
