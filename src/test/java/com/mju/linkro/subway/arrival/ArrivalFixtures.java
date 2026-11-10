package com.mju.linkro.subway.arrival;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Hand-made responses in src/test/resources/arrival (fields seen on 2026-11-10); no real response is used. */
final class ArrivalFixtures {
    /** A fake key with characters that need encoding. Never a real key. */
    static final String KEY = "FAKE+KEY/test=";
    static final String BASE = "http://swopenAPI.seoul.go.kr";
    /** 2026-11-10 08:00:00 KST. */
    static final Instant NOW = Instant.parse("2026-11-09T23:00:00Z");

    private ArrivalFixtures() {}

    static String json(String name) {
        try (InputStream in = ArrivalFixtures.class.getResourceAsStream("/arrival/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now = NOW;
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
