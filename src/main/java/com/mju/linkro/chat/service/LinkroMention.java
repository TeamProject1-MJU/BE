package com.mju.linkro.chat.service;

import java.util.regex.Pattern;

/**
 * The {@code @링크로} mention that calls the AI (API 10.1). Anything may follow it, so Korean particles work
 * ("@링크로야", "@링크로가"), but it must not be preceded by a letter or digit ("me@링크로").
 */
final class LinkroMention {
    private static final Pattern MENTION = Pattern.compile("(?<![\\p{L}\\p{N}_])@링크로");

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
