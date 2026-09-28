package com.soulsoftworks.sockbowlquestions.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sockbowl.packet.*} binding for the M3 authoring limits (plan 3.1.1), next to the
 * D15 EPHEMERAL settings that share the prefix.
 */
class PacketLimitsPropertiesTest {

    @Configuration
    @EnableConfigurationProperties({PacketLimitsProperties.class, EphemeralPacketProperties.class})
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void defaultsMatchThePlan() {
        runner.run(ctx -> {
            PacketLimitsProperties props = ctx.getBean(PacketLimitsProperties.class);
            PacketLimitsProperties.Limits limits = props.getLimits();
            assertThat(limits.getNameMax()).isEqualTo(200);
            assertThat(limits.getQuestionMax()).isEqualTo(4000);
            assertThat(limits.getAnswerMax()).isEqualTo(1000);
            assertThat(limits.getPreambleMax()).isEqualTo(2000);
            assertThat(limits.getMaxTossups()).isEqualTo(60);
            assertThat(limits.getMaxBonuses()).isEqualTo(60);
            assertThat(limits.getMaxPartsPerBonus()).isEqualTo(6);
            assertThat(limits.getMinPartsPerBonus()).isEqualTo(1);
            assertThat(props.getImport().getMaxBytes()).isEqualTo(524_288L);
            assertThat(props.getTaxonomy().getNameMax()).isEqualTo(100);
            assertThat(props.getTaxonomy().isDedupeOnStartup()).isFalse();
        });
    }

    @Test
    void everyGroupBindsIncludingImport() {
        runner.withPropertyValues(
                        "sockbowl.packet.limits.name-max=50",
                        "sockbowl.packet.limits.max-parts-per-bonus=4",
                        "sockbowl.packet.import.max-bytes=1024",
                        "sockbowl.packet.taxonomy.name-max=30",
                        "sockbowl.packet.taxonomy.dedupe-on-startup=true",
                        "sockbowl.packet.ephemeral-ttl=2h")
                .run(ctx -> {
                    PacketLimitsProperties props = ctx.getBean(PacketLimitsProperties.class);
                    assertThat(props.getLimits().getNameMax()).isEqualTo(50);
                    assertThat(props.getLimits().getMaxPartsPerBonus()).isEqualTo(4);
                    assertThat(props.getImport().getMaxBytes()).isEqualTo(1024L);
                    assertThat(props.getTaxonomy().getNameMax()).isEqualTo(30);
                    assertThat(props.getTaxonomy().isDedupeOnStartup()).isTrue();
                    // The shared prefix doesn't disturb the EPHEMERAL settings (D15).
                    assertThat(ctx.getBean(EphemeralPacketProperties.class).getEphemeralTtl())
                            .isEqualTo(Duration.ofHours(2));
                });
    }

    @Test
    void envStyleRelaxedNamesBind() {
        runner.withPropertyValues(
                        "sockbowl.packet.limits.questionMax=123",
                        "sockbowl.packet.import.maxBytes=2048")
                .run(ctx -> {
                    PacketLimitsProperties props = ctx.getBean(PacketLimitsProperties.class);
                    assertThat(props.getLimits().getQuestionMax()).isEqualTo(123);
                    assertThat(props.getImport().getMaxBytes()).isEqualTo(2048L);
                });
    }
}
