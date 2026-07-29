package com.gtd.platform.service.imports;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The background job pool for AWS-IMPORT-001's "session-independent
 * background job." A single dedicated thread: the advisory lock
 * ({@link ImportAdvisoryLockService}) is the authoritative, cross-instance
 * guard against concurrent imports, but there is no reason to allow more
 * than one import to even be queued locally either.
 */
@Configuration
@EnableAsync
public class ImportAsyncConfig {

    public static final String IMPORT_EXECUTOR_BEAN = "importTaskExecutor";

    @Bean(name = IMPORT_EXECUTOR_BEAN)
    public Executor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("import-job-");
        executor.initialize();
        return executor;
    }
}
