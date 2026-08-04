package com.devmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class TaskExecutorConfig {

    @Bean(name = "documentTaskExecutor")
    public ThreadPoolTaskExecutor documentTaskExecutor(DevMindProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.taskThreadPoolSize());
        executor.setMaxPoolSize(properties.taskThreadPoolSize());
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("document-task-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "documentTaskScheduler")
    public ThreadPoolTaskScheduler documentTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("document-task-scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
