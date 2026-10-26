package dev.sami.platform.repository;

import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.model.CostRecord;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:cost-record-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CostRecordRepositoryTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired TestEntityManager entityManager;
    @Autowired CostRecordRepository repository;

    private CloudAccount account;
    private CloudResource resource;
    private CostRecord withResource;
    private CostRecord withoutResource;

    @BeforeEach
    void setUp() {
        account = entityManager.persist(new CloudAccount("Test account", CloudProvider.AWS, "account-1",
                CloudEnvironment.DEVELOPMENT, "eu-west-3"));
        resource = entityManager.persist(new CloudResource(account, "resource-1", "Application server",
                ResourceCategory.COMPUTE, "EC2", "eu-west-1", ResourceStatus.ACTIVE));
        // Same account, service and day: only the resource differs, so a lookup must not confuse them.
        withResource = entityManager.persist(new CostRecord(account, resource, "EC2", DAY, BigDecimal.ONE, "USD"));
        withoutResource = entityManager.persist(new CostRecord(account, null, "EC2", DAY, BigDecimal.TEN, "USD"));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void findsTheRecordWithTheGivenResource() {
        assertThat(repository.findByKey(account.getId(), resource.getId(), "EC2", DAY))
                .get().extracting(CostRecord::getId).isEqualTo(withResource.getId());
    }

    @Test
    void findsOnlyTheRecordWithoutAResourceWhenNoResourceIsGiven() {
        assertThat(repository.findByKey(account.getId(), null, "EC2", DAY))
                .get().extracting(CostRecord::getId).isEqualTo(withoutResource.getId());
    }

    @Test
    void findsNothingWhenAnyKeyPartDiffers() {
        var otherAccount = entityManager.persistAndFlush(new CloudAccount("Other account", CloudProvider.AWS,
                "account-2", CloudEnvironment.DEVELOPMENT, "eu-west-3"));

        assertThat(repository.findByKey(otherAccount.getId(), null, "EC2", DAY)).isEmpty();
        assertThat(repository.findByKey(account.getId(), null, "EBS", DAY)).isEmpty();
        assertThat(repository.findByKey(account.getId(), null, "ec2", DAY)).isEmpty();
        assertThat(repository.findByKey(account.getId(), null, "EC2", DAY.plusDays(1))).isEmpty();
        assertThat(repository.findByKey(account.getId(), resource.getId(), "EC2", DAY.minusDays(1))).isEmpty();
        assertThat(repository.findByKey(account.getId(), Long.MAX_VALUE, "EC2", DAY)).isEmpty();
    }
}
