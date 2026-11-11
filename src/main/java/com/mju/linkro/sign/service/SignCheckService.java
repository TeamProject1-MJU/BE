package com.mju.linkro.sign.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.SignContext;
import com.mju.linkro.ai.api.SignImage;
import com.mju.linkro.ai.api.SignReading;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.sign.dto.SignCheckResponse;
import com.mju.linkro.sign.dto.SignCheckResponse.Place;
import com.mju.linkro.sign.dto.SignRouteRequest;
import com.mju.linkro.subway.api.LineLookup;
import com.mju.linkro.subway.api.StationLookup;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * POST /ai/direction-check (9.1). The image is only held in memory. The AI's verdict is returned as-is; our DB is
 * used only to turn stationId/lineId into names for the AI and for {@code expected}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignCheckService {
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    private final AiClient ai;
    private final StationLookup stations;
    private final LineLookup lines;

    public SignCheckResponse check(byte[] image, SignRouteRequest route) {
        SignImage signImage = new SignImage(image, contentType(image));
        SignRouteRequest.Valid request = route.validate();
        String stationName = stations.require(request.stationId()).name();
        String lineName = lines.name(request.lineId()).orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("lineId", "존재하지 않는 노선입니다.")));
        SignContext context = new SignContext(stationName, lineName, request.direction(), request.headsign());

        long started = System.nanoTime();
        SignReading reading = ai.readSign(signImage, context);
        log.info("Sign check: result={} reason={} ai={}ms", reading.result(), reading.reason(), (System.nanoTime() - started) / 1_000_000);

        return new SignCheckResponse(reading.result(), reading.reason(),
                new Place(reading.stationName(), reading.lineName(), reading.headsign()),
                new Place(stationName, lineName, request.headsign()), message(reading));
    }

    /** JPG/PNG up to 5MB, decided by the file's first bytes (the client's Content-Type is not trusted). */
    static String contentType(byte[] image) {
        if (image == null || image.length == 0 || image.length > MAX_IMAGE_BYTES) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID);
        }
        if (startsWith(image, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (startsWith(image, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        throw new BusinessException(ErrorCode.IMAGE_INVALID);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private static String message(SignReading reading) {
        if (reading.reason() == null) return "올바른 방향입니다.";
        return switch (reading.reason()) {
            case DIRECTION_MISMATCH -> "반대 방향입니다. 맞은편 승강장으로 이동하세요.";
            case LINE_NOT_ON_ROUTE -> "이동 경로에 없는 노선입니다. 이용할 노선의 승강장으로 이동하세요.";
            case NOT_A_SIGN -> "표지판이 아닌 것 같습니다. 승강장 방면 표지판이 보이게 다시 촬영해 주세요.";
            case UNREADABLE -> "표지판을 읽을 수 없습니다. 가까이에서 선명하게 다시 촬영해 주세요.";
            case MISSING_INFO -> "역명·노선·방면이 모두 보이지 않습니다. 표지판 전체가 나오게 다시 촬영해 주세요.";
        };
    }
}
