package com.mju.linkro.chat.service;

import java.util.regex.Pattern;

/**
 * The {@code @linkro} mention that calls the AI (API 10.1). Case-insensitive. It may be followed by a Korean particle
 * ("@linkro가", "@linkro야") or punctuation, but not by more word characters ("@linkrobot") and not preceded by one
 * ("me@linkro.com").
 */
final class LinkroMention {
    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_])@linkro(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);

    private LinkroMention() {}

    static boolean in(String content) {
        return MENTION.matcher(content).find();
    }

    /** The question without the mention. A message that is only the mention is asked as written. */
    static String question(String content) {
        String question = MENTION.matcher(content).replaceAll(" ").strip().replaceAll("\\s{2,}", " ");
        return question.isEmpty() ? content.strip() : question;
    }
}
