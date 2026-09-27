package com.soulsoftworks.sockbowlquestions.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for the EPHEMERAL packet cleanup (D15). Kept off the
 * application class so test slices don't pick it up, and switched off together with
 * the job by {@code sockbowl.packet.ephemeral-cleanup.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "sockbowl.packet.ephemeral-cleanup.enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
