package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.config.NoSecurityConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService.ImportOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth-off regression ({@code sockbowl.auth.enabled=false}, the local-dev default):
 * {@code import-random} stays open to anonymous callers and creates an ownerless
 * packet, exactly as before M2. The count clamp still applies.
 */
@WebMvcTest(controllers = QbreaderController.class, properties = "sockbowl.auth.enabled=false")
@Import(NoSecurityConfig.class)
class QbreaderImportAuthOffTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QbreaderImportService importService;

    @Test
    void anonymous_import_is_allowed_and_ownerless_with_clamp() throws Exception {
        Packet p = new Packet();
        p.setId("p");
        when(importService.importRandomPacket(any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any(), any(),
                any())).thenReturn(new ImportOutcome(p, List.of(), 5, 5));

        mvc.perform(post("/api/qbreader/import-random").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tossupCount\":99,\"bonusCount\":3}"))
                .andExpect(status().isOk());

        // Auth off keeps the pre-M2 behavior: an ownerless DRAFT (readable by anyone), not EPHEMERAL.
        verify(importService).importRandomPacket(any(), eq(30), eq(3), isNull(), isNull(), eq(false), isNull(), isNull(),
                eq(PacketVisibility.DRAFT));
    }

    @Test
    void tossup_count_zero_is_400_even_with_auth_off() throws Exception {
        mvc.perform(post("/api/qbreader/import-random").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tossupCount\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unlisted_paths_are_not_blocked_by_security_with_auth_off() throws Exception {
        // NoSecurityConfig keeps the permit-all topology: an unmapped path is a plain 404.
        mvc.perform(get("/api/unknown")).andExpect(status().isNotFound());
    }
}
