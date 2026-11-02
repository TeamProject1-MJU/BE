package com.mju.linkro.room.dto;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.RoomName;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Missing property = Java null; explicit JSON null = NullNode. No scalar coercion. */
public final class RoomWriteRequest {
    private JsonNode name;
    private JsonNode appointmentAt;
    @com.fasterxml.jackson.annotation.JsonSetter("name")
    public void setName(JsonNode value) { name = value == null ? tools.jackson.databind.node.NullNode.getInstance() : value; }
    @com.fasterxml.jackson.annotation.JsonSetter("appointmentAt")
    public void setAppointmentAt(JsonNode value) { appointmentAt = value == null ? tools.jackson.databind.node.NullNode.getInstance() : value; }
    public JsonNode name() { return name; }
    public JsonNode appointmentAt() { return appointmentAt; }
    public String validatedName(boolean required) {
        if (name == null && !required) return null;
        if (name == null || !name.isString()) throw invalid("name");
        try { return RoomName.normalize(name.asString()); }
        catch (IllegalArgumentException exception) { throw invalid("name"); }
    }

    public Instant validatedAppointmentAt() {
        if (appointmentAt == null || appointmentAt.isNull()) return null;
        if (!appointmentAt.isString()) throw invalid("appointmentAt");
        try { return Instant.parse(appointmentAt.asString()); }
        catch (DateTimeParseException exception) { throw invalid("appointmentAt"); }
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                Map.of(field, field.equals("name") ? "이름은 앞뒤 기본 공백을 제외하고 1~30자여야 합니다."
                        : "시간대가 포함된 ISO 8601 시각이어야 합니다."));
    }
}
