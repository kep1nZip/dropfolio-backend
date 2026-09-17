package com.dropfolio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Dropfolio backend entry point.
 *
 * Modular monolith, package-by-feature. See CLAUDE_CONTEXT.md §3 for module boundaries.
 * {@link EnableScheduling} is required here because scheduler/ (PriceSyncJob, EmailWorker)
 * relies on {@code @Scheduled} — see CLAUDE_CONTEXT.md §8.4.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class DropfolioApplication {

    public static void main(String[] args) {
        SpringApplication.run(DropfolioApplication.class, args);
    }
}
