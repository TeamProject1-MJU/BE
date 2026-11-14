package com.mju.linkro.sign.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.SignContext;
import com.mju.linkro.ai.api.SignImage;
import com.mju.linkro.ai.api.SignReading;
import com.mju.linkro.ai.api.SignReason;
import com.mju.linkro.ai.api.SignResult;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.sign.dto.SignCheckResponse;
import com.mju.linkro.sign.dto.SignCheckResponse.Place;
import com.mju.linkro.sign.dto.SignRouteRequest;
import com.mju.linkro.subway.api.LineLookup;
import com.mju.linkro.subway.api.StationLookup;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * POST /ai/direction-check (9.1). Images stay in memory. Direction-only requests require a real boarding edge
 * and explicit direction evidence; terminal names alone cannot prove a direction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignCheckService {
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    /** A small file can still declare a huge canvas; decoding that would exhaust memory. */
    static final long MAX_IMAGE_PIXELS = 50_000_000L;

    private final AiClient ai;
    private final StationLookup stations;
    private final LineLookup lines;
    private final SignRouteLookup routes;

    public SignCheckResponse check(byte[] image, SignRouteRequest route) {
        SignImage signImage = new SignImage(image, contentType(image));
        requireDecodable(image);
        SignRouteRequest.Valid request = route.validate();
        if (request.headsign() == null) routes.requireDirection(request.stationId(), request.lineId(), request.direction());
        String stationName = stations.require(request.stationId()).name();
        String lineName = lines.name(request.lineId()).orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of("lineId", "존재하지 않는 노선입니다.")));
        SignContext context = new SignContext(stationName, lineName, request.direction(), request.headsign());

        long started = System.nanoTime();
        SignReading reading = ai.readSign(signImage, context);
        if (context.directionOnly()) reading = checkDirectionEvidence(reading, request.direction());
        log.info("Sign check: result={} reason={} ai={}ms", reading.result(), reading.reason(), (System.nanoTime() - started) / 1_000_000);

        return new SignCheckResponse(reading.result(), reading.reason(),
                new Place(reading.stationName(), reading.lineName(), reading.headsign()),
                new Place(stationName, lineName, request.headsign()), message(reading));
    }

    /** Never accept the model's geographic inference from a terminal name as direction evidence. */
    private static SignReading checkDirectionEvidence(SignReading reading, DirectionCode expected) {
        if (reading.result() == SignResult.RETAKE || reading.reason() == SignReason.LINE_NOT_ON_ROUTE) return reading;
        String text = reading.headsign() == null ? "" : reading.headsign().replaceAll("\\s+", "");
        DirectionCode observed = switch (text) {
            case "UP", "상행" -> DirectionCode.UP;
            case "DOWN", "하행" -> DirectionCode.DOWN;
            case "INNER", "내선", "내선순환" -> DirectionCode.INNER;
            case "OUTER", "외선", "외선순환" -> DirectionCode.OUTER;
            case "LOOP", "응암순환" -> DirectionCode.LOOP;
            default -> null;
        };
        if (observed == null || reading.stationName() == null || reading.stationName().isBlank()
                || reading.lineName() == null || reading.lineName().isBlank()
                || (observed == expected && reading.result() != SignResult.CORRECT)) {
            return new SignReading(SignResult.RETAKE, SignReason.MISSING_INFO,
                    reading.stationName(), reading.lineName(), reading.headsign());
        }
        // Codes from another family (e.g. UP vs INNER) do not establish an opposite direction.
        boolean comparable = observed == expected
                || (observed == DirectionCode.UP && expected == DirectionCode.DOWN)
                || (observed == DirectionCode.DOWN && expected == DirectionCode.UP)
                || (observed == DirectionCode.INNER && expected == DirectionCode.OUTER)
                || (observed == DirectionCode.OUTER && expected == DirectionCode.INNER);
        if (!comparable) return new SignReading(SignResult.RETAKE, SignReason.MISSING_INFO,
                reading.stationName(), reading.lineName(), reading.headsign());
        return new SignReading(observed == expected ? SignResult.CORRECT : SignResult.WRONG,
                observed == expected ? null : SignReason.DIRECTION_MISMATCH,
                reading.stationName(), reading.lineName(), reading.headsign());
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

    /**
     * The first bytes alone pass a corrupt file, which the AI call would then turn into AI_UNAVAILABLE. Decode it fully,
     * in memory (MemoryCacheImageInputStream, so ImageIO never writes a cache file), and treat decoder warnings such as
     * a truncated JPEG as corrupt.
     */
    static void requireDecodable(byte[] image) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException(ErrorCode.IMAGE_INVALID);
            ImageReader reader = readers.next();
            try {
                boolean[] warned = {false};
                IIOReadWarningListener listener = (source, warning) -> warned[0] = true;
                reader.addIIOReadWarningListener(listener);
                reader.setInput(input, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_IMAGE_PIXELS) {
                    throw new BusinessException(ErrorCode.IMAGE_INVALID);
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || warned[0]) throw new BusinessException(ErrorCode.IMAGE_INVALID);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BusinessException business) throw business;
            throw new BusinessException(ErrorCode.IMAGE_INVALID);
        }
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
            case MISSING_INFO -> "경로와 표지판의 방향을 비교할 정보가 부족합니다. 방향 표시가 보이게 다시 촬영해 주세요.";
        };
    }
}
