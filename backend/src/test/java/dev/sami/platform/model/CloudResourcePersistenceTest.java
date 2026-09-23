package dev.sami.platform.model;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:cloud-resource-persistence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CloudResourcePersistenceTest {
    @Autowired TestEntityManager entityManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void persistsResourceWithAccountAndTimestamps() {
        var account = persistAccount("account-1");
        var resource = entityManager.persistAndFlush(resource(account));
        var resourceId = resource.getId();
        var accountId = account.getId();
        entityManager.clear();

        var loaded = entityManager.find(CloudResource.class, resourceId);

        assertThat(loaded.getCloudAccount().getId()).isEqualTo(accountId);
        assertThat(loaded.getExternalResourceId()).isEqualTo("resource-1");
        assertThat(loaded.getName()).isEqualTo("Application server");
        assertThat(loaded.getCategory()).isEqualTo(ResourceCategory.COMPUTE);
        assertThat(loaded.getProviderService()).isEqualTo("EC2");
        assertThat(loaded.getRegion()).isEqualTo("eu-west-1");
        assertThat(loaded.getStatus()).isEqualTo(ResourceStatus.ACTIVE);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT category FROM cloud_resources WHERE id = ?", String.class, resourceId))
                .isEqualTo("COMPUTE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM cloud_resources WHERE id = ?", String.class, resourceId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void updatesTimestampWhilePreservingCreationTime() {
        var resource = entityManager.persistAndFlush(resource(persistAccount("account-1")));
        var resourceId = resource.getId();
        var oldTimestamp = Instant.parse("2020-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE cloud_resources SET created_at = ?, updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(oldTimestamp), java.sql.Timestamp.from(oldTimestamp), resourceId);
        entityManager.clear();

        var loaded = entityManager.find(CloudResource.class, resourceId);
        loaded.update("Stopped server", ResourceCategory.COMPUTE, "EC2", "eu-west-1", ResourceStatus.INACTIVE);
        entityManager.flush();
        entityManager.clear();

        var updated = entityManager.find(CloudResource.class, resourceId);
        assertThat(updated.getCreatedAt()).isEqualTo(oldTimestamp);
        assertThat(updated.getUpdatedAt()).isAfter(oldTimestamp);
        assertThat(updated.getStatus()).isEqualTo(ResourceStatus.INACTIVE);
    }

    @Test
    void allowsSameExternalIdInDifferentAccounts() {
        var first = entityManager.persistAndFlush(resource(persistAccount("account-1")));
        var second = entityManager.persistAndFlush(resource(persistAccount("account-2")));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void rejectsDuplicateExternalIdWithinAccount() {
        var account = persistAccount("account-1");
        entityManager.persistAndFlush(resource(account));

        assertThatThrownBy(() -> insertResource(account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingOrUnknownAccount() {
        assertThatThrownBy(() -> insertResource(null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertResource(Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preventsDeletingAccountWithResources() {
        var account = persistAccount("account-1");
        entityManager.persistAndFlush(resource(account));

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM cloud_accounts WHERE id = ?", account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingResourcePreservesAccount() {
        var account = persistAccount("account-1");
        var accountId = account.getId();
        var resource = entityManager.persistAndFlush(resource(account));

        entityManager.remove(resource);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(CloudAccount.class, accountId)).isNotNull();
    }

    private CloudAccount persistAccount(String externalAccountId) {
        return entityManager.persistAndFlush(new CloudAccount("Test account", CloudProvider.AWS,
                externalAccountId, CloudEnvironment.DEVELOPMENT, "eu-west-3"));
    }

    private CloudResource resource(CloudAccount account) {
        return new CloudResource(account, "resource-1", "Application server", ResourceCategory.COMPUTE,
                "EC2", "eu-west-1", ResourceStatus.ACTIVE);
    }

    private void insertResource(Long accountId) {
        jdbcTemplate.update("""
                INSERT INTO cloud_resources
                    (cloud_account_id, external_resource_id, name, category, provider_service,
                     region, status, created_at, updated_at)
                VALUES (?, 'resource-1', 'Duplicate server', 'COMPUTE', 'EC2',
                        'eu-west-1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, accountId);
    }
}
