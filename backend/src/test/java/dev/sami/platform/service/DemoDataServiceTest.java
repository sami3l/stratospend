package dev.sami.platform.service;

import dev.sami.platform.dto.CloudAccountRequest;
import dev.sami.platform.dto.CloudResourceQuery;
import dev.sami.platform.dto.CloudResourceResponse;
import dev.sami.platform.dto.DemoDataSet;
import dev.sami.platform.dto.DemoDataSet.DemoAccount;
import dev.sami.platform.dto.DemoDataSet.DemoResource;
import dev.sami.platform.dto.UpdateCloudResourceRequest;
import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import dev.sami.platform.repository.CloudAccountRepository;
import dev.sami.platform.repository.CloudResourceRepository;
import dev.sami.platform.service.DemoDataService.ImportSummary;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The import commits its own transaction, so this class uses a separate database and cleans up after each test.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:demo-data-service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class DemoDataServiceTest {
    @Autowired DemoDataService service;
    @Autowired CloudAccountService accountService;
    @Autowired CloudResourceService resourceService;
    @Autowired CloudAccountRepository accounts;
    @Autowired CloudResourceRepository resources;

    @AfterEach
    void clean() {
        resources.deleteAll();
        accounts.deleteAll();
    }

    private static DemoResource resource(String externalId, String name) {
        return new DemoResource(externalId, name, ResourceCategory.COMPUTE, "EC2", "eu-west-3", ResourceStatus.ACTIVE);
    }

    private static DemoAccount account(CloudProvider provider, String externalId, String region,
                                       List<DemoResource> demoResources) {
        return new DemoAccount("Demo " + provider, provider, externalId, CloudEnvironment.DEVELOPMENT, region,
                demoResources);
    }

    private static DemoDataSet sample() {
        return new DemoDataSet(List.of(
                account(CloudProvider.AWS, "111122223333", "eu-west-3",
                        List.of(resource("i-web-1", "Web server 1"), resource("i-web-2", "Web server 2"))),
                account(CloudProvider.GCP, "demo-project", "europe-west1",
                        List.of(resource("bucket-1", "Data bucket")))));
    }

    private List<CloudResourceResponse> allResources(Long cloudAccountId) {
        return resourceService.findAll(new CloudResourceQuery(cloudAccountId, null, null, null, null, 0, 100, "name,asc"))
                .content();
    }

    @Test
    void importsEveryAccountAndResourceIntoAnEmptyDatabase() {
        assertThat(service.importData(sample())).isEqualTo(new ImportSummary(2, 0, 3, 0));

        assertThat(accountService.findAll()).extracting("name", "provider", "externalAccountId")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Demo AWS", CloudProvider.AWS, "111122223333"),
                        org.assertj.core.groups.Tuple.tuple("Demo GCP", CloudProvider.GCP, "demo-project"));
        assertThat(allResources(null)).extracting("name", "cloudAccountName", "externalResourceId")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Data bucket", "Demo GCP", "bucket-1"),
                        org.assertj.core.groups.Tuple.tuple("Web server 1", "Demo AWS", "i-web-1"),
                        org.assertj.core.groups.Tuple.tuple("Web server 2", "Demo AWS", "i-web-2"));
    }

    @Test
    void secondImportCreatesNothingAndAddsNoDuplicates() {
        service.importData(sample());

        assertThat(service.importData(sample())).isEqualTo(new ImportSummary(0, 2, 0, 3));
        assertThat(accounts.count()).isEqualTo(2);
        assertThat(resources.count()).isEqualTo(3);
    }

    @Test
    void addsOnlyTheResourcesThatAreMissing() {
        service.importData(new DemoDataSet(List.of(account(CloudProvider.AWS, "111122223333", "eu-west-3",
                List.of(resource("i-web-1", "Web server 1"))))));

        assertThat(service.importData(sample())).isEqualTo(new ImportSummary(1, 1, 2, 1));
        assertThat(resources.count()).isEqualTo(3);
    }

    @Test
    void reusesAnExistingAccountWithoutChangingIt() {
        var own = accountService.create(new CloudAccountRequest("My own AWS", CloudProvider.AWS, "111122223333",
                CloudEnvironment.PRODUCTION, "us-east-1"));

        assertThat(service.importData(sample())).isEqualTo(new ImportSummary(1, 1, 3, 0));
        var reused = accountService.findById(own.id());
        assertThat(reused.name()).isEqualTo("My own AWS");
        assertThat(reused.environment()).isEqualTo(CloudEnvironment.PRODUCTION);
        assertThat(reused.region()).isEqualTo("us-east-1");
        assertThat(allResources(own.id())).extracting("externalResourceId").containsExactly("i-web-1", "i-web-2");
    }

    @Test
    void rejectsExternalAccountIdsThatTheApiWouldReject() {
        var padded = new DemoDataSet(List.of(account(CloudProvider.AWS, "  111122223333  ", "eu-west-3",
                List.of(resource("i-web-1", "Web server 1")))));

        assertThatThrownBy(() -> service.importData(padded))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("externalAccountId");
        assertThat(accounts.count()).isZero();
    }

    @Test
    void keepsEditsMadeToImportedResources() {
        service.importData(sample());
        var webServer = allResources(null).stream().filter(r -> r.externalResourceId().equals("i-web-1")).findFirst()
                .orElseThrow();
        resourceService.update(webServer.id(), new UpdateCloudResourceRequest("Edited web server",
                ResourceCategory.CONTAINER, "ECS", "global", ResourceStatus.INACTIVE));

        service.importData(sample());

        var afterReimport = resourceService.findById(webServer.id());
        assertThat(afterReimport.name()).isEqualTo("Edited web server");
        assertThat(afterReimport.category()).isEqualTo(ResourceCategory.CONTAINER);
        assertThat(afterReimport.status()).isEqualTo(ResourceStatus.INACTIVE);
    }

    @Test
    void rollsBackEverythingWhenAnAccountIsInvalid() {
        var data = new DemoDataSet(List.of(
                sample().accounts().get(0),
                account(CloudProvider.AZURE, "azure-subscription", "West Europe", List.of())));

        assertThatThrownBy(() -> service.importData(data))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("region");
        assertThat(accounts.count()).isZero();
        assertThat(resources.count()).isZero();
    }

    @Test
    void rollsBackEverythingWhenAResourceIsInvalid() {
        var invalid = new DemoResource("i-bad", "Bad region", ResourceCategory.COMPUTE, "EC2", "EU West",
                ResourceStatus.ACTIVE);
        var data = new DemoDataSet(List.of(account(CloudProvider.AWS, "111122223333", "eu-west-3",
                List.of(resource("i-web-1", "Web server 1"), invalid))));

        assertThatThrownBy(() -> service.importData(data)).isInstanceOf(ConstraintViolationException.class);
        assertThat(accounts.count()).isZero();
        assertThat(resources.count()).isZero();
    }

    @Test
    void acceptsAccountsWithoutResourcesAndEmptyDataSets() {
        assertThat(service.importData(new DemoDataSet(null))).isEqualTo(new ImportSummary(0, 0, 0, 0));
        assertThat(service.importData(new DemoDataSet(List.of(
                account(CloudProvider.GCP, "empty-project", "global", null)))))
                .isEqualTo(new ImportSummary(1, 0, 0, 0));
        assertThat(accounts.count()).isEqualTo(1);
    }
}
