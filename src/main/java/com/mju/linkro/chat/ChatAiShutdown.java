package com.mju.linkro.chat;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Lets @링크로 answers finish at shutdown. It stops during the lifecycle stop, after the web server's graceful phase
 * has finished the running requests and the server has stopped (see {@link #PHASE}), and before any bean, the
 * database included, is destroyed, so an answer still running can save its message. From then on the executor refuses new jobs and
 * ChatService answers those questions with the failure notice. Answers still not done after the wait are cut off;
 * the startup sweep (ChatAnswerSweep) covers them on the next start.
 */
@Slf4j
@RequiredArgsConstructor
class ChatAiShutdown implements SmartLifecycle {
    /**
     * Stop order is highest phase first. Spring Boot's web server stops in two steps: the graceful phase
     * (MAX - 1024) refuses new requests and waits for the running ones, then the server itself stops
     * (MAX - 2048). One lower than that, so the last request has finished and no new one can start.
     */
    static final int PHASE = Integer.MAX_VALUE - 2049;

    private final ThreadPoolExecutor executor;
    private final Duration wait;
    private volatile boolean running;

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("@링크로 answers still running after {}: cutting off {} running and {} waiting", wait,
                        executor.getActiveCount(), executor.getQueue().size());
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
