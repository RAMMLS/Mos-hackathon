package ru.moshackathon.heatnetwork.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebAsyncConfiguration implements WebMvcConfigurer {
    private final ThreadPoolTaskExecutor streamingExecutor;

    public WebAsyncConfiguration() {
        streamingExecutor = new ThreadPoolTaskExecutor();
        streamingExecutor.setCorePoolSize(10);
        streamingExecutor.setMaxPoolSize(50);
        streamingExecutor.setQueueCapacity(100);
        streamingExecutor.setThreadNamePrefix("geojson-stream-");
        streamingExecutor.setWaitForTasksToCompleteOnShutdown(true);
        streamingExecutor.setAwaitTerminationSeconds(30);
        streamingExecutor.initialize();
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor streamingTaskExecutor() {
        return streamingExecutor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(streamingExecutor);
        configurer.setDefaultTimeout(3_600_000L);
    }
}
