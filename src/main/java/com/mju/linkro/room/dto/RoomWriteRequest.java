package com.mju.linkro.room.dto;

import com.mju.linkro.common.exception.*;
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
        String value = name.asString();
        // PostgreSQL btrim removes only U+0020, not tabs or Unicode whitespace.
        int start = 0, end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        value = value.substring(start, end);
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 30 || value.indexOf('\0') >= 0) throw invalid("name");
        return value;
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
