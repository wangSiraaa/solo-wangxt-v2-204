package com.example.craft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "craft")
public record CraftProperties(Scheduler scheduler, long tokenTtlHours) {

    public record Scheduler(boolean enabled) {}

    public boolean schedulerEnabled() {
        return scheduler != null && scheduler.enabled();
    }
}
