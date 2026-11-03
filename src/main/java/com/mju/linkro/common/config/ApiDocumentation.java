package com.mju.linkro.common.config;

import com.mju.linkro.auth.dto.*;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.room.dto.RoomResponses;
import com.mju.linkro.subway.station.dto.StationResponses;
import com.mju.linkro.user.dto.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** OpenAPI-only models: avoid losing generic data types on the sealed runtime envelope. */
public final class ApiDocumentation {
    private ApiDocumentation() {}

    @Schema(name = "RoomJoinResponse", requiredProperties = {"success", "data"})
    public record JoinResponse(@Schema(example = "true", description = "항상 true") boolean success, RoomResponses.Joined data) {}

    @Schema(name = "RoomCreateRequest", description = "정원은 4명 고정. userId·ownerId는 받지 않고 인증된 사용자를 생성자로 사용합니다.")
    public record RoomCreateRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "필수이며 null 불가. 앞뒤 U+0020 제거 후 유니코드 코드포인트 1~30자. 탭·유니코드 공백은 정규화하지 않습니다.", example = "금요일 저녁") String name,
            @Schema(types = {"string", "null"}, format = "date-time", description = "선택. 생략 또는 null이면 약속 시각 미설정. 시간대가 포함된 ISO 8601 시각.", example = "2026-11-06T19:00:00+09:00") String appointmentAt) {}

    @Schema(name = "RoomPatchRequest", description = "name·appointmentAt 모두 선택. 생략한 필드는 유지합니다. 빈 객체 {}도 허용합니다. 추천·후보·투표·확정역은 보존하며 자동 재추천하지 않습니다.")
    public record RoomPatchRequest(
            @Schema(description = "생략하면 유지. 명시적 null은 VALIDATION_ERROR. 앞뒤 U+0020 제거 후 유니코드 코드포인트 1~30자.", example = "토요일 점심") String name,
            @Schema(types = {"string", "null"}, format = "date-time", description = "생략하면 유지. 명시적 null은 약속 시각 해제. 시간대가 포함된 ISO 8601 시각.", example = "2026-11-07T12:00:00+09:00") String appointmentAt) {}

    @Schema(name = "RoomCreateResponse", requiredProperties = {"success", "data"})
    public record RoomCreated(@Schema(description = "항상 true", example = "true") boolean success, RoomResponses.Created data) {}
    @Schema(name = "RoomListResponse", requiredProperties = {"success", "data"})
    public record RoomList(@Schema(description = "항상 true", example = "true") boolean success, List<RoomResponses.Summary> data) {}
    @Schema(name = "RoomDetailResponse", requiredProperties = {"success", "data"})
    public record RoomDetail(@Schema(description = "항상 true", example = "true") boolean success, RoomResponses.Detail data) {}
    @Schema(name = "RoomMembersResponse", requiredProperties = {"success", "data"})
    public record RoomMembers(@Schema(description = "항상 true", example = "true") boolean success, List<RoomResponses.Member> data) {}
    @Schema(name = "RoomInviteCodeResponse", requiredProperties = {"success", "data"})
    public record RoomInviteCode(@Schema(description = "항상 true", example = "true") boolean success, RoomResponses.InviteCode data) {}
    @Schema(name = "SubwayLineListResponse", requiredProperties = {"success", "data"})
    public record LineList(@Schema(description = "항상 true", example = "true") boolean success, List<StationResponses.Line> data) {}
    @Schema(name = "StationPageResponse", requiredProperties = {"success", "data"})
    public record StationPageResponse(@Schema(description = "항상 true", example = "true") boolean success, StationResponses.StationPage data) {}
    @Schema(name = "StationDetailResponse", requiredProperties = {"success", "data"})
    public record StationDetail(@Schema(description = "항상 true", example = "true") boolean success, StationResponses.Station data) {}
    @Schema(name = "AuthLoginResponse", requiredProperties = {"success", "data"})
    public record Login(@Schema(description = "항상 true", example = "true") boolean success, KakaoLoginResponse data) {}
    @Schema(name = "AuthTokensResponse", requiredProperties = {"success", "data"})
    public record Tokens(@Schema(description = "항상 true", example = "true") boolean success, TokenResponse data) {}
    @Schema(name = "UserProfileResponseEnvelope", requiredProperties = {"success", "data"})
    public record Profile(@Schema(description = "항상 true", example = "true") boolean success, UserProfileResponse data) {}
    @Schema(name = "UserNicknameResponseEnvelope", requiredProperties = {"success", "data"})
    public record Nickname(@Schema(description = "항상 true", example = "true") boolean success, NicknameUpdateResponse data) {}
    @Schema(name = "ApiEmptySuccessResponse", requiredProperties = {"success", "data"})
    public record EmptySuccess(@Schema(description = "항상 true", example = "true") boolean success,
                               @Schema(types = {"null"}, description = "성공 data는 항상 null입니다.") Object data) {}
    @Schema(name = "ApiFailureResponse", requiredProperties = {"success", "error"})
    public record Failure(@Schema(description = "항상 false", example = "false") boolean success, ApiResponse.Error error) {}

}
