package com.mju.linkro.ai.api;

/** API spec 8.1 {@code filter.memberLimits} item before mapping: the AI returns the nickname, #74 maps it to userId. */
public record MemberLimit(String nickname, Integer maxMinutes) { }
