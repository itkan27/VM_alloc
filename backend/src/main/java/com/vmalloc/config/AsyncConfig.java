package com.vmalloc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Background execution for live simulations, plus scheduling for SSE heartbeats
 * and session cleanup.
 */
@Configuration
@EnableScheduling
public class AsyncConfig {

    /** Runs live simulations; each occupies one thread for its whole (paced) duration */
    @Bean
    public ThreadPoolTaskExecutor simExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("sim-");
        executor.initialize();
        return executor;
    }
}
