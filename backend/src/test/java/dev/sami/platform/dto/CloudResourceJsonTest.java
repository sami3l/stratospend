package dev.sami.platform.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudResourceJsonTest {
    // Exercise the permissive mapper setting used by Spring Boot: DTO strictness must still apply.
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private static final String EDITABLE_JSON = """
            {"name":"Resource","category":"COMPUTE","providerService":"EC2",
             "region":"global","status":"ACTIVE"}
            """;

    @Test
    void deserializesValidCreateAndUpdateWithoutChangingExternalId() throws Exception {
        var json = (ObjectNode) mapper.readTree(EDITABLE_JSON);
        json.put("cloudAccountId", 1);
        json.put("externalResourceId", "  CaseSensitive/ID:AbC  ");

        var create = mapper.treeToValue(json, CloudResourceRequest.class);
        var update = mapper.readValue(EDITABLE_JSON, UpdateCloudResourceRequest.class);

        assertThat(create.cloudAccountId()).isEqualTo(1L);
        assertThat(create.externalResourceId()).isEqualTo("  CaseSensitive/ID:AbC  ");
        assertThat(create.category()).isEqualTo(ResourceCategory.COMPUTE);
        assertThat(update.status()).isEqualTo(ResourceStatus.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cloudAccountId", "externalResourceId", "provider", "id", "createdAt", "updatedAt", "unexpected"})
    void rejectsUnknownAndImmutableUpdatePropertiesEvenWhenNull(String property) throws Exception {
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
        var json = (ObjectNode) mapper.readTree(EDITABLE_JSON);
        json.put(property, "attempted change");

        assertThatThrownBy(() -> mapper.treeToValue(json, UpdateCloudResourceRequest.class))
                .isInstanceOf(JsonMappingException.class).hasMessageContaining("Unknown or immutable property: " + property);

        json.putNull(property);
        assertThatThrownBy(() -> mapper.treeToValue(json, UpdateCloudResourceRequest.class))
                .isInstanceOf(JsonMappingException.class).hasMessageContaining("Unknown or immutable property: " + property);
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "id", "createdAt", "updatedAt", "unexpected"})
    void rejectsUnknownAndServerManagedCreateProperties(String property) throws Exception {
        var json = (ObjectNode) mapper.readTree(EDITABLE_JSON);
        json.put("cloudAccountId", 1);
        json.put("externalResourceId", "resource-1");
        json.putObject(property).put("nested", "value");

        assertThatThrownBy(() -> mapper.treeToValue(json, CloudResourceRequest.class))
                .isInstanceOf(JsonMappingException.class).hasMessageContaining("Unknown or immutable property: " + property);
    }

    @ParameterizedTest
    @ValueSource(strings = {"category", "status"})
    void rejectsInvalidEnumNames(String property) throws Exception {
        var json = (ObjectNode) mapper.readTree(EDITABLE_JSON);
        json.put(property, "NOT_A_VALID_ENUM");
        assertThatThrownBy(() -> mapper.treeToValue(json, UpdateCloudResourceRequest.class))
                .isInstanceOf(JsonMappingException.class);
        json.put("cloudAccountId", 1);
        json.put("externalResourceId", "resource-1");
        assertThatThrownBy(() -> mapper.treeToValue(json, CloudResourceRequest.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    void mapsAccountDetailsAndSerializesOnlyResponseFields() {
        var account = new CloudAccount("Production Azure", CloudProvider.AZURE, "subscription-1",
                CloudEnvironment.PRODUCTION, "westeurope");
        var resource = new CloudResource(account, "/subscriptions/Sub-A/resourceGroups/MyGroup", "Resource",
                ResourceCategory.COMPUTE, "Virtual Machines", "global", ResourceStatus.ACTIVE);
        var createdAt = Instant.parse("2026-01-01T00:00:00Z");
        var updatedAt = Instant.parse("2026-02-01T00:00:00Z");
        ReflectionTestUtils.setField(account, "id", 7L);
        ReflectionTestUtils.setField(resource, "id", 42L);
        ReflectionTestUtils.setField(resource, "createdAt", createdAt);
        ReflectionTestUtils.setField(resource, "updatedAt", updatedAt);

        var response = CloudResourceResponse.from(resource);
        assertThat(response).isEqualTo(new CloudResourceResponse(42L, 7L, "Production Azure", CloudProvider.AZURE,
                resource.getExternalResourceId(), "Resource", ResourceCategory.COMPUTE, "Virtual Machines", "global",
                ResourceStatus.ACTIVE, createdAt, updatedAt));
        ObjectNode json = mapper.valueToTree(response);
        assertThat(json.properties()).extracting(entry -> entry.getKey()).containsExactlyInAnyOrder(
                "id", "cloudAccountId", "cloudAccountName", "provider", "externalResourceId", "name", "category",
                "providerService", "region", "status", "createdAt", "updatedAt");
        assertThat(json.get("provider").asText()).isEqualTo("AZURE");
    }

    @Test
    void serializesStableGenericPageWithoutSpringDataMetadata() throws Exception {
        var page = new PageResponse<>(List.of("resource-1"), 2, 20, 41L, 3);

        assertThat(mapper.readTree(mapper.writeValueAsString(page))).isEqualTo(mapper.readTree("""
                {"content":["resource-1"],"page":2,"size":20,"totalElements":41,"totalPages":3}
                """));
        assertThat(mapper.readTree(mapper.writeValueAsString(new PageResponse<>(List.of(), 0, 20, 0, 0))))
                .isEqualTo(mapper.readTree("""
                        {"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
                        """));
    }

    @Test
    void pageContentIsADefensiveCopy() {
        var content = new ArrayList<>(List.of("resource-1"));
        var response = new PageResponse<>(content, 0, 20, 1, 1);

        content.clear();

        assertThat(response.content()).containsExactly("resource-1");
        assertThatThrownBy(() -> response.content().add("resource-2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
