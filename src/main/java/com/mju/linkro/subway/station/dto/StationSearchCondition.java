package com.mju.linkro.subway.station.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import java.util.Map;

/**
 * Validated query of GET /stations. Invalid values are VALIDATION_ERROR (400); nothing is clamped.
 *
 * @param keyword        trimmed keyword, or null when absent or blank
 * @param keywordStem    keyword without a trailing "역" (so "강남역" finds 강남), or null when it has none
 */
public record StationSearchCondition(String keyword, String keywordStem, Long lineId, Bbox bbox, int page, int size) {
    static final int KEYWORD_MAX_LENGTH = 50;
    static final int DEFAULT_SIZE = 20;
    static final int KEYWORD_MAX_SIZE = 50;
    static final int BBOX_MAX_SIZE = 500;

    /** WGS84 range "minLng,minLat,maxLng,maxLat". */
    public record Bbox(double minLng, double minLat, double maxLng, double maxLat) {
        static Bbox parse(String raw) {
            String[] parts = raw.split(",", -1);
            if (parts.length != 4) {
                throw invalid("bbox", "bbox는 minLng,minLat,maxLng,maxLat 숫자 4개여야 합니다.");
            }
            double[] values = new double[4];
            for (int i = 0; i < 4; i++) {
                try {
                    values[i] = Double.parseDouble(parts[i].strip());
                } catch (NumberFormatException exception) {
                    throw invalid("bbox", "bbox는 minLng,minLat,maxLng,maxLat 숫자 4개여야 합니다.");
                }
                if (!Double.isFinite(values[i])) {
                    throw invalid("bbox", "bbox는 minLng,minLat,maxLng,maxLat 숫자 4개여야 합니다.");
                }
            }
            Bbox bbox = new Bbox(values[0], values[1], values[2], values[3]);
            if (!inRange(bbox.minLng, 180) || !inRange(bbox.maxLng, 180) || !inRange(bbox.minLat, 90) || !inRange(bbox.maxLat, 90)) {
                throw invalid("bbox", "bbox 경도는 -180~180, 위도는 -90~90이어야 합니다.");
            }
            if (bbox.minLng >= bbox.maxLng || bbox.minLat >= bbox.maxLat) {
                throw invalid("bbox", "bbox의 min 값은 max 값보다 작아야 합니다.");
            }
            return bbox;
        }

        private static boolean inRange(double value, double limit) {
            return value >= -limit && value <= limit;
        }
    }

    public static StationSearchCondition of(String keyword, Long lineId, String bbox, Integer page, Integer size) {
        String normalized = normalizeKeyword(keyword);
        Bbox box = bbox == null || bbox.isBlank() ? null : Bbox.parse(bbox);
        if (normalized == null && box == null) {
            throw invalid("keyword", "keyword 또는 bbox 중 하나 이상이 필요합니다.");
        }
        int pageValue = page == null ? 0 : page;
        if (pageValue < 0) {
            throw invalid("page", "page는 0 이상이어야 합니다.");
        }
        int maxSize = box != null ? BBOX_MAX_SIZE : KEYWORD_MAX_SIZE;
        int sizeValue = size == null ? DEFAULT_SIZE : size;
        if (sizeValue < 1 || sizeValue > maxSize) {
            throw invalid("size", "size는 1~" + maxSize + "이어야 합니다.");
        }
        return new StationSearchCondition(normalized, stem(normalized), lineId, box, pageValue, sizeValue);
    }

    /** Offset as long so that large page numbers cannot overflow. */
    public long offset() {
        return (long) page * size;
    }

    static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String stripped = keyword.strip();
        if (stripped.codePointCount(0, stripped.length()) > KEYWORD_MAX_LENGTH) {
            throw invalid("keyword", "keyword는 " + KEYWORD_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return stripped;
    }

    /** "강남역" -> "강남". A lone "역" has no stem. */
    static String stem(String keyword) {
        if (keyword == null || !keyword.endsWith("역") || keyword.length() == 1) {
            return null;
        }
        return keyword.substring(0, keyword.length() - 1);
    }

    /** Escapes LIKE wildcards for {@code ... ilike :pattern escape '\'}. */
    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of(field, message));
    }
}
