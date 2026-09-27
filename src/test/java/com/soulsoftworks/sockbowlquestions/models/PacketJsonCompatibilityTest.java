package com.soulsoftworks.sockbowlquestions.models;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The models jar is decoded by sockbowl-game with Jackson 3, which binds through the
 * all-args constructor. Payloads from older queries (no {@code visibility}, no
 * {@code answersRedacted}) must still decode, and the new fields must round-trip.
 */
class PacketJsonCompatibilityTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void payloadWithoutNewFieldsDecodes() {
        Packet packet = mapper.readValue(
                "{\"id\":\"1\",\"name\":\"x\",\"difficulty\":null,\"tossups\":[],\"bonuses\":[]}", Packet.class);

        assertThat(packet.getId()).isEqualTo("1");
        assertThat(packet.getVisibility()).isNull();
        assertThat(PacketVisibility.effective(packet.getVisibility())).isEqualTo(PacketVisibility.PUBLISHED);
        assertThat(packet.getAnswersRedacted()).isNull();
    }

    @Test
    void newFieldsRoundTrip() {
        Packet packet = mapper.readValue(
                "{\"id\":\"1\",\"visibility\":\"DRAFT\",\"answersRedacted\":true}",
                Packet.class);

        assertThat(packet.getVisibility()).isEqualTo(PacketVisibility.DRAFT);
        assertThat(packet.getAnswersRedacted()).isTrue();
    }
}
