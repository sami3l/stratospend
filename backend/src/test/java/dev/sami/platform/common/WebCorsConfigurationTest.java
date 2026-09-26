package dev.sami.platform.common;

import dev.sami.platform.controller.CloudAccountController;
import dev.sami.platform.controller.CloudResourceController;
import dev.sami.platform.dto.CloudResourceResponse;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import dev.sami.platform.service.CloudAccountService;
import dev.sami.platform.service.CloudResourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CloudAccountController.class, CloudResourceController.class},
        properties = "app.cors.allowed-origins=http://localhost:3000, https://ui.stratospend.test ")
class WebCorsConfigurationTest {
    @Autowired MockMvc mvc;
    @MockitoBean CloudAccountService accounts;
    @MockitoBean CloudResourceService resources;

    @ParameterizedTest
    @CsvSource({
            "/api/v1/cloud-accounts,GET", "/api/v1/cloud-accounts,POST",
            "/api/v1/cloud-accounts/7,PUT", "/api/v1/cloud-accounts/7/status,PATCH",
            "/api/v1/cloud-resources,GET", "/api/v1/cloud-resources,POST",
            "/api/v1/cloud-resources/42,PUT",
    })
    void allowsPreflightAcrossBothControllers(String path, String method) throws Exception {
        mvc.perform(options(path).header("Origin", "https://ui.stratospend.test")
                        .header("Access-Control-Request-Method", method)
                        .header("Access-Control-Request-Headers", "content-type,x-inventory-client"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://ui.stratospend.test"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,OPTIONS"))
                .andExpect(header().string("Access-Control-Allow-Headers", "content-type, x-inventory-client"))
                .andExpect(header().string("Access-Control-Expose-Headers", "Location"))
                .andExpect(header().string("Access-Control-Max-Age", "3600"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        verifyNoInteractions(accounts, resources);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "https://ui.stratospend.test"})
    void allowsEachExplicitOriginOnActualRequests(String origin) throws Exception {
        when(accounts.findAll()).thenReturn(List.of());
        mvc.perform(get("/api/v1/cloud-accounts").header("Origin", origin))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
                .andExpect(content().json("[]"));
    }

    @Test
    void exposesCreatedResourceLocation() throws Exception {
        when(resources.create(any())).thenReturn(new CloudResourceResponse(42L, 7L, "Account", CloudProvider.AWS,
                "resource-1", "Resource", ResourceCategory.COMPUTE, "EC2", "global", ResourceStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH));
        mvc.perform(post("/api/v1/cloud-resources").header("Origin", "https://ui.stratospend.test")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"cloudAccountId":7,"externalResourceId":"resource-1","name":"Resource",
                                 "category":"COMPUTE","providerService":"EC2","region":"global","status":"ACTIVE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/cloud-resources/42"))
                .andExpect(header().string("Access-Control-Expose-Headers", "Location"))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://ui.stratospend.test"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    void rejectsUnlistedOriginsForPreflightAndActualRequests() throws Exception {
        mvc.perform(options("/api/v1/cloud-resources").header("Origin", "https://unlisted.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/cloud-accounts").header("Origin", "https://unlisted.test"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(accounts, resources);
    }

    @Test
    void rejectsDeletePreflight() throws Exception {
        mvc.perform(options("/api/v1/cloud-resources/42").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(accounts, resources);
    }

    @Test
    void defaultsToLocalFrontendAndOnlyMapsApiPaths() {
        new ApplicationContextRunner().withUserConfiguration(CorsConfiguration.class).run(context -> {
            var registry = new InspectableRegistry();
            context.getBean(CorsConfiguration.class).addCorsMappings(registry);
            assertThat(registry.mappings()).containsOnlyKeys("/api/**");
            var policy = registry.mappings().get("/api/**");
            assertThat(policy.getAllowedOrigins()).containsExactly("http://localhost:3000");
            assertThat(policy.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "PATCH", "OPTIONS");
            assertThat(policy.getAllowCredentials()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "http://localhost:3000,*", " , "})
    void rejectsWildcardOrEmptyOriginConfiguration(String origins) {
        assertThatThrownBy(() -> new CorsConfiguration(origins)).isInstanceOf(IllegalArgumentException.class);
    }

    private static class InspectableRegistry extends CorsRegistry {
        Map<String, org.springframework.web.cors.CorsConfiguration> mappings() {
            return getCorsConfigurations();
        }
    }
}
