package dev.sami.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.sami.platform.dto.CloudResourceQuery;
import dev.sami.platform.dto.CloudResourceRequest;
import dev.sami.platform.dto.CloudResourceResponse;
import dev.sami.platform.dto.PageResponse;
import dev.sami.platform.dto.UpdateCloudResourceRequest;
import dev.sami.platform.exception.CloudAccountNotFoundException;
import dev.sami.platform.exception.CloudResourceNotFoundException;
import dev.sami.platform.exception.DuplicateCloudResourceException;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import dev.sami.platform.service.CloudResourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CloudResourceController.class,
        properties = "app.cors.allowed-origins=https://ui.stratospend.test")
class CloudResourceControllerTest {
    private static final String BASE = "/api/v1/cloud-resources";
    private static final String EXTERNAL_ID = "CaseSensitive/ID:AbC";
    private static final String PRIVATE_DETAILS = "SQL SELECT password; constraint secret_constraint; database-secret";
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-01-02T00:00:00Z");
    private static final CloudResourceRequest CREATE = new CloudResourceRequest(7L, EXTERNAL_ID,
            "Web server", ResourceCategory.COMPUTE, "EC2", "eu-west-1", ResourceStatus.ACTIVE);
    private static final UpdateCloudResourceRequest UPDATE = new UpdateCloudResourceRequest(
            "Updated server", ResourceCategory.CONTAINER, "ECS", "global", ResourceStatus.INACTIVE);
    private static final CloudResourceResponse RESOURCE = new CloudResourceResponse(42L, 7L,
            "Production AWS", CloudProvider.AWS, EXTERNAL_ID, "Web server", ResourceCategory.COMPUTE,
            "EC2", "eu-west-1", ResourceStatus.ACTIVE, CREATED, CREATED);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CloudResourceService service;

    @Test
    void createsResourceWithLocationAndCompleteResponse() throws Exception {
        when(service.create(CREATE)).thenReturn(RESOURCE);

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(CREATE)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", BASE + "/42"))
                .andExpect(resourceBody(RESOURCE));
        verify(service).create(CREATE);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidAccountId(long id) throws Exception {
        var body = createBody().put("cloudAccountId", id);
        rejectCreate(body, "cloudAccountId", "Validation failed");
    }

    @ParameterizedTest
    @CsvSource({"externalResourceId,512", "name,120", "providerService,80", "region,40"})
    void rejectsBlankAndOversizedCreateStrings(String field, int maxLength) throws Exception {
        rejectCreate(createBody().put(field, " "), field, "Validation failed");
        rejectCreate(createBody().put(field, "a".repeat(maxLength + 1)), field, "Validation failed");
    }

    @Test
    void rejectsInvalidRegion() throws Exception {
        rejectCreate(createBody().put("region", "EU_WEST_1"), "region", "Validation failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cloudAccountId", "category", "status"})
    void rejectsMissingRequiredCreateValues(String field) throws Exception {
        var body = createBody();
        body.remove(field);
        rejectCreate(body, field, "Validation failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"category", "status"})
    void rejectsInvalidCreateEnum(String field) throws Exception {
        rejectCreate(createBody().put(field, "not-an-enum"), field, "Invalid request body");
    }

    @Test
    void rejectsUnknownCreateProperty() throws Exception {
        rejectCreate(createBody().put("unexpected", "value"), "unexpected", "Invalid request body");
    }

    @Test
    void returnsNotFoundWhenCreateAccountIsMissing() throws Exception {
        when(service.create(CREATE)).thenThrow(new CloudAccountNotFoundException(7L));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(CREATE)))
                .andExpect(problem(404, "Cloud account not found: 7"))
                .andExpect(jsonPath("$.errors").isEmpty());
        verify(service).create(CREATE);
    }

    @Test
    void returnsConflictWithoutExposingDuplicateCause() throws Exception {
        when(service.create(CREATE)).thenThrow(new DuplicateCloudResourceException(7L, EXTERNAL_ID,
                new DataIntegrityViolationException(PRIVATE_DETAILS)));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(CREATE)))
                .andExpect(problem(409, "A resource with external ID " + EXTERNAL_ID
                        + " already exists in cloud account 7"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void findsResourceById() throws Exception {
        when(service.findById(42L)).thenReturn(RESOURCE);
        mvc.perform(get(BASE + "/42")).andExpect(status().isOk()).andExpect(resourceBody(RESOURCE));
        verify(service).findById(42L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveLookupId(long id) throws Exception {
        mvc.perform(get(BASE + "/{id}", id))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.id").value("must be greater than 0"));
        verifyNoInteractions(service);
    }

    @Test
    void returnsNotFoundForMissingResource() throws Exception {
        when(service.findById(42L)).thenThrow(new CloudResourceNotFoundException(42L));
        mvc.perform(get(BASE + "/42"))
                .andExpect(problem(404, "Cloud resource not found: 42"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void listsWithDefaultsAndStablePageJson() throws Exception {
        var query = new CloudResourceQuery(null, null, null, null, null, 0, 20, null);
        var page = new PageResponse<>(List.of(RESOURCE), 0, 20, 1, 1);
        when(service.findAll(query)).thenReturn(page);
        mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(pageBody(page));
        verify(service).findAll(query);
    }

    @Test
    void bindsAllFiltersPaginationAndSortTogether() throws Exception {
        var query = new CloudResourceQuery(7L, CloudProvider.AWS, ResourceCategory.COMPUTE,
                "eu-west-1", ResourceStatus.ACTIVE, 2, 5, "name,asc");
        var page = new PageResponse<>(List.of(RESOURCE), 2, 5, 11, 3);
        when(service.findAll(query)).thenReturn(page);
        mvc.perform(get(BASE).param("cloudAccountId", "7").param("provider", "AWS")
                        .param("category", "COMPUTE").param("region", "eu-west-1").param("status", "ACTIVE")
                        .param("page", "2").param("size", "5").param("sort", "name,asc"))
                .andExpect(status().isOk()).andExpect(pageBody(page));
        verify(service).findAll(query);
    }

    @ParameterizedTest
    @CsvSource({"cloudAccountId,7", "provider,AZURE", "category,STORAGE", "region,global", "status,UNKNOWN"})
    void bindsEachOptionalFilterIndependently(String field, String value) throws Exception {
        var query = new CloudResourceQuery(
                field.equals("cloudAccountId") ? Long.valueOf(value) : null,
                field.equals("provider") ? CloudProvider.valueOf(value) : null,
                field.equals("category") ? ResourceCategory.valueOf(value) : null,
                field.equals("region") ? value : null,
                field.equals("status") ? ResourceStatus.valueOf(value) : null, 0, 20, null);
        var page = new PageResponse<CloudResourceResponse>(List.of(), 0, 20, 0, 0);
        when(service.findAll(query)).thenReturn(page);
        mvc.perform(get(BASE).param(field, value)).andExpect(status().isOk()).andExpect(pageBody(page));
        verify(service).findAll(query);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "name", "category", "providerService", "region", "status", "createdAt", "updatedAt"})
    void acceptsApprovedSortFieldsInBothDirections(String field) throws Exception {
        for (String direction : List.of("asc", "desc")) {
            var query = new CloudResourceQuery(null, null, null, null, null, 0, 20, field + "," + direction);
            when(service.findAll(query)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
            mvc.perform(get(BASE).param("sort", field + "," + direction)).andExpect(status().isOk());
            verify(service).findAll(query);
        }
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,101", "cloudAccountId,0", "region,EU_WEST_1",
            "sort,'provider,asc'", "sort,'name,ASC'", "sort,name", "sort,'name,asc,id,desc'"})
    void rejectsInvalidQueryConstraints(String field, String value) throws Exception {
        mvc.perform(get(BASE).param(field, value))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors." + field).isNotEmpty());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "category", "status", "cloudAccountId", "page", "size"})
    void rejectsInvalidQueryTypes(String field) throws Exception {
        mvc.perform(get(BASE).param(field, "invalid"))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors." + field).isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void returnsEmptyPageWithRequestedMetadata() throws Exception {
        var query = new CloudResourceQuery(null, CloudProvider.GCP, null, null, null, 3, 10, null);
        var page = new PageResponse<CloudResourceResponse>(List.of(), 3, 10, 0, 0);
        when(service.findAll(query)).thenReturn(page);
        mvc.perform(get(BASE).param("provider", "GCP").param("page", "3").param("size", "10"))
                .andExpect(status().isOk()).andExpect(pageBody(page));
        verify(service).findAll(query);
    }

    @Test
    void returnsNotFoundForExplicitlyFilteredMissingAccount() throws Exception {
        var query = new CloudResourceQuery(7L, null, null, null, null, 0, 20, null);
        when(service.findAll(query)).thenThrow(new CloudAccountNotFoundException(7L));
        mvc.perform(get(BASE).param("cloudAccountId", "7"))
                .andExpect(problem(404, "Cloud account not found: 7"))
                .andExpect(jsonPath("$.errors").isEmpty());
        verify(service).findAll(query);
    }

    @Test
    void updatesResourceWithExactServiceDelegation() throws Exception {
        var updated = new CloudResourceResponse(42L, 7L, "Production AWS", CloudProvider.AWS, EXTERNAL_ID,
                UPDATE.name(), UPDATE.category(), UPDATE.providerService(), UPDATE.region(), UPDATE.status(), CREATED, UPDATED);
        when(service.update(42L, UPDATE)).thenReturn(updated);
        mvc.perform(put(BASE + "/42").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(UPDATE)))
                .andExpect(status().isOk()).andExpect(resourceBody(updated));
        verify(service).update(42L, UPDATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "category", "providerService", "region", "status"})
    void requiresEveryEditableFieldOnUpdate(String field) throws Exception {
        ObjectNode body = mapper.valueToTree(UPDATE);
        body.remove(field);
        rejectUpdate(body, field, "Validation failed");
    }

    @Test
    void rejectsInvalidUpdateFieldValues() throws Exception {
        ObjectNode body = mapper.valueToTree(UPDATE);
        body.put("name", " ").put("providerService", "a".repeat(81)).put("region", "Paris");
        mvc.perform(put(BASE + "/42").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.name").isNotEmpty())
                .andExpect(jsonPath("$.errors.providerService").isNotEmpty())
                .andExpect(jsonPath("$.errors.region").isNotEmpty());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveUpdateId(long id) throws Exception {
        mvc.perform(put(BASE + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(UPDATE)))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.id").value("must be greater than 0"));
        verifyNoInteractions(service);
    }

    @Test
    void returnsNotFoundForMissingUpdateResource() throws Exception {
        when(service.update(42L, UPDATE)).thenThrow(new CloudResourceNotFoundException(42L));
        mvc.perform(put(BASE + "/42").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(UPDATE)))
                .andExpect(problem(404, "Cloud resource not found: 42"))
                .andExpect(jsonPath("$.errors").isEmpty());
        verify(service).update(42L, UPDATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cloudAccountId", "externalResourceId", "provider", "unexpected"})
    void rejectsImmutableAndUnknownUpdatePropertiesEvenWhenNull(String field) throws Exception {
        ObjectNode body = mapper.valueToTree(UPDATE);
        body.put(field, "value");
        rejectUpdate(body, field, "Invalid request body");
        body.putNull(field);
        rejectUpdate(body, field, "Invalid request body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "9223372036854775808"})
    void rejectsInvalidNumericPathForLookupAndUpdate(String id) throws Exception {
        mvc.perform(get(BASE + "/{id}", id)).andExpect(problem(400, "Invalid parameter"))
                .andExpect(jsonPath("$.errors.id").value("must be a valid integer"));
        mvc.perform(put(BASE + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(UPDATE)))
                .andExpect(problem(400, "Invalid parameter"))
                .andExpect(jsonPath("$.errors.id").value("must be a valid integer"));
        verifyNoInteractions(service);
    }

    @Test
    void sanitizesUnexpectedDatabaseFailuresInsteadOfReturningConflict() throws Exception {
        when(service.create(any())).thenThrow(new DataIntegrityViolationException(PRIVATE_DETAILS,
                new IllegalStateException("private-cause")));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(CREATE)))
                .andExpect(problem(500, "Internal server error"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void usesConfiguredCorsOrigin() throws Exception {
        mvc.perform(options(BASE).header("Origin", "https://ui.stratospend.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://ui.stratospend.test"));
        verifyNoInteractions(service);
    }

    @Test
    void doesNotExposeDelete() throws Exception {
        mvc.perform(delete(BASE + "/42")).andExpect(problem(405, "Method Not Allowed"));
        verifyNoInteractions(service);
    }

    private ObjectNode createBody() {
        return mapper.valueToTree(CREATE);
    }

    private void rejectCreate(ObjectNode body, String field, String message) throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(problem(400, message)).andExpect(jsonPath("$.errors." + field).isNotEmpty());
        verifyNoInteractions(service);
    }

    private void rejectUpdate(ObjectNode body, String field, String message) throws Exception {
        mvc.perform(put(BASE + "/42").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(problem(400, message)).andExpect(jsonPath("$.errors." + field).isNotEmpty());
        verifyNoInteractions(service);
    }

    private ResultMatcher resourceBody(CloudResourceResponse expected) {
        return result -> {
            var body = mapper.readTree(result.getResponse().getContentAsString());
            assertThat(body).isEqualTo(mapper.readTree(mapper.writeValueAsString(expected)));
            var fields = new ArrayList<String>();
            body.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactly("id", "cloudAccountId", "cloudAccountName", "provider",
                    "externalResourceId", "name", "category", "providerService", "region", "status", "createdAt", "updatedAt");
        };
    }

    private ResultMatcher pageBody(PageResponse<CloudResourceResponse> expected) {
        return result -> {
            var body = mapper.readTree(result.getResponse().getContentAsString());
            var fields = new ArrayList<String>();
            body.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactly("content", "page", "size", "totalElements", "totalPages");
            assertThat(body).isEqualTo(mapper.readTree(mapper.writeValueAsString(expected)));
        };
    }

    private ResultMatcher problem(int expectedStatus, String message) {
        return result -> {
            assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
            var json = result.getResponse().getContentAsString();
            var body = mapper.readTree(json);
            var fields = new ArrayList<String>();
            body.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactly("timestamp", "status", "message", "errors");
            assertThat(body.get("status").asInt()).isEqualTo(expectedStatus);
            assertThat(body.get("message").asText()).isEqualTo(message);
            assertThat(body.get("errors").isObject()).isTrue();
            assertThat(body.get("timestamp").asText()).endsWith("Z");
            Instant.parse(body.get("timestamp").asText());
            assertThat(json).doesNotContain("SQL SELECT", "secret_constraint", "database-secret", "private-cause",
                    "stackTrace", "DataIntegrityViolationException", "java.lang", "\"cause\"");
        };
    }
}
