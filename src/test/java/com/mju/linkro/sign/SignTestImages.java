package com.mju.linkro.sign;

import java.util.Arrays;
import java.util.Base64;

/** Real 8x8 JPG/PNG files (made with ImageIO) and broken variants of them for the sign tests. */
public final class SignTestImages {
    private static final String JPG = "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDK0TwT9391+lFFFcOJzCv7R6muSZpifqkfeP/Z";
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAbElEQVR4XhXNQRUAUQhCUZOYhCQmeUlIQhISzR+XXA7ODDtouIHBQ4YOM8suWm5h8ZKl+0CskDiBsIioHhx76LiDw0eO3oN/4FVf+J8h0PduzBqZ8x/bxNQPwgaFy192SGgelC0q13/CJaXlA+odUoED3fDeAAAAAElFTkSuQmCC";

    private SignTestImages() {}

    public static byte[] jpeg() { return Base64.getDecoder().decode(JPG); }

    public static byte[] png() { return Base64.getDecoder().decode(PNG); }

    /** Only the magic bytes: passes a header check, is not an image. */
    public static byte[] jpegHeaderOnly() { return Arrays.copyOf(jpeg(), 4); }

    public static byte[] pngHeaderOnly() { return Arrays.copyOf(png(), 8); }

    /** The first half of a real file: valid start, missing the rest. */
    public static byte[] truncated(byte[] image) { return Arrays.copyOf(image, image.length / 2); }

    /**
     * Real start and end with garbage over the table/chunk bytes just after the signature. Damage inside a JPEG's
     * compressed scan data is not used: decoders accept it and just produce wrong pixels.
     */
    public static byte[] corrupt(byte[] image) {
        byte[] copy = image.clone();
        for (int i = 20; i < 40; i++) copy[i] = (byte) 0xA5;
        return copy;
    }

    /** A real image followed by padding up to {@code size} bytes (decoders ignore trailing bytes). */
    public static byte[] padded(byte[] image, int size) { return Arrays.copyOf(image, size); }
}
