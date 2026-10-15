package com.betteranalytics.crm.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * spring-boot-starter-data-redis is on the classpath for the optional Redis
 * features, but unless app.redis.enabled=true (REDIS_ENABLED) the app must not
 * create a Redis connection factory, attempt connections or report a Redis
 * health indicator. This post-processor (registered in META-INF/spring.factories)
 * adds Spring's Redis auto-configurations to spring.autoconfigure.exclude when
 * Redis is off, keeping any exclusions configured elsewhere.
 */
public class RedisAutoConfigToggle implements EnvironmentPostProcessor {

    static final List<String> REDIS_AUTO_CONFIGS = List.of(
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.data.redis.RedisHealthContributorAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.data.redis.RedisReactiveHealthContributorAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.metrics.redis.LettuceMetricsAutoConfiguration");

    private static final String EXCLUDE = "spring.autoconfigure.exclude";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (redisEnabled(env)) return;
        Set<String> excludes = new LinkedHashSet<>();
        String existing = env.getProperty(EXCLUDE);
        if (existing != null) {
            for (String e : existing.split(",")) if (!e.isBlank()) excludes.add(e.trim());
        }
        // indexed form (spring.autoconfigure.exclude[0]=...) used by YAML lists
        for (int i = 0; ; i++) {
            String v = env.getProperty(EXCLUDE + "[" + i + "]");
            if (v == null) break;
            excludes.add(v.trim());
        }
        excludes.addAll(REDIS_AUTO_CONFIGS);
        env.getPropertySources().addFirst(new MapPropertySource("redisDisabled",
                Map.of(EXCLUDE, String.join(",", excludes))));
    }

    static boolean redisEnabled(ConfigurableEnvironment env) {
        return "true".equalsIgnoreCase(String.valueOf(env.getProperty("app.redis.enabled", "false")).trim());
    }
}
