package com.mju.linkro.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

class ChatAiShutdownTest {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(5));

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void stopWaitsForRunningAndQueuedAnswersThenRefusesNewOnes() throws Exception {
        var shutdown = new ChatAiShutdown(executor, Duration.ofSeconds(5));
        shutdown.start();
        CountDownLatch started = new CountDownLatch(2);
        AtomicInteger finished = new AtomicInteger();
        for (int i = 0; i < 3; i++) {   // two run, one waits in the queue
            executor.execute(() -> {
                started.countDown();
                sleep(300);
                finished.incrementAndGet();
            });
        }
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        shutdown.stop();

        assertThat(finished.get()).isEqualTo(3);
        assertThat(shutdown.isRunning()).isFalse();
        assertThat(executor.isTerminated()).isTrue();
        assertThatThrownBy(() -> executor.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void newJobsAreRefusedWhileTheRunningOnesStillFinish() throws Exception {
        var shutdown = new ChatAiShutdown(executor, Duration.ofSeconds(5));
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        executor.execute(() -> {
            await(release);
            done.set(true);
        });
        Thread stopping = new Thread(shutdown::stop);
        stopping.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!executor.isShutdown() && System.nanoTime() < deadline) sleep(5);

        assertThatThrownBy(() -> executor.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
        assertThat(done.get()).isFalse();
        release.countDown();
        stopping.join(2000);

        assertThat(done.get()).isTrue();
        assertThat(stopping.isAlive()).isFalse();
    }

    @Test
    void anAnswerThatTakesLongerThanTheWaitIsCutOff() {
        var shutdown = new ChatAiShutdown(executor, Duration.ofMillis(200));
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        await(started);

        long begin = System.nanoTime();
        shutdown.stop();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertThat(tookMillis).isBetween(150L, 3000L);
        awaitTrue(interrupted);
    }

    @Test
    void itStopsAfterTheWebServerDrainsAndBeforeTheBeansGoAway() {
        int phase = new ChatAiShutdown(executor, Duration.ofSeconds(1)).getPhase();

        // Stop order is highest phase first: the web server's graceful phase (MAX - 1024), then the server itself
        // (MAX - 2048, WebServerStartStopLifecycle), then this. Beans are destroyed only after every phase.
        assertThat(phase).isLessThan(2147481599);
        assertThat(phase).isLessThan(SmartLifecycle.DEFAULT_PHASE).isGreaterThan(0);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void awaitTrue(AtomicBoolean flag) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!flag.get() && System.nanoTime() < deadline) sleep(5);
        assertThat(flag.get()).isTrue();
    }
}
