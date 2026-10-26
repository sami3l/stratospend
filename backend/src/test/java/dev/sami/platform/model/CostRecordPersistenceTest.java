package dev.sami.platform.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:cost-record-persistence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CostRecordPersistenceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired TestEntityManager entityManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void persistsCostWithResourceAndExactAmount() {
        var account = persistAccount("account-1");
        var resource = persistResource(account, "resource-1");
        var largest = new BigDecimal("9999999999999.999999");
        var cost = entityManager.persistAndFlush(new CostRecord(account, resource, "EC2", DAY, largest, "USD"));
        var costId = cost.getId();
        entityManager.clear();

        var loaded = entityManager.find(CostRecord.class, costId);

        assertThat(loaded.getCloudAccount().getId()).isEqualTo(account.getId());
        assertThat(loaded.getCloudResource().getId()).isEqualTo(resource.getId());
        assertThat(loaded.getProviderService()).isEqualTo("EC2");
        assertThat(loaded.getUsageDate()).isEqualTo(DAY);
        assertThat(loaded.getAmount()).isEqualByComparingTo(largest);
        assertThat(loaded.getCurrency()).isEqualTo("USD");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
    }

    @Test
    void persistsCostWithoutResourceIncludingTinyAndNegativeAmounts() {
        var account = persistAccount("account-1");
        var tiny = entityManager.persistAndFlush(
                new CostRecord(account, null, "Support", DAY, new BigDecimal("0.000001"), "USD"));
        var credit = entityManager.persistAndFlush(
                new CostRecord(account, null, "Credits", DAY, new BigDecimal("-25.50"), "USD"));
        entityManager.clear();

        var loadedTiny = entityManager.find(CostRecord.class, tiny.getId());
        assertThat(loadedTiny.getCloudResource()).isNull();
        assertThat(loadedTiny.getAmount()).isEqualByComparingTo("0.000001");
        assertThat(entityManager.find(CostRecord.class, credit.getId()).getAmount()).isEqualByComparingTo("-25.5");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cloud_resource_id FROM cost_records WHERE id = ?", Long.class, tiny.getId())).isNull();
    }

    @Test
    void replacesAmountWhilePreservingKeyAndCreationTime() {
        var account = persistAccount("account-1");
        var cost = entityManager.persistAndFlush(new CostRecord(account, null, "EC2", DAY, BigDecimal.TEN, "USD"));
        var costId = cost.getId();
        var oldTimestamp = Instant.parse("2020-01-01T00:00:00Z");
        jdbcTemplate.update("UPDATE cost_records SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(oldTimestamp), Timestamp.from(oldTimestamp), costId);
        entityManager.clear();

        entityManager.find(CostRecord.class, costId).replaceAmount(new BigDecimal("12.345678"));
        entityManager.flush();
        entityManager.clear();

        var updated = entityManager.find(CostRecord.class, costId);
        assertThat(updated.getAmount()).isEqualByComparingTo("12.345678");
        assertThat(updated.getCreatedAt()).isEqualTo(oldTimestamp);
        assertThat(updated.getUpdatedAt()).isAfter(oldTimestamp);
        assertThat(updated.getProviderService()).isEqualTo("EC2");
        assertThat(updated.getUsageDate()).isEqualTo(DAY);
    }

    @Test
    void rejectsDuplicateKeyWithResource() {
        var account = persistAccount("account-1");
        var resource = persistResource(account, "resource-1");
        entityManager.persistAndFlush(new CostRecord(account, resource, "EC2", DAY, BigDecimal.ONE, "USD"));

        assertThatThrownBy(() -> insertCost(account.getId(), resource.getId(), "EC2", DAY, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateKeyWithoutResource() {
        var account = persistAccount("account-1");
        entityManager.persistAndFlush(new CostRecord(account, null, "Support", DAY, BigDecimal.ONE, "USD"));

        // Plain UNIQUE would treat the two empty resources as different values and accept this row.
        assertThatThrownBy(() -> insertCost(account.getId(), null, "Support", DAY, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsCostsThatDifferInAnyKeyColumn() {
        var account = persistAccount("account-1");
        var other = persistAccount("account-2");
        var first = persistResource(account, "resource-1");
        var second = persistResource(account, "resource-2");
        insertCost(account.getId(), first.getId(), "EC2", DAY, "USD");

        insertCost(account.getId(), second.getId(), "EC2", DAY, "USD");
        insertCost(account.getId(), null, "EC2", DAY, "USD");
        insertCost(account.getId(), first.getId(), "EBS", DAY, "USD");
        insertCost(account.getId(), first.getId(), "EC2", DAY.plusDays(1), "USD");
        insertCost(other.getId(), null, "EC2", DAY, "USD");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cost_records", Long.class)).isEqualTo(6);
    }

    @Test
    void rejectsResourceFromAnotherAccountInTheEntity() {
        var owner = persistAccount("account-1");
        var other = persistAccount("account-2");
        var resource = persistResource(owner, "resource-1");

        assertThatThrownBy(() -> new CostRecord(other, resource, "EC2", DAY, BigDecimal.ONE, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must belong to the cost record account");
    }

    @Test
    void rejectsResourceFromAnotherAccountInTheDatabase() {
        var owner = persistAccount("account-1");
        var other = persistAccount("account-2");
        var resource = persistResource(owner, "resource-1");

        assertThatThrownBy(() -> insertCost(other.getId(), resource.getId(), "EC2", DAY, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingOrUnknownAccountAndUnknownResource() {
        var account = persistAccount("account-1");

        assertThatThrownBy(() -> insertCost(null, null, "EC2", DAY, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCost(Long.MAX_VALUE, null, "EC2", DAY, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCost(account.getId(), Long.MAX_VALUE, "EC2", DAY, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"usd", "US", "U1D", "USDX", ""})
    void rejectsInvalidCurrencyCodes(String currency) {
        var account = persistAccount("account-1");

        assertThatThrownBy(() -> insertCost(account.getId(), null, "EC2", DAY, currency))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preventsDeletingResourceOrAccountWithCosts() {
        var account = persistAccount("account-1");
        var resource = persistResource(account, "resource-1");
        insertCost(account.getId(), resource.getId(), "EC2", DAY, "USD");

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM cloud_resources WHERE id = ?", resource.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM cloud_accounts WHERE id = ?", account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CloudAccount persistAccount(String externalAccountId) {
        return entityManager.persistAndFlush(new CloudAccount("Test account", CloudProvider.AWS,
                externalAccountId, CloudEnvironment.DEVELOPMENT, "eu-west-3"));
    }

    private CloudResource persistResource(CloudAccount account, String externalResourceId) {
        return entityManager.persistAndFlush(new CloudResource(account, externalResourceId, "Application server",
                ResourceCategory.COMPUTE, "EC2", "eu-west-1", ResourceStatus.ACTIVE));
    }

    private void insertCost(Long accountId, Long resourceId, String service, LocalDate day, String currency) {
        jdbcTemplate.update("""
                INSERT INTO cost_records
                    (cloud_account_id, cloud_resource_id, provider_service, usage_date, amount, currency,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 1.25, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, accountId, resourceId, service, Date.valueOf(day), currency);
    }
}
