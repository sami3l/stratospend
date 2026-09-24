package dev.sami.platform.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.sami.platform.controller.CloudAccountController;
import dev.sami.platform.dto.CloudResourceQuery;
import dev.sami.platform.dto.CloudResourceRequest;
import dev.sami.platform.dto.UpdateCloudResourceRequest;
import dev.sami.platform.exception.CloudAccountNotFoundException;
import dev.sami.platform.exception.CloudResourceNotFoundException;
import dev.sami.platform.exception.DuplicateCloudAccountException;
import dev.sami.platform.exception.DuplicateCloudResourceException;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.service.CloudAccountService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CloudAccountController.class)
@Import({ApiExceptionHandler.class, ApiExceptionHandlerTest.ErrorEndpoints.class,
        ApiExceptionHandlerTest.LegacyValidationEndpoints.class})
class ApiExceptionHandlerTest {
    private static final String PRIVATE_DETAILS = "SQL SELECT password FROM accounts; constraint secret_constraint; database-secret";
    private static final String RESOURCE_JSON = """
            {"cloudAccountId":7,"externalResourceId":"CaseSensitive/ID:AbC","name":"Resource",
             "category":"COMPUTE","providerService":"Compute","region":"global","status":"ACTIVE"}
            """;
    private static final String UPDATE_JSON = """
            {"name":"Resource","category":"COMPUTE","providerService":"Compute","region":"global","status":"ACTIVE"}
            """;
    private static final String ACCOUNT_JSON = """
            {"name":"Account","provider":"AWS","externalAccountId":"123456789012",
             "environment":"PRODUCTION","region":"eu-west-1"}
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CloudAccountService accountService;

    @Test
    void mapsResourceNotFound() throws Exception {
        mvc.perform(get("/test-errors/domain/resource-missing"))
                .andExpect(problem(404, "Cloud resource not found: 42"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void preservesAccountNotFoundBehaviorOnExistingController() throws Exception {
        when(accountService.findById(99L)).thenThrow(new CloudAccountNotFoundException(99L));
        mvc.perform(get("/api/v1/cloud-accounts/99"))
                .andExpect(problem(404, "Cloud account not found: 99"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void mapsDuplicateResourceWithoutLeakingDatabaseCause() throws Exception {
        mvc.perform(get("/test-errors/domain/resource-duplicate"))
                .andExpect(problem(409, "A resource with external ID resource-1 already exists in cloud account 7"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void preservesDuplicateAccountBehaviorOnExistingController() throws Exception {
        when(accountService.create(any())).thenThrow(new DuplicateCloudAccountException(CloudProvider.AWS, "123456789012"));
        mvc.perform(post("/api/v1/cloud-accounts").contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_JSON))
                .andExpect(problem(409, "A AWS account with ID 123456789012 already exists"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void preservesAccountBodyValidationFields() throws Exception {
        mvc.perform(post("/api/v1/cloud-accounts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","provider":"AWS","externalAccountId":"bad id",
                         "environment":"PRODUCTION","region":"Paris"}
                        """))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.name").value("must not be blank"))
                .andExpect(jsonPath("$.errors.externalAccountId").exists())
                .andExpect(jsonPath("$.errors.region").exists())
                .andExpect(result -> assertThat(result.getResolvedException()).isInstanceOf(MethodArgumentNotValidException.class));
    }

    @Test
    void returnsDeterministicBodyValidationErrors() throws Exception {
        JsonNode firstErrors = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var result = mvc.perform(post("/test-errors/body").contentType(MediaType.APPLICATION_JSON).content("""
                            {"cloudAccountId":0,"externalResourceId":" ","name":"", "region":"",
                             "providerService":"","category":null,"status":null}
                            """))
                    .andExpect(problem(400, "Validation failed"))
                    .andExpect(jsonPath("$.errors.cloudAccountId").value("must be greater than 0"))
                    .andExpect(jsonPath("$.errors.category").value("must not be null"))
                    .andReturn();
            var errors = mapper.readTree(result.getResponse().getContentAsString()).get("errors");
            var fields = new ArrayList<String>();
            errors.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactly("category", "cloudAccountId", "externalResourceId", "name",
                    "providerService", "region", "status");
            if (firstErrors != null) assertThat(errors).isEqualTo(firstErrors);
            firstErrors = errors;
        }
    }

    @Test
    void rejectsInvalidEnumJsonWithoutEchoingValueOrJavaType() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(RESOURCE_JSON);
        body.put("category", PRIVATE_DETAILS);
        mvc.perform(post("/test-errors/body").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(problem(400, "Invalid request body"))
                .andExpect(jsonPath("$.errors.category").value(
                        "must be one of: COMPUTE, STORAGE, DATABASE, NETWORK, CONTAINER, SERVERLESS, OTHER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"name\":", "", "null"})
    void rejectsMalformedOrMissingJson(String body) throws Exception {
        mvc.perform(post("/test-errors/body").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(problem(400, "Invalid request body"));
    }

    @Test
    void rejectsInvalidNumericJsonWithoutEchoingParserDetails() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(RESOURCE_JSON);
        body.put("cloudAccountId", PRIVATE_DETAILS);
        mvc.perform(post("/test-errors/body").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(problem(400, "Invalid request body"))
                .andExpect(jsonPath("$.errors.cloudAccountId").value("must be a valid integer"));
    }

    @Test
    void rejectsUnknownCreateProperty() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(RESOURCE_JSON);
        body.put("unexpected", PRIVATE_DETAILS);
        mvc.perform(post("/test-errors/body").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(problem(400, "Invalid request body"))
                .andExpect(jsonPath("$.errors.unexpected").value("has an invalid value or is not allowed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cloudAccountId", "externalResourceId", "provider", "unexpected"})
    void rejectsImmutableOrUnknownUpdatePropertyIncludingNull(String property) throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(UPDATE_JSON);
        if (property.equals("cloudAccountId")) body.put(property, 8);
        else body.put(property, "changed");
        mvc.perform(put("/test-errors/update").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(problem(400, "Invalid request body"))
                .andExpect(jsonPath("$.errors." + property).exists());
        body.putNull(property);
        mvc.perform(put("/test-errors/update").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(problem(400, "Invalid request body"));
    }

    @Test
    void validatesModelAttributeFields() throws Exception {
        mvc.perform(get("/test-errors/query").param("page", "-1").param("size", "101").param("region", "UPPER"))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.page").value("must be greater than or equal to 0"))
                .andExpect(jsonPath("$.errors.size").value("must be less than or equal to 100"))
                .andExpect(jsonPath("$.errors.region").exists())
                .andExpect(result -> assertThat(result.getResolvedException()).isInstanceOf(MethodArgumentNotValidException.class));
    }

    @Test
    void sanitizesModelAttributeBindingFailures() throws Exception {
        mvc.perform(get("/test-errors/query").param("page", PRIVATE_DETAILS).param("provider", PRIVATE_DETAILS))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.page").value("must be a valid integer"))
                .andExpect(jsonPath("$.errors.provider").value("must be one of: AWS, AZURE, GCP"));
    }

    @Test
    void validatesRequestParamAndPathVariableWithNativeMvcValidation() throws Exception {
        mvc.perform(get("/test-errors/parameters/0").param("limit", "101"))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.resourceId").value("must be greater than 0"))
                .andExpect(jsonPath("$.errors.limit").value("must be less than or equal to 100"))
                .andExpect(result -> assertThat(result.getResolvedException()).isInstanceOf(HandlerMethodValidationException.class));
    }

    @Test
    void handlesBeanErrorsWithinNativeMethodValidation() throws Exception {
        mvc.perform(get("/test-errors/combined").param("size", "101").param("limit", "0"))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.size").exists())
                .andExpect(jsonPath("$.errors.limit").exists())
                .andExpect(result -> assertThat(result.getResolvedException()).isInstanceOf(HandlerMethodValidationException.class));
    }

    @Test
    void handlesLegacyValidatedProxyConstraints() throws Exception {
        mvc.perform(get("/test-errors/legacy/0"))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.id").value("must be greater than 0"))
                .andExpect(result -> assertThat(result.getResolvedException()).isInstanceOf(ConstraintViolationException.class));
    }

    @Test
    void handlesServiceStyleBeanValidation() throws Exception {
        mvc.perform(get("/test-errors/service-validation"))
                .andExpect(problem(400, "Validation failed"))
                .andExpect(jsonPath("$.errors.page").value("must be greater than or equal to 0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "99999999999999999999999999999"})
    void rejectsInvalidPathTypes(String value) throws Exception {
        mvc.perform(get("/api/v1/cloud-accounts/{id}", value))
                .andExpect(problem(400, "Invalid parameter"))
                .andExpect(jsonPath("$.errors.id").value("must be a valid integer"));
    }

    @Test
    void rejectsInvalidQueryTypes() throws Exception {
        mvc.perform(get("/test-errors/parameters/1").param("limit", PRIVATE_DETAILS))
                .andExpect(problem(400, "Invalid parameter"))
                .andExpect(jsonPath("$.errors.limit").value("must be a valid integer"));
        mvc.perform(patch("/api/v1/cloud-accounts/1/status").param("active", PRIVATE_DETAILS))
                .andExpect(problem(400, "Invalid parameter"))
                .andExpect(jsonPath("$.errors.active").value("must be true or false"));
        mvc.perform(get("/test-errors/provider").param("provider", PRIVATE_DETAILS))
                .andExpect(problem(400, "Invalid parameter"))
                .andExpect(jsonPath("$.errors.provider").value("must be one of: AWS, AZURE, GCP"));
    }

    @Test
    void handlesMissingRequiredQueryParameter() throws Exception {
        mvc.perform(patch("/api/v1/cloud-accounts/1/status"))
                .andExpect(problem(400, "Missing required parameter"))
                .andExpect(jsonPath("$.errors.active").value("is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "integrity", "illegal-argument"})
    void sanitizesUnexpectedExceptionsAndUnrelatedIntegrityErrors(String kind) throws Exception {
        mvc.perform(get("/test-errors/unexpected/{kind}", kind))
                .andExpect(problem(500, "Internal server error"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test-errors/return-value", "/test-errors/legacy-return"})
    void treatsInvalidControllerReturnValuesAsServerFailures(String path) throws Exception {
        mvc.perform(get(path)).andExpect(problem(500, "Internal server error"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void preservesFrameworkMethodAndMediaTypeErrors() throws Exception {
        mvc.perform(delete("/api/v1/cloud-accounts/1"))
                .andExpect(problem(405, "Method Not Allowed"))
                .andExpect(header().exists("Allow"));
        mvc.perform(post("/api/v1/cloud-accounts").contentType(MediaType.TEXT_PLAIN).content("body"))
                .andExpect(problem(415, "Unsupported Media Type"));
    }

    @Test
    void leavesAccountUnknownPropertyBehaviorUnchanged() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(ACCOUNT_JSON);
        body.put("unexpected", "still ignored for existing account DTOs");
        mvc.perform(post("/api/v1/cloud-accounts").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated());
    }

    private ResultMatcher problem(int expectedStatus, String message) {
        return result -> {
            assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
            String body = result.getResponse().getContentAsString();
            JsonNode json = mapper.readTree(body);
            assertThat(json.properties()).extracting(Map.Entry::getKey)
                    .containsExactly("timestamp", "status", "message", "errors");
            assertThat(json.get("status").asInt()).isEqualTo(expectedStatus);
            assertThat(json.get("message").asText()).isEqualTo(message);
            assertThat(json.get("errors").isObject()).isTrue();
            String timestamp = json.get("timestamp").asText();
            assertThat(timestamp).endsWith("Z");
            assertThat(Instant.parse(timestamp)).isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(1));
            assertThat(body).doesNotContain(PRIVATE_DETAILS, "secret_constraint", "database-secret", "stackTrace",
                    "org.hibernate", "java.lang", "dev.sami", "\"cause\"", "\"trace\"");
        };
    }

    @TestComponent
    @RestController
    static class ErrorEndpoints {
        private final Validator validator;

        ErrorEndpoints(Validator validator) { this.validator = validator; }

        @PostMapping("/test-errors/body")
        public void body(@Valid @RequestBody CloudResourceRequest request) {}

        @PutMapping("/test-errors/update")
        public void update(@Valid @RequestBody UpdateCloudResourceRequest request) {}

        @GetMapping("/test-errors/query")
        public void query(@Valid @ModelAttribute CloudResourceQuery query) {}

        @GetMapping("/test-errors/combined")
        public void combined(@Valid @ModelAttribute CloudResourceQuery query, @RequestParam @Min(1) int limit) {}

        @GetMapping("/test-errors/parameters/{resourceId}")
        public void parameters(@PathVariable("resourceId") @Positive Long id,
                               @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int maximum) {}

        @GetMapping("/test-errors/provider")
        public void provider(@RequestParam CloudProvider provider) {}

        @GetMapping("/test-errors/domain/{kind}")
        public void domain(@PathVariable String kind) {
            if (kind.equals("resource-missing")) throw new CloudResourceNotFoundException(42L);
            throw new DuplicateCloudResourceException(7L, "resource-1", new DataIntegrityViolationException(PRIVATE_DETAILS));
        }

        @GetMapping("/test-errors/service-validation")
        public void serviceValidation() {
            throw new ConstraintViolationException(validator.validate(
                    new CloudResourceQuery(null, null, null, null, null, -1, 20, null)));
        }

        @GetMapping("/test-errors/unexpected/{kind}")
        public void unexpected(@PathVariable String kind) {
            if (kind.equals("integrity")) throw new DataIntegrityViolationException(PRIVATE_DETAILS);
            if (kind.equals("illegal-argument")) throw new IllegalArgumentException(PRIVATE_DETAILS);
            throw new IllegalStateException(PRIVATE_DETAILS, new RuntimeException("database-secret"));
        }

        @GetMapping("/test-errors/return-value")
        @NotNull
        public String invalidReturnValue() { return null; }
    }

    @TestComponent
    @RestController
    @Validated
    static class LegacyValidationEndpoints {
        @GetMapping("/test-errors/legacy/{id}")
        public void path(@PathVariable @Positive Long id) {}

        @GetMapping("/test-errors/legacy-return")
        @NotNull
        public String invalidReturnValue() { return null; }
    }
}
