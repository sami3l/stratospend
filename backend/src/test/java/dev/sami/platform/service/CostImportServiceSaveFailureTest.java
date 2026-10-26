package dev.sami.platform.service;

import dev.sami.platform.exception.ConcurrentCostImportException;
import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.repository.CloudAccountRepository;
import dev.sami.platform.repository.CloudResourceRepository;
import dev.sami.platform.repository.CostRecordRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Two imports can insert the same new key at the same moment; the database constraint decides. */
class CostImportServiceSaveFailureTest {
    private static final String CSV = """
            date,provider,account_id,service,resource_id,amount,currency
            2026-10-01,AWS,111122223333,Support,,1,USD
            """;

    private final CloudAccountRepository accounts = mock(CloudAccountRepository.class);
    private final CostRecordRepository costs = mock(CostRecordRepository.class);
    private final CostImportService service = new CostImportService(new CostCsvReader(), accounts,
            mock(CloudResourceRepository.class), costs, "USD");

    @BeforeEach
    void setUp() {
        var account = new CloudAccount("Production AWS", CloudProvider.AWS, "111122223333",
                CloudEnvironment.PRODUCTION, "eu-west-3");
        ReflectionTestUtils.setField(account, "id", 7L);
        when(accounts.findAll()).thenReturn(List.of(account));
        when(costs.findByCloudAccountIdInAndUsageDateBetween(any(), any(), any())).thenReturn(List.of());
    }

    private static DataIntegrityViolationException violation(String constraint) {
        var cause = new ConstraintViolationException("duplicate key", new SQLException("duplicate key", "23505"),
                constraint);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    private void importCsv() {
        service.importCsv(new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void reportsAConcurrentImportWhenTheCostKeyConstraintFails() {
        var failure = violation("uk_cost_record_account_resource_service_date");
        doThrow(failure).when(costs).flush();

        assertThatThrownBy(this::importCsv)
                .isInstanceOf(ConcurrentCostImportException.class)
                .hasMessage("Cost records changed during the import. Please retry.")
                .hasCause(failure);
    }

    @Test
    void recognizesTheGeneratedIndexNameThatH2Reports() {
        var cause = new ConstraintViolationException("duplicate key", new SQLException("duplicate key", "23505", 23505),
                "PUBLIC.UK_COST_RECORD_ACCOUNT_RESOURCE_SERVICE_DATE_INDEX_4");
        doThrow(new DataIntegrityViolationException("could not execute statement", cause)).when(costs).flush();

        assertThatThrownBy(this::importCsv).isInstanceOf(ConcurrentCostImportException.class);
    }

    @Test
    void passesOnOtherIntegrityFailures() {
        var failure = violation("fk_cost_record_resource_account");
        doThrow(failure).when(costs).flush();

        assertThatThrownBy(this::importCsv).isSameAs(failure);
    }
}
