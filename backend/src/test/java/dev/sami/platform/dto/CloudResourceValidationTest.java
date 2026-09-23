package dev.sami.platform.dto;

import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CloudResourceValidationTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:lambda:eu-west-1:123456789012:function:MyFunction",
            "/subscriptions/Sub-A/resourceGroups/MyGroup/providers/Microsoft.Compute/virtualMachines/MyVM",
            "//compute.googleapis.com/projects/MyProject/zones/us-central1-a/instances/MyInstance",
            "  CaseSensitive/ID:AbC  "
    })
    void acceptsProviderIdentifiersWithoutChangingThem(String externalId) {
        var request = new CloudResourceRequest(1L, externalId, "Resource", ResourceCategory.COMPUTE,
                "Compute", "eu-west-1", ResourceStatus.ACTIVE);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.externalResourceId()).isEqualTo(externalId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"global", "eu-west-1", "us-central1", "westeurope"})
    void acceptsValidRegionsOnCreateAndUpdate(String region) {
        assertThat(validator.validate(new CloudResourceRequest(1L, "id", "Resource", ResourceCategory.COMPUTE,
                "Compute", region, ResourceStatus.ACTIVE))).isEmpty();
        assertThat(validator.validate(new UpdateCloudResourceRequest("Resource", ResourceCategory.COMPUTE,
                "Compute", region, ResourceStatus.ACTIVE))).isEmpty();
    }

    @Test
    void acceptsAllExistingEnumValues() {
        for (var category : ResourceCategory.values()) {
            for (var status : ResourceStatus.values()) {
                assertThat(validator.validate(new CloudResourceRequest(1L, "id", "Resource", category,
                        "Service", "global", status))).isEmpty();
                assertThat(validator.validate(new UpdateCloudResourceRequest("Resource", category,
                        "Service", "global", status))).isEmpty();
            }
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void requiresPositiveAccountIdOnCreate(Long accountId) {
        assertThat(invalidFields(new CloudResourceRequest(accountId, "id", "Resource", ResourceCategory.COMPUTE,
                "Compute", "global", ResourceStatus.ACTIVE))).containsExactly("cloudAccountId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingOrBlankRequiredStrings(String value) {
        assertThat(invalidFields(new CloudResourceRequest(1L, value, value, ResourceCategory.COMPUTE,
                value, value, ResourceStatus.ACTIVE)))
                .containsExactlyInAnyOrder("externalResourceId", "name", "providerService", "region");
        assertThat(invalidFields(new UpdateCloudResourceRequest(value, ResourceCategory.COMPUTE,
                value, value, ResourceStatus.ACTIVE)))
                .containsExactlyInAnyOrder("name", "providerService", "region");
    }

    @Test
    void acceptsExactDatabaseStringLimits() {
        assertThat(validator.validate(new CloudResourceRequest(1L, "X".repeat(512), "N".repeat(120),
                ResourceCategory.OTHER, "S".repeat(80), "r".repeat(40), ResourceStatus.UNKNOWN))).isEmpty();
        assertThat(validator.validate(new UpdateCloudResourceRequest("N".repeat(120), ResourceCategory.OTHER,
                "S".repeat(80), "r".repeat(40), ResourceStatus.UNKNOWN))).isEmpty();
    }

    @Test
    void rejectsStringsExceedingDatabaseLimits() {
        assertThat(invalidFields(new CloudResourceRequest(1L, "X".repeat(513), "N".repeat(121),
                ResourceCategory.OTHER, "S".repeat(81), "r".repeat(41), ResourceStatus.UNKNOWN)))
                .containsExactlyInAnyOrder("externalResourceId", "name", "providerService", "region");
        assertThat(invalidFields(new UpdateCloudResourceRequest("N".repeat(121), ResourceCategory.OTHER,
                "S".repeat(81), "r".repeat(41), ResourceStatus.UNKNOWN)))
                .containsExactlyInAnyOrder("name", "providerService", "region");
    }

    @Test
    void requiresCategoryAndStatusForBothRequests() {
        assertThat(invalidFields(new CloudResourceRequest(1L, "id", "Resource", null,
                "Compute", "global", null))).containsExactlyInAnyOrder("category", "status");
        assertThat(invalidFields(new UpdateCloudResourceRequest("Resource", null, "Compute", "global", null)))
                .containsExactlyInAnyOrder("category", "status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EU-WEST-1", "Global", "eu_west_1", "eu.west.1", "eu west 1", "global\n"})
    void rejectsInvalidRegionSyntax(String region) {
        assertThat(invalidFields(new CloudResourceRequest(1L, "id", "Resource", ResourceCategory.COMPUTE,
                "Compute", region, ResourceStatus.ACTIVE))).containsExactly("region");
        assertThat(invalidFields(new UpdateCloudResourceRequest("Resource", ResourceCategory.COMPUTE,
                "Compute", region, ResourceStatus.ACTIVE))).containsExactly("region");
    }

    @Test
    void defaultsQueryPaginationAndAllowsOmittedFilters() {
        var query = new CloudResourceQuery(null, null, null, null, null, null, null, null);

        assertThat(validator.validate(query)).isEmpty();
        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.sort()).isNull();
    }

    @Test
    void acceptsCombinedFiltersAndPaginationBoundaries() {
        var query = new CloudResourceQuery(1L, CloudProvider.AWS, ResourceCategory.SERVERLESS, "global",
                ResourceStatus.ACTIVE, 0, 100, "createdAt,desc");
        assertThat(validator.validate(query)).isEmpty();
        assertThat(validator.validate(new CloudResourceQuery(null, null, null, null, null, 1, 1, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void rejectsInvalidQueryPageSizes(int size) {
        assertThat(invalidFields(new CloudResourceQuery(null, null, null, null, null, 0, size, null)))
                .containsExactly("size");
    }

    @Test
    void rejectsNegativePageAndNonPositiveFilterAccount() {
        assertThat(invalidFields(new CloudResourceQuery(0L, null, null, null, null, -1, 20, null)))
                .containsExactlyInAnyOrder("cloudAccountId", "page");
        assertThat(invalidFields(new CloudResourceQuery(-1L, null, null, null, null, 0, 20, null)))
                .containsExactly("cloudAccountId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "EU-WEST-1", "eu_west_1"})
    void rejectsInvalidRegionFilters(String region) {
        assertThat(invalidFields(new CloudResourceQuery(null, null, null, region, null, null, null, null)))
                .containsExactly("region");
    }

    @Test
    void enforcesRegionFilterLength() {
        assertThat(validator.validate(new CloudResourceQuery(null, null, null, "r".repeat(40), null,
                null, null, null))).isEmpty();
        assertThat(invalidFields(new CloudResourceQuery(null, null, null, "r".repeat(41), null,
                null, null, null))).containsExactly("region");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "name", "category", "providerService", "region", "status", "createdAt", "updatedAt"})
    void acceptsApprovedSortFieldsInBothDirections(String field) {
        for (String direction : new String[]{"asc", "desc"}) {
            assertThat(validator.validate(new CloudResourceQuery(null, null, null, null, null, null, null,
                    field + "," + direction))).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "name", "name,ASC", "name,sideways", "name,asc,id,desc",
            "cloudAccount.provider,asc", "provider,asc", "externalResourceId,desc", "unknown,asc"})
    void rejectsUnapprovedOrMalformedSort(String sort) {
        assertThat(invalidFields(new CloudResourceQuery(null, null, null, null, null, null, null, sort)))
                .containsExactly("sort");
    }

    private Set<String> invalidFields(Object value) {
        return validator.validate(value).stream().map(ConstraintViolation::getPropertyPath)
                .map(Object::toString).collect(Collectors.toSet());
    }
}
