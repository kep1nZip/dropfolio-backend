package com.dropfolio.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated executor for admin-triggered {@code PriceSyncJob} runs — M7 Implementation
 * Authorization §14: "Scheduler execution must not block the HTTP request thread pool."
 *
 * Core/max pool size of 1 is intentional, not a placeholder: the distributed lock
 * ({@code lock:scheduler:price-sync}) already guarantees at most one {@code PriceSyncJob}
 * execution cluster-wide at a time, so a larger pool on a single instance would only let a
 * second admin-triggered call queue up locally without ever actually running concurrently
 * with the first (it would just fail the lock acquisition once it started). A small bounded
 * queue exists only to hold, briefly, a call that arrives the instant the previous run's
 * {@code @Async} method returns but before the executor thread is available again.
 */
@Configuration
@EnableAsync
public class SchedulerAsyncConfig {

    public static final String PRICE_SYNC_EXECUTOR = "priceSyncExecutor";

    @Bean(name = PRICE_SYNC_EXECUTOR)
    public TaskExecutor priceSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("price-sync-");
        executor.initialize();
        return executor;
    }
}
