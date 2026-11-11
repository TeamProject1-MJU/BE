package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class LinkroMentionTest {
    @ParameterizedTest
    @ValueSource(strings = {"@링크로 사당역에서 갈만한 곳 알려줘", "사당역 맛집 @링크로", "@링크로가 알려줘", "@링크로야 안녕", "얘들아 @링크로, 도와줘", "a\n@링크로\nb"})
    void aMentionCallsTheAi(String content) {
        assertThat(LinkroMention.in(content)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"링크로 알려줘", "@linkro 알려줘", "@LinkRo 안녕", "me@링크로 으로 보내줘", "@링 크로", "@ 링크로"})
    void otherTextDoesNot(String content) {
        assertThat(LinkroMention.in(content)).isFalse();
    }

    @Test
    void theQuestionDropsTheMention() {
        assertThat(LinkroMention.question("@링크로 사당역에서 갈만한 곳 알려줘")).isEqualTo("사당역에서 갈만한 곳 알려줘");
        assertThat(LinkroMention.question("사당역 맛집 @링크로 알려줘")).isEqualTo("사당역 맛집 알려줘");
    }

    @Test
    void aMessageThatIsOnlyTheMentionIsAskedAsWritten() {
        assertThat(LinkroMention.question("  @링크로 ")).isEqualTo("@링크로");
    }
}
