package com.betteranalytics.crm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * Bounded background executor for integration work that must never block an
 * HTTP request: WhatsApp campaign dispatch and event-fired AI automations.
 *
 * Deliberately exposed as a small wrapper type rather than a raw Executor bean
 * so it does not replace Spring Boot's auto-configured applicationTaskExecutor.
 * When the queue is full, submit() throws TaskRejectedException — callers log
 * and drop (automations) or leave work pending for the scheduler (campaigns).
 */
@Configuration
public class IntegrationAsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public IntegrationExecutor integrationExecutor(
            @Value("${app.integrations.executor.core-size:2}") int coreSize,
            @Value("${app.integrations.executor.max-size:8}") int maxSize,
            @Value("${app.integrations.executor.queue-capacity:500}") int queueCapacity) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(Math.max(1, coreSize));
        ex.setMaxPoolSize(Math.max(Math.max(1, coreSize), maxSize));
        ex.setQueueCapacity(Math.max(1, queueCapacity));
        ex.setThreadNamePrefix("integration-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(20);
        ex.initialize();
        return new IntegrationExecutor(ex);
    }

    /**
     * RestTemplate for outbound integration calls: explicit connect/read
     * timeouts and redirects disabled (a redirect target would bypass the
     * UrlSafety check done on the original URL).
     */
    public static RestTemplate outboundRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    public static final class IntegrationExecutor {
        private static final Logger log = LoggerFactory.getLogger(IntegrationExecutor.class);
        private final ThreadPoolTaskExecutor delegate;

        IntegrationExecutor(ThreadPoolTaskExecutor delegate) {
            this.delegate = delegate;
        }

        /** @throws TaskRejectedException when the bounded queue is full. */
        public void submit(String description, Runnable task) {
            delegate.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("Background task '{}' failed: {}", description, e.toString(), e);
                }
            });
        }

        public void shutdown() {
            delegate.shutdown();
        }
    }
}
