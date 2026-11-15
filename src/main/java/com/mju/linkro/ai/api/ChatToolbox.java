package com.mju.linkro.ai.api;

import java.util.List;

/**
 * The tools of one @링크로 question (#129). The room and the asking user are fixed inside the implementation, never
 * tool arguments. The AI client offers {@link #specs()}, runs the calls the model makes and sends each result back.
 */
public interface ChatToolbox {
    List<ChatToolSpec> specs();

    /**
     * Runs one tool call and returns its JSON result ({@code {"ok":true,...}} or {@code {"ok":false,"code":"..."}}).
     * Never throws: a failing or timed out tool is a reason code, so the model can tell the user.
     */
    String run(String toolName, String argumentsJson);
}
