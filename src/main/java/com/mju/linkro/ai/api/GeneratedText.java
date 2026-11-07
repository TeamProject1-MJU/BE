package com.mju.linkro.ai.api;

import java.util.Objects;

public record GeneratedText(String text) {
    public GeneratedText {
        Objects.requireNonNull(text, "text");
    }
}
