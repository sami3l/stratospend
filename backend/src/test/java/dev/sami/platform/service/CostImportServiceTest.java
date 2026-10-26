package dev.sami.platform.service;

import dev.sami.platform.dto.CostImportResponse;
import dev.sami.platform.dto.CostImportRowError;
import dev.sami.platform.exception.InvalidCostFileException;
import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import dev.sami.platform.repository.CloudAccountRepository;
import dev.sami.platform.repository.CloudResourceRepository;
import dev.sami.platform.repository.CostRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// Real Flyway schema (NULLS NOT DISTINCT, composite foreign key) on a database of its own, cleaned after each test.
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:cost-import-service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class CostImportServiceTest {
    private static final String HEADER = "date,provider,account_id,service,resource_id,amount,currency\n";

    @Autowired CostImportService service;
    @Autowired CloudAccountRepository accounts;
    @Autowired CloudResourceRepository resources;
    @Autowired CostRecordRepository costs;
    @Autowired JdbcTemplate jdbcTemplate;

    private CloudAccount aws;

    @BeforeEach
    void setUp() {
        aws = accounts.save(new CloudAccount("Production AWS", CloudProvider.AWS, "111122223333",
                CloudEnvironment.PRODUCTION, "eu-west-3"));
        var other = accounts.save(new CloudAccount("Other AWS", CloudProvider.AWS, "444455556666",
                CloudEnvironment.DEVELOPMENT, "eu-west-3"));
        accounts.save(new CloudAccount("Demo GCP", CloudProvider.GCP, "demo-project",
                CloudEnvironment.DEVELOPMENT, "europe-west1"));
        resources.save(new CloudResource(aws, "i-web-1", "Web server", ResourceCategory.COMPUTE, "EC2",
                "eu-west-3", ResourceStatus.ACTIVE));
        resources.save(new CloudResource(other, "i-other", "Other server", ResourceCategory.COMPUTE, "EC2",
                "eu-west-3", ResourceStatus.ACTIVE));
    }

    @AfterEach
    void clean() {
        costs.deleteAll();
        resources.deleteAll();
        accounts.deleteAll();
    }

    private CostImportResponse importCsv(String rows) {
        return service.importCsv(new ByteArrayInputStream((HEADER + rows).getBytes(StandardCharsets.UTF_8)));
    }

    private List<CostImportRowError> importErrors(String rows) {
        return catchThrowableOfType(InvalidCostFileException.class, () -> importCsv(rows)).getErrors();
    }

    private record StoredCost(String account, String resource, String service, LocalDate date, BigDecimal amount,
                              String currency) {}

    private List<StoredCost> storedCosts() {
        return jdbcTemplate.query("""
                SELECT a.external_account_id, r.external_resource_id, c.provider_service, c.usage_date, c.amount,
                       c.currency
                FROM cost_records c
                JOIN cloud_accounts a ON a.id = c.cloud_account_id
                LEFT JOIN cloud_resources r ON r.id = c.cloud_resource_id
                ORDER BY c.usage_date, c.provider_service
                """, (rs, n) -> new StoredCost(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getString(6)));
    }

    @Test
    void createsCostsWithAndWithoutAResource() {
        var result = importCsv("""
                2026-10-01,AWS,111122223333,EC2,i-web-1,12.345678,USD
                2026-10-01,GCP,demo-project,Support,,-3.50,USD
                """);

        assertThat(result).isEqualTo(new CostImportResponse(2, 2, 0, 0));
        assertThat(storedCosts()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("amount")
                .containsExactly(
                        new StoredCost("111122223333", "i-web-1", "EC2", LocalDate.of(2026, 10, 1), null, "USD"),
                        new StoredCost("demo-project", null, "Support", LocalDate.of(2026, 10, 1), null, "USD"));
        assertThat(storedCosts()).extracting(StoredCost::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("12.345678"), new BigDecimal("-3.5"));
    }

    @Test
    void reimportReplacesChangedAmountsAndCountsUnchangedRows() {
        importCsv("""
                2026-10-01,AWS,111122223333,EC2,i-web-1,10,USD
                2026-10-01,AWS,111122223333,Support,,5,USD
                """);

        // 5.000 equals 5: amounts are compared by value, not by how many decimals were written.
        var result = importCsv("""
                2026-10-01,AWS,111122223333,EC2,i-web-1,11.25,USD
                2026-10-01,AWS,111122223333,Support,,5.000,USD
                2026-10-02,AWS,111122223333,Support,,7,USD
                """);

        assertThat(result).isEqualTo(new CostImportResponse(3, 1, 1, 1));
        assertThat(costs.count()).isEqualTo(3);
        assertThat(storedCosts()).extracting(StoredCost::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("11.25"), new BigDecimal("5"), new BigDecimal("7"));
    }

    @Test
    void acceptsSurroundingSpacesAndTreatsABlankResourceAsNone() {
        var result = importCsv(" 2026-10-01 , AWS , 111122223333 , Data Transfer ,   , 0.10 , USD \n");

        assertThat(result.created()).isEqualTo(1);
        assertThat(storedCosts()).singleElement().satisfies(cost -> {
            assertThat(cost.resource()).isNull();
            assertThat(cost.service()).isEqualTo("Data Transfer");
        });
    }

    @ParameterizedTest(name = "{1}={2} -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            date         | date        | ''                    | is required
            date         | date        | 2026/10/01            | must be a date in YYYY-MM-DD format
            date         | date        | 2026-02-30            | must be a date in YYYY-MM-DD format
            provider     | provider    | ''                    | is required
            provider     | provider    | aws                   | must be one of: AWS, AZURE, GCP
            provider     | provider    | ORACLE                | must be one of: AWS, AZURE, GCP
            account_id   | account_id  | ''                    | is required
            account_id   | account_id  | 999999999999          | does not match a registered AWS account
            account_id   | account_id  | demo-project          | does not match a registered AWS account
            service      | service     | ''                    | is required
            service      | service     | =HYPERLINK("x")       | must not start with =, + or @
            service      | service     | +cmd                  | must not start with =, + or @
            service      | service     | @SUM(A1)              | must not start with =, + or @
            resource_id  | resource_id | i-unknown             | does not match a resource in account 111122223333
            resource_id  | resource_id | I-WEB-1               | does not match a resource in account 111122223333
            resource_id  | resource_id | i-other               | does not match a resource in account 111122223333
            resource_id  | resource_id | =1+1                  | must not start with =, + or @
            amount       | amount      | ''                    | is required
            amount       | amount      | 1e3                   | must be a plain decimal number such as 12.50
            amount       | amount      | '1,5'                 | must be a plain decimal number such as 12.50
            amount       | amount      | +5                    | must be a plain decimal number such as 12.50
            amount       | amount      | .5                    | must be a plain decimal number such as 12.50
            amount       | amount      | 12.3456789            | must have at most 6 decimal places
            amount       | amount      | 12345678901234        | must have at most 13 digits before the decimal point
            currency     | currency    | ''                    | is required
            currency     | currency    | EUR                   | must be USD
            currency     | currency    | usd                   | must be USD
            """)
    void rejectsInvalidValues(String field, String column, String value, String message) {
        var row = new String[] {"2026-10-01", "AWS", "111122223333", "EC2", "i-web-1", "12.50", "USD"};
        int index = List.of("date", "provider", "account_id", "service", "resource_id", "amount", "currency")
                .indexOf(field);
        row[index] = value.contains(",") || value.contains("\"") ? '"' + value.replace("\"", "\"\"") + '"' : value;

        assertThat(importErrors(String.join(",", row) + "\n"))
                .containsExactly(new CostImportRowError(2, column, message));
        assertThat(costs.count()).isZero();
    }

    @Test
    void limitsServiceNamesToEightyCharacters() {
        assertThat(importErrors("2026-10-01,AWS,111122223333," + "S".repeat(81) + ",,1,USD\n"))
                .containsExactly(new CostImportRowError(2, "service", "must be 80 characters or fewer"));
        assertThat(importCsv("2026-10-01,AWS,111122223333," + "S".repeat(80) + ",,1,USD\n").created()).isEqualTo(1);
    }

    @Test
    void rejectsFutureDatesAndAcceptsToday() {
        var today = LocalDate.now(ZoneOffset.UTC);

        assertThat(importErrors(today.plusDays(1) + ",AWS,111122223333,EC2,,1,USD\n"))
                .containsExactly(new CostImportRowError(2, "date", "must not be in the future"));
        assertThat(importCsv(today + ",AWS,111122223333,EC2,,1,USD\n").created()).isEqualTo(1);
    }

    @Test
    void reportsEveryProblemInOneRow() {
        assertThat(importErrors("2026-13-01,AWS,111122223333,,i-unknown,abc,EUR\n")).containsExactly(
                new CostImportRowError(2, "date", "must be a date in YYYY-MM-DD format"),
                new CostImportRowError(2, "service", "is required"),
                new CostImportRowError(2, "resource_id", "does not match a resource in account 111122223333"),
                new CostImportRowError(2, "amount", "must be a plain decimal number such as 12.50"),
                new CostImportRowError(2, "currency", "must be USD"));
    }

    @Test
    void rejectsTheSameKeyTwiceInOneFile() {
        assertThat(importErrors("""
                2026-10-01,AWS,111122223333,Support,,1,USD
                2026-10-01,AWS,111122223333,EC2,i-web-1,1,USD
                2026-10-01,AWS,111122223333,Support,  ,2,USD
                """)).containsExactly(new CostImportRowError(4, null,
                "repeats row 2: same date, account, service and resource"));
        assertThat(costs.count()).isZero();
    }

    @Test
    void savesNothingWhenAnyRowIsInvalid() {
        assertThatThrownBy(() -> importCsv("""
                2026-10-01,AWS,111122223333,EC2,i-web-1,1,USD
                2026-10-02,AWS,111122223333,EC2,i-web-1,1,USD
                2026-10-03,AWS,111122223333,EC2,i-web-1,not-a-number,USD
                """)).isInstanceOf(InvalidCostFileException.class).hasMessage("Cost file has 1 error");
        assertThat(costs.count()).isZero();
    }

    @Test
    void listsErrorsInRowOrderAndCapsTheList() {
        var rows = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            // Every tenth row has the wrong number of values, mixing reader and service errors.
            rows.append(i % 10 == 0 ? "2026-10-01,AWS\n" : "2026-10-01,AWS,111122223333,EC2,,x,USD\n");
        }

        var failure = catchThrowableOfType(InvalidCostFileException.class, () -> importCsv(rows.toString()));

        assertThat(failure).hasMessage("Cost file has 150 errors; the first 100 are listed");
        assertThat(failure.getTotalErrors()).isEqualTo(150);
        assertThat(failure.getErrors()).hasSize(CostImportService.MAX_LISTED_ERRORS)
                .extracting(CostImportRowError::row).isSorted().startsWith(2, 3).endsWith(101);
        assertThat(failure.getErrors().get(0)).isEqualTo(new CostImportRowError(2, null, "has 2 values; expected 7"));
        assertThat(failure.getErrors().get(1).column()).isEqualTo("amount");
    }

    @Test
    void acceptsCostsForInactiveAccounts() {
        aws.setActive(false);
        accounts.save(aws);

        assertThat(importCsv("2026-10-01,AWS,111122223333,EC2,i-web-1,1,USD\n").created()).isEqualTo(1);
    }

    @Test
    void rejectsAnInvalidConfiguredCurrency() {
        for (var invalid : new String[] {"usd", "US", "EURO", "", null}) {
            assertThatThrownBy(() -> new CostImportService(null, null, null, null, invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.cost.currency");
        }
    }
}
