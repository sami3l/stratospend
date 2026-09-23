package dev.sami.platform.repository;

import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:cloud-resource-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CloudResourceRepositoryTest {
    private static final String EXTERNAL_ID = "Resource-A/Instance:One";
    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired CloudResourceRepository repository;
    @Autowired TestEntityManager entityManager;

    private CloudAccount awsAccount;
    private CloudAccount otherAwsAccount;
    private CloudResource awsCompute;
    private CloudResource awsStorage;
    private CloudResource inactiveCompute;
    private CloudResource azureCompute;
    private CloudResource gcpDatabase;

    @BeforeEach
    void seedInventory() {
        awsAccount = account("AWS primary", CloudProvider.AWS);
        otherAwsAccount = account("AWS secondary", CloudProvider.AWS);
        var azureAccount = account("Azure", CloudProvider.AZURE);
        var gcpAccount = account("GCP", CloudProvider.GCP);
        awsCompute = resource(awsAccount, EXTERNAL_ID, "Shared", ResourceCategory.COMPUTE,
                "EC2", "eu-west-1", ResourceStatus.ACTIVE);
        awsStorage = resource(awsAccount, "bucket-1", "Bucket", ResourceCategory.STORAGE,
                "S3", "global", ResourceStatus.ACTIVE);
        inactiveCompute = resource(otherAwsAccount, "instance-2", "Shared", ResourceCategory.COMPUTE,
                "EC2", "eu-west-1", ResourceStatus.INACTIVE);
        azureCompute = resource(azureAccount, "azure-vm", "Shared", ResourceCategory.COMPUTE,
                "Virtual Machines", "eu-west-1", ResourceStatus.ACTIVE);
        gcpDatabase = resource(gcpAccount, "gcp-db", "Database", ResourceCategory.DATABASE,
                "Cloud SQL", "us-central1", ResourceStatus.UNKNOWN);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void detectsDuplicatesOnlyForExactExternalIdAndAccount() {
        assertThat(repository.existsByCloudAccountIdAndExternalResourceId(awsAccount.getId(), EXTERNAL_ID)).isTrue();
        assertThat(repository.existsByCloudAccountIdAndExternalResourceId(otherAwsAccount.getId(), EXTERNAL_ID)).isFalse();
        assertThat(repository.existsByCloudAccountIdAndExternalResourceId(awsAccount.getId(),
                EXTERNAL_ID.toLowerCase(Locale.ROOT))).isFalse();
        assertThat(repository.existsByCloudAccountIdAndExternalResourceId(awsAccount.getId(), "missing")).isFalse();
    }

    @Test
    void databaseRejectsDuplicateExternalIdInSameAccount() {
        assertThatThrownBy(() -> repository.saveAndFlush(newResource(awsAccount, EXTERNAL_ID)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameExternalIdInDifferentAccountsAndPreservesCase() {
        var anotherAccountResource = repository.saveAndFlush(newResource(otherAwsAccount, EXTERNAL_ID));
        var caseVariant = repository.saveAndFlush(newResource(awsAccount, EXTERNAL_ID.toLowerCase(Locale.ROOT)));
        entityManager.clear();

        assertThat(repository.findById(awsCompute.getId()).orElseThrow().getExternalResourceId()).isEqualTo(EXTERNAL_ID);
        assertThat(repository.findById(anotherAccountResource.getId()).orElseThrow().getExternalResourceId()).isEqualTo(EXTERNAL_ID);
        assertThat(repository.findById(caseVariant.getId()).orElseThrow().getExternalResourceId())
                .isEqualTo(EXTERNAL_ID.toLowerCase(Locale.ROOT));
    }

    @Test
    void filtersByAccount() {
        assertIds(repository.findByFilters(awsAccount.getId(), null, null, null, null, FIRST_PAGE), awsCompute, awsStorage);
    }

    @Test
    void filtersProviderThroughAccountsIncludingMultipleAccounts() {
        assertIds(repository.findByFilters(null, CloudProvider.AWS, null, null, null, FIRST_PAGE),
                awsCompute, awsStorage, inactiveCompute);
        assertIds(repository.findByFilters(null, CloudProvider.AZURE, null, null, null, FIRST_PAGE), azureCompute);
        assertIds(repository.findByFilters(null, CloudProvider.GCP, null, null, null, FIRST_PAGE), gcpDatabase);
    }

    @Test
    void filtersByCategory() {
        assertIds(repository.findByFilters(null, null, ResourceCategory.COMPUTE, null, null, FIRST_PAGE),
                awsCompute, inactiveCompute, azureCompute);
    }

    @Test
    void filtersByResourceRegionRatherThanAccountPrimaryRegion() {
        assertIds(repository.findByFilters(null, null, null, "eu-west-1", null, FIRST_PAGE),
                awsCompute, inactiveCompute, azureCompute);
        assertThat(repository.findByFilters(null, null, null, "eu-west-3", null, FIRST_PAGE)).isEmpty();
    }

    @Test
    void filtersByStatus() {
        assertIds(repository.findByFilters(null, null, null, null, ResourceStatus.INACTIVE, FIRST_PAGE), inactiveCompute);
        assertIds(repository.findByFilters(null, null, null, null, ResourceStatus.UNKNOWN, FIRST_PAGE), gcpDatabase);
    }

    @Test
    void combinesAllFiltersWithAnd() {
        assertIds(repository.findByFilters(awsAccount.getId(), CloudProvider.AWS, ResourceCategory.COMPUTE,
                "eu-west-1", ResourceStatus.ACTIVE, FIRST_PAGE), awsCompute);
        assertThat(repository.findByFilters(awsAccount.getId(), CloudProvider.GCP, null, null, null, FIRST_PAGE)).isEmpty();
        assertThat(repository.findByFilters(Long.MAX_VALUE, null, null, null, null, FIRST_PAGE)).isEmpty();
    }

    @Test
    void paginatesWithAccurateTotalsAndNoOverlappingRows() {
        var ids = new ArrayList<Long>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            var page = all(PageRequest.of(pageNumber, 2, Sort.by("id")));
            assertThat(page.getNumber()).isEqualTo(pageNumber);
            assertThat(page.getSize()).isEqualTo(2);
            assertThat(page.getTotalElements()).isEqualTo(5);
            assertThat(page.getTotalPages()).isEqualTo(3);
            assertThat(page.getContent()).hasSize(pageNumber < 2 ? 2 : 1);
            ids.addAll(page.map(CloudResource::getId).getContent());
        }
        assertThat(ids).containsExactly(awsCompute.getId(), awsStorage.getId(), inactiveCompute.getId(),
                azureCompute.getId(), gcpDatabase.getId());
        var beyondLastPage = all(PageRequest.of(3, 2));
        assertThat(beyondLastPage).isEmpty();
        assertThat(beyondLastPage.getTotalElements()).isEqualTo(5);
    }

    @Test
    void defaultsToNewestCreationThenDescendingIdWhenTimestampsTie() {
        entityManager.getEntityManager().createNativeQuery("UPDATE cloud_resources SET created_at = :timestamp")
                .setParameter("timestamp", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")))
                .executeUpdate();
        entityManager.clear();

        assertThat(all(PageRequest.of(0, 2)).map(CloudResource::getId).getContent())
                .containsExactly(gcpDatabase.getId(), azureCompute.getId());
        assertThat(all(PageRequest.of(1, 2)).map(CloudResource::getId).getContent())
                .containsExactly(inactiveCompute.getId(), awsStorage.getId());
    }

    @Test
    void appendsIdTieBreakerToCustomSortAcrossPages() {
        var ids = new ArrayList<Long>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            ids.addAll(all(PageRequest.of(pageNumber, 2, Sort.by("name"))).map(CloudResource::getId).getContent());
        }
        assertThat(ids).containsExactly(awsStorage.getId(), gcpDatabase.getId(), awsCompute.getId(),
                inactiveCompute.getId(), azureCompute.getId());
    }

    @Test
    void preservesExplicitIdSortDirection() {
        assertThat(all(PageRequest.of(0, 20, Sort.by("name").and(Sort.by(Sort.Order.desc("id")))))
                .map(CloudResource::getId).getContent())
                .containsExactly(awsStorage.getId(), gcpDatabase.getId(), azureCompute.getId(),
                        inactiveCompute.getId(), awsCompute.getId());
    }

    @Test
    void fetchesAccountsForPageInOneSelectPlusCountWithoutNPlusOne() {
        var statistics = entityManager.getEntityManager().getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        // All five rows reference four different accounts; four rows force a count query.
        var page = all(PageRequest.of(0, 4, Sort.by("id")));
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        entityManager.clear();

        assertThat(page.getContent()).allSatisfy(resource -> {
            assertThat(Hibernate.isInitialized(resource.getCloudAccount())).isTrue();
            assertThat(resource.getCloudAccount().getName()).isNotBlank();
            assertThat(resource.getCloudAccount().getProvider()).isNotNull();
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void fetchesAccountsWithProviderFilteredPageWithoutExtraQueries() {
        var statistics = entityManager.getEntityManager().getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var page = repository.findByFilters(null, CloudProvider.AWS, null, null, null,
                PageRequest.of(0, 2, Sort.by(Sort.Order.desc("id"))));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        entityManager.clear();

        assertThat(page.getContent()).extracting(resource -> resource.getCloudAccount().getName())
                .containsExactly("AWS secondary", "AWS primary");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void fetchesSingleResourceAndAccountInOneQuery() {
        var statistics = entityManager.getEntityManager().getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var resource = repository.findById(awsCompute.getId()).orElseThrow();
        entityManager.clear();

        assertThat(resource.getCloudAccount().getName()).isEqualTo("AWS primary");
        assertThat(resource.getCloudAccount().getProvider()).isEqualTo(CloudProvider.AWS);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void returnsEmptyForMissingResource() {
        assertThat(repository.findById(Long.MAX_VALUE)).isEmpty();
    }

    private Page<CloudResource> all(Pageable pageable) {
        return repository.findByFilters(null, null, null, null, null, pageable);
    }

    private void assertIds(Page<CloudResource> page, CloudResource... expected) {
        assertThat(page.getContent()).extracting(CloudResource::getId)
                .containsExactlyInAnyOrderElementsOf(List.of(expected).stream().map(CloudResource::getId).toList());
        assertThat(page.getTotalElements()).isEqualTo(expected.length);
    }

    private CloudAccount account(String name, CloudProvider provider) {
        return entityManager.persist(new CloudAccount(name, provider, name.replace(' ', '-'),
                CloudEnvironment.DEVELOPMENT, "eu-west-3"));
    }

    private CloudResource resource(CloudAccount account, String externalId, String name, ResourceCategory category,
                                   String service, String region, ResourceStatus status) {
        return entityManager.persist(new CloudResource(account, externalId, name, category, service, region, status));
    }

    private CloudResource newResource(CloudAccount account, String externalId) {
        return new CloudResource(account, externalId, "Another resource", ResourceCategory.COMPUTE,
                "EC2", "eu-west-1", ResourceStatus.ACTIVE);
    }
}
