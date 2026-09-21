package dev.sami.platform.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.jayway.jsonpath.JsonPath;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CloudAccountControllerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void completesTheCloudAccountLifecycle() throws Exception {
        String createdAccount = mockMvc.perform(post("/api/v1/cloud-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Production AWS",
                                  "provider":"AWS",
                                  "externalAccountId":"123456789012",
                                  "environment":"PRODUCTION",
                                  "region":"eu-west-3"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        Integer accountId = JsonPath.read(createdAccount, "$.id");

        mockMvc.perform(get("/api/v1/cloud-accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].provider").value("AWS"));

        mockMvc.perform(patch("/api/v1/cloud-accounts/{id}/status", accountId).param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/cloud-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","provider":"AWS","externalAccountId":"bad id","environment":"PRODUCTION","region":"Paris"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.region").exists())
                .andExpect(jsonPath("$.errors.externalAccountId").exists());
    }

    @Test
    void returnsNotFoundForUnknownAccount() throws Exception {
        mockMvc.perform(get("/api/v1/cloud-accounts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Cloud account not found: 999"));
    }
}
