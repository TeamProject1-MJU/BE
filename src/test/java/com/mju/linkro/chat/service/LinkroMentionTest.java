package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class LinkroMentionTest {
    @ParameterizedTest
    @ValueSource(strings = {"@linkro 사당역에서 갈만한 곳 알려줘", "사당역 맛집 @linkro", "@linkro가 알려줘", "@LinkRo 안녕", "얘들아 @linkro, 도와줘", "a\n@linkro\nb"})
    void aMentionCallsTheAi(String content) {
        assertThat(LinkroMention.in(content)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"링크로 알려줘", "linkro 알려줘", "@linkrobot 안녕", "me@linkro.com 으로 보내줘", "@link ro", "@ linkro"})
    void otherTextDoesNot(String content) {
        assertThat(LinkroMention.in(content)).isFalse();
    }

    @Test
    void theQuestionDropsTheMention() {
        assertThat(LinkroMention.question("@linkro 사당역에서 갈만한 곳 알려줘")).isEqualTo("사당역에서 갈만한 곳 알려줘");
        assertThat(LinkroMention.question("사당역 맛집 @linkro 알려줘")).isEqualTo("사당역 맛집 알려줘");
    }

    @Test
    void aMessageThatIsOnlyTheMentionIsAskedAsWritten() {
        assertThat(LinkroMention.question("  @linkro ")).isEqualTo("@linkro");
    }
}
