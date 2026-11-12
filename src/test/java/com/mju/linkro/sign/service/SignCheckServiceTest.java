package com.mju.linkro.sign.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.SignContext;
import com.mju.linkro.ai.api.SignImage;
import com.mju.linkro.ai.api.SignReading;
import com.mju.linkro.ai.api.SignReason;
import com.mju.linkro.ai.api.SignResult;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.sign.SignTestImages;
import com.mju.linkro.sign.dto.SignCheckResponse;
import com.mju.linkro.sign.dto.SignRouteRequest;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.LineLookup;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SignCheckServiceTest {
    static final byte[] JPEG = SignTestImages.jpeg();
    static final byte[] PNG = SignTestImages.png();

    StationLookup stations = mock(StationLookup.class);
    LineLookup lines = mock(LineLookup.class);
    AiClient ai = mock(AiClient.class);
    SignCheckService service = new SignCheckService(ai, stations, lines);

    @BeforeEach
    void setUp() {
        when(stations.require(105)).thenReturn(new StationInfo(105, "강남", 37.49, 127.02, true));
        when(lines.name(2)).thenReturn(Optional.of("2호선"));
    }

    static SignRouteRequest route(String direction) {
        return new SignRouteRequest(105L, 2L, direction, "사당 방면");
    }

    @Test
    void passesNamesAndDirectionToAiAndReturnsItsVerdictAsIs() {
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.WRONG, SignReason.DIRECTION_MISMATCH, "강남", "2호선", "성수 방면"));

        SignCheckResponse response = service.check(JPEG, route("INNER"));

        ArgumentCaptor<SignImage> image = ArgumentCaptor.forClass(SignImage.class);
        ArgumentCaptor<SignContext> context = ArgumentCaptor.forClass(SignContext.class);
        verify(ai).readSign(image.capture(), context.capture());
        assertThat(context.getValue()).isEqualTo(new SignContext("강남", "2호선", DirectionCode.INNER, "사당 방면"));
        assertThat(image.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(response.result()).isEqualTo(SignResult.WRONG);
        assertThat(response.reason()).isEqualTo(SignReason.DIRECTION_MISMATCH);
        assertThat(response.recognized()).isEqualTo(new SignCheckResponse.Place("강남", "2호선", "성수 방면"));
        assertThat(response.expected()).isEqualTo(new SignCheckResponse.Place("강남", "2호선", "사당 방면"));
        assertThat(response.message()).isEqualTo("반대 방향입니다. 맞은편 승강장으로 이동하세요.");
    }

    @Test
    void missingDirectionIsPassedAsNullSoHeadsignDecides() {
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.CORRECT, null, "강남", "2호선", "사당 방면"));

        SignCheckResponse response = service.check(PNG, route(null));

        ArgumentCaptor<SignContext> context = ArgumentCaptor.forClass(SignContext.class);
        ArgumentCaptor<SignImage> image = ArgumentCaptor.forClass(SignImage.class);
        verify(ai).readSign(image.capture(), context.capture());
        assertThat(context.getValue().direction()).isNull();
        assertThat(image.getValue().contentType()).isEqualTo("image/png");
        assertThat(response.result()).isEqualTo(SignResult.CORRECT);
        assertThat(response.reason()).isNull();
        assertThat(response.message()).isEqualTo("올바른 방향입니다.");
    }

    @Test
    void everyReasonHasItsOwnMessageAndRetakeKeepsWhatWasRead() {
        for (SignReason reason : SignReason.values()) {
            when(ai.readSign(any(), any())).thenReturn(new SignReading(reason.result(), reason, null, null, null));
            assertThat(service.check(JPEG, route("LOOP")).message()).as(reason.name()).isNotBlank();
        }
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.RETAKE, SignReason.MISSING_INFO, "강남", null, null));
        SignCheckResponse response = service.check(JPEG, route(null));
        assertThat(response.recognized()).isEqualTo(new SignCheckResponse.Place("강남", null, null));
        assertThat(response.expected().headsign()).isEqualTo("사당 방면");
    }

    @Test
    void invalidImageIsRejectedBeforeAnyLookupOrAiCall() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a'};
        byte[] tooBig = SignTestImages.padded(JPEG, SignCheckService.MAX_IMAGE_BYTES + 1);
        for (byte[] bad : new byte[][] {null, new byte[0], gif, new byte[] {(byte) 0xFF, (byte) 0xD8}, tooBig}) {
            assertThatThrownBy(() -> service.check(bad, route(null)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IMAGE_INVALID));
        }
        verifyNoInteractions(ai, stations, lines);
        byte[] exactlyFive = SignTestImages.padded(PNG, SignCheckService.MAX_IMAGE_BYTES);
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.CORRECT, null, null, null, null));
        assertThat(service.check(exactlyFive, route(null)).result()).isEqualTo(SignResult.CORRECT);
    }

    @Test
    void realJpegAndPngReachTheAi() {
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.CORRECT, null, null, null, null));
        assertThat(service.check(JPEG, route(null)).result()).isEqualTo(SignResult.CORRECT);
        assertThat(service.check(PNG, route(null)).result()).isEqualTo(SignResult.CORRECT);
        verify(ai, times(2)).readSign(any(), any());
    }

    @Test
    void headerOnlyTruncatedAndCorruptFilesAreImageInvalidAndTheAiIsNeverCalled() {
        for (byte[] bad : new byte[][] {
                SignTestImages.jpegHeaderOnly(), SignTestImages.pngHeaderOnly(),
                SignTestImages.truncated(JPEG), SignTestImages.truncated(PNG),
                SignTestImages.corrupt(JPEG), SignTestImages.corrupt(PNG)}) {
            assertThatThrownBy(() -> service.check(bad, route(null)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IMAGE_INVALID));
        }
        verifyNoInteractions(ai);
    }

    @Test
    void decodingNeverTouchesTheImageIoDiskCache() throws Exception {
        // ImageIO would put a plain InputStream into a cache file when the cache is on. Point that directory at a place
        // that no longer exists: if decoding used it, the real images below would fail instead of reaching the AI.
        boolean cacheBefore = javax.imageio.ImageIO.getUseCache();
        java.io.File dirBefore = javax.imageio.ImageIO.getCacheDirectory();
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.CORRECT, null, null, null, null));
        javax.imageio.ImageIO.setUseCache(true);
        java.nio.file.Path gone = java.nio.file.Files.createTempDirectory("imageio-cache-test");
        javax.imageio.ImageIO.setCacheDirectory(gone.toFile());
        java.nio.file.Files.delete(gone);
        try {
            assertThat(service.check(JPEG, route(null)).result()).isEqualTo(SignResult.CORRECT);
            assertThat(service.check(PNG, route(null)).result()).isEqualTo(SignResult.CORRECT);
        } finally {
            javax.imageio.ImageIO.setUseCache(cacheBefore);
            javax.imageio.ImageIO.setCacheDirectory(dirBefore);
        }
    }

    @Test
    void routeProblemsAreValidationErrorsAndUnknownLineIsToo() {
        for (SignRouteRequest bad : new SignRouteRequest[] {
                new SignRouteRequest(null, 2L, null, "사당 방면"), new SignRouteRequest(105L, 0L, null, "사당 방면"),
                new SignRouteRequest(105L, 2L, "SIDEWAYS", "사당 방면"), new SignRouteRequest(105L, 2L, null, " ")}) {
            assertThatThrownBy(() -> service.check(JPEG, bad))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
        when(lines.name(99)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.check(JPEG, new SignRouteRequest(105L, 99L, null, "사당 방면")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getDetails()).containsKey("lineId");
                });
        verifyNoInteractions(ai);
    }

    @Test
    void unknownStationIsStationNotFound() {
        when(stations.require(999)).thenThrow(new BusinessException(ErrorCode.STATION_NOT_FOUND));
        assertThatThrownBy(() -> service.check(JPEG, new SignRouteRequest(999L, 2L, null, "사당 방면")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STATION_NOT_FOUND));
        verifyNoInteractions(ai);
    }

    @Test
    void aiFailuresPassThroughUnchanged() {
        when(ai.readSign(any(), any())).thenThrow(new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED));
        assertThatThrownBy(() -> service.check(JPEG, route(null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED));
    }
}
