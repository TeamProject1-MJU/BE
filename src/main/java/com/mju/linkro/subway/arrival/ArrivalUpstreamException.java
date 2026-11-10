package com.mju.linkro.subway.arrival;

/**
 * A failed arrival API call. The message is a short reason (result code or exception type) and never the URL,
 * which carries the API key; the original exception is not kept as the cause for the same reason.
 */
public class ArrivalUpstreamException extends RuntimeException {

    public ArrivalUpstreamException(String reason) {
        super("Seoul arrival request failed: " + reason);
    }

    /** Keeps the stack location of the original failure without its message or cause. */
    static ArrivalUpstreamException wrap(String reason, Throwable original) {
        ArrivalUpstreamException safe = new ArrivalUpstreamException(reason);
        safe.setStackTrace(original.getStackTrace());
        return safe;
    }
}
