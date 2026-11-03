package com.mju.linkro.subway.station.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StationSearchConditionTest {

    @Test
    void keywordIsTrimmedAndBlankMeansAbsent() {
        assertThat(StationSearchCondition.of("  강남 ", null, null, null, null).keyword()).isEqualTo("강남");
        assertThat(StationSearchCondition.normalizeKeyword("   ")).isNull();
        assertThat(StationSearchCondition.normalizeKeyword("")).isNull();
        assertThat(StationSearchCondition.normalizeKeyword(null)).isNull();
        assertError(() -> StationSearchCondition.of("   ", null, null, null, null), "keyword");
    }

    @Test
    void keywordLimitIsFiftyCodePoints() {
        assertThat(StationSearchCondition.normalizeKeyword("가".repeat(50))).hasSize(50);
        assertThat(StationSearchCondition.normalizeKeyword("😀".repeat(50))).isNotNull();
        assertError(() -> StationSearchCondition.of("가".repeat(51), null, null, null, null), "keyword");
    }

    @ParameterizedTest
    @CsvSource(value = {"강남역,강남", "서울역,서울", "역,NULL", "강남,NULL", "역삼,NULL"}, nullValues = "NULL")
    void trailingStationSuffixGivesAStem(String keyword, String stem) {
        assertThat(StationSearchCondition.stem(keyword)).isEqualTo(stem);
        assertThat(StationSearchCondition.of(keyword, null, null, null, null).keywordStem()).isEqualTo(stem);
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(StationSearchCondition.escapeLike("50%_\\x")).isEqualTo("50\\%\\_\\\\x");
        assertThat(StationSearchCondition.escapeLike("강남")).isEqualTo("강남");
    }

    @Test
    void bboxIsParsedInLngLatOrder() {
        var bbox = StationSearchCondition.of(null, null, "126.9, 37.4,127.1,37.6", null, null).bbox();
        assertThat(bbox).isEqualTo(new StationSearchCondition.Bbox(126.9, 37.4, 127.1, 37.6));
        assertThat(StationSearchCondition.of(null, null, "-180,-90,180,90", null, null).bbox()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"126.9,37.4,127.1", "126.9,37.4,127.1,37.6,1", "a,37.4,127.1,37.6", "126.9,,127.1,37.6",
        "NaN,37.4,127.1,37.6", "126.9,37.4,Infinity,37.6", "-180.1,37.4,127.1,37.6", "126.9,-90.1,127.1,37.6",
        "126.9,37.4,180.1,37.6", "126.9,37.4,127.1,90.1", "127.1,37.4,126.9,37.6", "126.9,37.6,127.1,37.4",
        "127,37.4,127,37.6"})
    void invalidBboxIsValidationError(String bbox) {
        assertError(() -> StationSearchCondition.of(null, null, bbox, null, null), "bbox");
        assertError(() -> StationSearchCondition.of("강남", null, bbox, null, null), "bbox");
    }

    @Test
    void keywordOrBboxIsRequired() {
        assertError(() -> StationSearchCondition.of(null, 2L, null, 0, 20), "keyword");
        assertError(() -> StationSearchCondition.of(null, null, " ", 0, 20), "keyword");
    }

    @Test
    void sizeLimitDependsOnBboxAndIsNotClamped() {
        assertThat(StationSearchCondition.of("강남", null, null, null, null)).extracting("page", "size").containsExactly(0, 20);
        assertThat(StationSearchCondition.of("강남", null, null, 0, 50).size()).isEqualTo(50);
        assertError(() -> StationSearchCondition.of("강남", null, null, 0, 51), "size");
        String bbox = "126.9,37.4,127.1,37.6";
        assertThat(StationSearchCondition.of(null, null, bbox, 0, 500).size()).isEqualTo(500);
        assertThat(StationSearchCondition.of("강남", null, bbox, 0, 500).size()).isEqualTo(500);
        assertError(() -> StationSearchCondition.of(null, null, bbox, 0, 501), "size");
        assertError(() -> StationSearchCondition.of("강남", null, null, 0, 0), "size");
        assertError(() -> StationSearchCondition.of("강남", null, null, -1, 20), "page");
    }

    @Test
    void offsetDoesNotOverflow() {
        assertThat(StationSearchCondition.of("강남", null, null, Integer.MAX_VALUE, 50).offset())
                .isEqualTo((long) Integer.MAX_VALUE * 50);
    }

    private static void assertError(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(exception.getDetails()).containsOnlyKeys(field);
        });
    }
}
