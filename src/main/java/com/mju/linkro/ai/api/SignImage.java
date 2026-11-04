package com.mju.linkro.ai.api;

import java.util.Objects;

/** In-memory only. Validation (type, size) is done by the #75 controller before calling the AI. */
public record SignImage(byte[] bytes, String contentType) {
    public SignImage {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(contentType, "contentType");
        if (bytes.length == 0) throw new IllegalArgumentException("bytes must not be empty");
    }

    @Override
    public String toString() {
        return "SignImage[contentType=" + contentType + ", size=" + bytes.length + "]";
    }
}
