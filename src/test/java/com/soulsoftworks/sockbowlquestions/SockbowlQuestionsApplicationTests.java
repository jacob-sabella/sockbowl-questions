package com.soulsoftworks.sockbowlquestions;

import com.soulsoftworks.sockbowlquestions.config.EphemeralPacketProperties;
import com.soulsoftworks.sockbowlquestions.config.PacketLimitsProperties;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full application context boots against a real Neo4j (M3 Q1 acceptance: the
 * schema with every M3 field, the moved taxonomy controller and the new limits all wire
 * up), with application.yml's defaults.
 */
@SpringBootTest
class SockbowlQuestionsApplicationTests extends Neo4jContainerTestBase {

    @Autowired private PacketLimitsProperties limits;
    @Autowired private EphemeralPacketProperties ephemeral;

    @Test
    void contextLoads() {
        assertThat(limits).isNotNull();
    }

    @Test
    void applicationYmlLimitsBind() {
        assertThat(limits.getLimits().getNameMax()).isEqualTo(200);
        assertThat(limits.getLimits().getMaxTossups()).isEqualTo(60);
        assertThat(limits.getImport().getMaxBytes()).isEqualTo(524_288L);
        assertThat(limits.getTaxonomy().isDedupeOnStartup()).isFalse();
        assertThat(ephemeral.getEphemeralTtl()).isEqualTo(Duration.ofHours(24));
    }
}
