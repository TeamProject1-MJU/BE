package com.mju.linkro.chat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    /** Runs the slow AI call off the request thread. Bounded: a full queue rejects instead of piling up. */
    @Bean(destroyMethod = "shutdown")
    public Executor chatAiExecutor(ChatProperties properties) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "chat-ai-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // A bounded queue only feeds the pool beyond core size, so core = max.
        ThreadPoolExecutor executor = new ThreadPoolExecutor(properties.getAiThreads(), properties.getAiThreads(),
                60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(Math.max(1, properties.getAiQueueCapacity())), threads);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
