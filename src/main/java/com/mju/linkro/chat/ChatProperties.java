package com.mju.linkro.chat;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code linkro.chat.*}. The 1000-character message limit is a spec constant (API 10.1), not a setting. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "linkro.chat")
public class ChatProperties {
    /** How many earlier USER/AI messages of the room go to the AI with an @링크로 question. */
    @Min(0)
    private int aiContextMessages = 10;
    /** Threads answering @링크로 questions at the same time. */
    @Min(1)
    private int aiThreads = 4;
    /** Questions waiting for a free thread; beyond this the asker gets the "AI unavailable" SYSTEM message. */
    @Min(0)
    private int aiQueueCapacity = 50;
}
