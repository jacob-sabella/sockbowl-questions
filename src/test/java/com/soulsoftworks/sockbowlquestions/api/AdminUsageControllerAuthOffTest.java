package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * With auth off (the local-dev default) there is no admin to authorize, so the
 * content-counts endpoint (WP-Q4, M4-AD-01) must not exist at all: it would
 * otherwise hand per-user counts to anyone.
 */
@SpringBootTest(properties = "sockbowl.auth.enabled=false")
@AutoConfigureMockMvc
class AdminUsageControllerAuthOffTest extends Neo4jContainerTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void theEndpointIsAbsent() throws Exception {
        assertThat(context.getBeanNamesForType(AdminUsageController.class)).isEmpty();
        assertThat(mvc.perform(get("/api/admin/usage/content-counts").param("subs", "a"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
