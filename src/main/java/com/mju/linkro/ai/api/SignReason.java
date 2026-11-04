package com.mju.linkro.ai.api;

/** API spec 9.1 {@code reason}: WRONG uses the first two, RETAKE the last three, CORRECT none. */
public enum SignReason {
    LINE_NOT_ON_ROUTE(SignResult.WRONG), DIRECTION_MISMATCH(SignResult.WRONG),
    NOT_A_SIGN(SignResult.RETAKE), UNREADABLE(SignResult.RETAKE), MISSING_INFO(SignResult.RETAKE);

    private final SignResult result;

    SignReason(SignResult result) {
        this.result = result;
    }

    public SignResult result() {
        return result;
    }
}
