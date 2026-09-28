package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.config.JwtDecoderConfig;
import com.soulsoftworks.sockbowlquestions.config.SecurityConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService.ImportOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTH-07 / D3 as amended by D15: {@code POST /api/qbreader/import-random} with auth
 * on. A caller with {@code packet:create} gets an owned DRAFT packet; anyone else
 * (anonymous guest, player) gets an ownerless EPHEMERAL one. The requested counts are
 * clamped to {@code sockbowl.import.*} either way, and an invalid bearer is a 401.
 */
@WebMvcTest(controllers = QbreaderController.class, properties = "sockbowl.auth.enabled=true")
@Import({SecurityConfig.class, JwtDecoderConfig.class})
class QbreaderImportAuthTest {

    private static final String URL = "/api/qbreader/import-random";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QbreaderImportService importService;

    @BeforeEach
    void stubImport() {
        Packet p = new Packet();
        p.setId("new-packet");
        p.setName("Random packet");
        when(importService.importRandomPacket(any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any(), any(),
                any())).thenReturn(new ImportOutcome(p, List.of("r1", "r2"), 5, 5));
    }

    private static MockHttpServletRequestBuilder importRequest(String body) {
        return post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor author() {
        return jwt().jwt(j -> j.subject("author-sub").claim("preferred_username", "alice"))
                .authorities(new SimpleGrantedAuthority("packet:create"), new SimpleGrantedAuthority("packet:read"));
    }

    private void verifyNoImport() {
        verify(importService, never())
                .importRandomPacket(any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void anonymous_guest_gets_an_ownerless_ephemeral_packet_with_clamp() throws Exception {
        mvc.perform(importRequest("{\"tossupCount\":500,\"bonusCount\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("new-packet"));

        verify(importService).importRandomPacket(any(), eq(30), eq(5), isNull(), isNull(), eq(false),
                isNull(), isNull(), eq(PacketVisibility.EPHEMERAL));
    }

    @Test
    void player_without_packet_create_gets_an_ownerless_ephemeral_packet() throws Exception {
        mvc.perform(importRequest("{\"tossupCount\":5,\"bonusCount\":5}")
                        .with(jwt().jwt(j -> j.subject("player-sub").claim("preferred_username", "pat"))
                                .authorities(new SimpleGrantedAuthority("packet:read"),
                                        new SimpleGrantedAuthority("game:host"))))
                .andExpect(status().isOk());

        // No owner is recorded, even though the caller is known: nobody may manage it.
        verify(importService).importRandomPacket(any(), eq(5), eq(5), isNull(), isNull(), eq(false),
                isNull(), isNull(), eq(PacketVisibility.EPHEMERAL));
    }

    @Test
    void invalid_bearer_is_401() throws Exception {
        mvc.perform(importRequest("{\"tossupCount\":5}").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        verifyNoImport();
    }

    @Test
    void guest_tossup_count_zero_is_400() throws Exception {
        mvc.perform(importRequest("{\"tossupCount\":0,\"bonusCount\":5}"))
                .andExpect(status().isBadRequest());
        verifyNoImport();
    }

    @Test
    void author_is_200_with_counts_clamped_and_owner_passed_through() throws Exception {
        mvc.perform(importRequest("{\"tossupCount\":500,\"bonusCount\":31,\"name\":\"Mine\"}").with(author()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("new-packet"))
                .andExpect(jsonPath("$.usedRemoteIds.length()").value(2));

        verify(importService).importRandomPacket(any(), eq(30), eq(30), eq("Mine"), isNull(), eq(false),
                eq("author-sub"), eq("alice"), eq(PacketVisibility.DRAFT));
    }

    @Test
    void author_counts_within_bounds_pass_unchanged() throws Exception {
        mvc.perform(importRequest("{\"tossupCount\":12,\"bonusCount\":0}").with(author()))
                .andExpect(status().isOk());

        // bonusCount=0 is a tossup-only packet, which the Generate UI allows.
        verify(importService).importRandomPacket(any(), eq(12), eq(0), isNull(), isNull(), eq(false),
                eq("author-sub"), anyString(), eq(PacketVisibility.DRAFT));
    }

    @Test
    void unset_counts_default_to_20() throws Exception {
        mvc.perform(importRequest("{}").with(author())).andExpect(status().isOk());

        verify(importService).importRandomPacket(any(), eq(20), eq(20), isNull(), isNull(), eq(false),
                eq("author-sub"), anyString(), eq(PacketVisibility.DRAFT));
    }

    @Test
    void tossup_count_zero_is_400() throws Exception {
        mvc.perform(importRequest("{\"tossupCount\":0,\"bonusCount\":5}").with(author()))
                .andExpect(status().isBadRequest());
        verifyNoImport();
    }

    @Test
    void negative_counts_are_400() throws Exception {
        mvc.perform(importRequest("{\"tossupCount\":-5,\"bonusCount\":5}").with(author()))
                .andExpect(status().isBadRequest());
        mvc.perform(importRequest("{\"tossupCount\":5,\"bonusCount\":-1}").with(author()))
                .andExpect(status().isBadRequest());
        verifyNoImport();
    }
}
