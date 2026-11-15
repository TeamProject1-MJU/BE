package com.mju.linkro.ai.api;

import java.util.Map;

/** One read-only tool the chat AI may call (#129): its name, when to use it, and its JSON-schema parameters. */
public record ChatToolSpec(String name, String description, Map<String, Object> parameters) {}
