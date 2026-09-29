package ru.moshackathon.heatnetwork.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class PortfolioConfiguration {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService portfolioExecutor(
            @Value("${heat-network.portfolio.parallelism:8}") int configuredParallelism) {
        int parallelism = Math.max(1, Math.min(configuredParallelism,
                Runtime.getRuntime().availableProcessors()));
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "portfolio-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(parallelism, factory);
    }
}
