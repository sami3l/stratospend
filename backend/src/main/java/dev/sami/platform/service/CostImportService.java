package dev.sami.platform.service;

import dev.sami.platform.common.UniqueConstraintViolations;
import dev.sami.platform.dto.CostImportResponse;
import dev.sami.platform.dto.CostImportRowError;
import dev.sami.platform.exception.ConcurrentCostImportException;
import dev.sami.platform.exception.InvalidCostFileException;
import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.model.CostRecord;
import dev.sami.platform.repository.CloudAccountRepository;
import dev.sami.platform.repository.CloudResourceRepository;
import dev.sami.platform.repository.CostRecordRepository;
import dev.sami.platform.service.CostCsvReader.CostCsvRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Imports daily costs from CSV. The whole file is validated first; if any row is invalid nothing is
 * saved. Otherwise each row creates a record, or replaces the amount of the record with the same key.
 */
@Service
public class CostImportService {
    static final int MAX_LISTED_ERRORS = 100;
    private static final String UNIQUE_CONSTRAINT = "uk_cost_record_account_resource_service_date";
    private static final int MAX_SERVICE_LENGTH = 80;
    private static final Pattern DECIMAL = Pattern.compile("-?(\\d+)(?:\\.(\\d+))?");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final String PROVIDERS = Arrays.stream(CloudProvider.values()).map(Enum::name)
            .collect(Collectors.joining(", "));

    private final CostCsvReader reader;
    private final CloudAccountRepository accountRepository;
    private final CloudResourceRepository resourceRepository;
    private final CostRecordRepository costRepository;
    private final String currency;

    public CostImportService(CostCsvReader reader, CloudAccountRepository accountRepository,
                             CloudResourceRepository resourceRepository, CostRecordRepository costRepository,
                             @Value("${app.cost.currency:USD}") String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("app.cost.currency must be a three-letter ISO 4217 code");
        }
        this.reader = reader;
        this.accountRepository = accountRepository;
        this.resourceRepository = resourceRepository;
        this.costRepository = costRepository;
        this.currency = currency;
    }

    private record AccountKey(CloudProvider provider, String externalAccountId) {}

    private record CostKey(Long accountId, Long resourceId, String service, LocalDate date) {}

    private record ValidRow(int row, CloudAccount account, CloudResource resource, String service,
                            LocalDate date, BigDecimal amount) {
        CostKey key() {
            return new CostKey(account.getId(), resource == null ? null : resource.getId(), service, date);
        }
    }

    @Transactional
    public CostImportResponse importCsv(InputStream input) {
        var rows = reader.read(input);
        var errors = new ImportErrors();
        var accounts = accountRepository.findAll().stream().collect(Collectors.toMap(
                account -> new AccountKey(account.getProvider(), account.getExternalAccountId()),
                Function.identity()));
        var resourcesByAccount = new HashMap<Long, Map<String, CloudResource>>();
        var firstRowByKey = new HashMap<CostKey, Integer>();
        var valid = new ArrayList<ValidRow>();

        for (var row : rows) {
            if (row.problem() != null) {
                errors.add(row.row(), null, row.problem());
                continue;
            }
            var parsed = validate(row, accounts, resourcesByAccount, errors);
            if (parsed == null) {
                continue;
            }
            var firstRow = firstRowByKey.putIfAbsent(parsed.key(), parsed.row());
            if (firstRow != null) {
                errors.add(parsed.row(), null,
                        "repeats row " + firstRow + ": same date, account, service and resource");
                continue;
            }
            valid.add(parsed);
        }
        if (errors.total > 0) {
            throw new InvalidCostFileException(errors.listed, errors.total);
        }
        return save(valid);
    }

    private ValidRow validate(CostCsvRow row, Map<AccountKey, CloudAccount> accounts,
                              Map<Long, Map<String, CloudResource>> resourcesByAccount, ImportErrors errors) {
        int number = row.row();
        int errorsBefore = errors.total;

        var date = parseDate(number, row.get("date").trim(), errors);
        var provider = parseProvider(number, row.get("provider").trim(), errors);

        var externalAccountId = row.get("account_id").trim();
        CloudAccount account = null;
        if (externalAccountId.isEmpty()) {
            errors.add(number, "account_id", "is required");
        } else if (provider != null) {
            account = accounts.get(new AccountKey(provider, externalAccountId));
            if (account == null) {
                errors.add(number, "account_id", "does not match a registered " + provider + " account");
            }
        }

        var service = row.get("service").trim();
        if (service.isEmpty()) {
            errors.add(number, "service", "is required");
        } else if (service.length() > MAX_SERVICE_LENGTH) {
            errors.add(number, "service", "must be " + MAX_SERVICE_LENGTH + " characters or fewer");
        } else if (startsLikeFormula(service)) {
            errors.add(number, "service", "must not start with =, + or @");
        }

        // Resource IDs are case-sensitive and matched exactly, as when resources are registered.
        var externalResourceId = row.get("resource_id");
        CloudResource resource = null;
        if (!externalResourceId.isBlank()) {
            if (startsLikeFormula(externalResourceId)) {
                errors.add(number, "resource_id", "must not start with =, + or @");
            } else if (account != null) {
                resource = resourcesByAccount.computeIfAbsent(account.getId(), this::resourcesOf)
                        .get(externalResourceId);
                if (resource == null) {
                    errors.add(number, "resource_id", "does not match a resource in account " + externalAccountId);
                }
            }
        }

        var amount = parseAmount(number, row.get("amount").trim(), errors);

        var rowCurrency = row.get("currency").trim();
        if (rowCurrency.isEmpty()) {
            errors.add(number, "currency", "is required");
        } else if (!rowCurrency.equals(currency)) {
            errors.add(number, "currency", "must be " + currency);
        }

        return errors.total > errorsBefore ? null : new ValidRow(number, account, resource, service, date, amount);
    }

    private Map<String, CloudResource> resourcesOf(Long accountId) {
        return resourceRepository.findByCloudAccountId(accountId).stream()
                .collect(Collectors.toMap(CloudResource::getExternalResourceId, Function.identity()));
    }

    private static LocalDate parseDate(int row, String value, ImportErrors errors) {
        if (value.isEmpty()) {
            errors.add(row, "date", "is required");
            return null;
        }
        try {
            // ISO_LOCAL_DATE is strict, so impossible dates such as 2026-02-30 are rejected.
            var date = LocalDate.parse(value);
            if (date.isAfter(LocalDate.now(ZoneOffset.UTC))) {
                errors.add(row, "date", "must not be in the future");
                return null;
            }
            return date;
        } catch (DateTimeParseException invalid) {
            errors.add(row, "date", "must be a date in YYYY-MM-DD format");
            return null;
        }
    }

    private static CloudProvider parseProvider(int row, String value, ImportErrors errors) {
        if (value.isEmpty()) {
            errors.add(row, "provider", "is required");
            return null;
        }
        try {
            return CloudProvider.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            errors.add(row, "provider", "must be one of: " + PROVIDERS);
            return null;
        }
    }

    private static BigDecimal parseAmount(int row, String value, ImportErrors errors) {
        if (value.isEmpty()) {
            errors.add(row, "amount", "is required");
            return null;
        }
        // Accept only plain decimals: PostgreSQL would silently round extra decimals, and 1e3 notation is ambiguous.
        var decimal = DECIMAL.matcher(value);
        if (!decimal.matches()) {
            errors.add(row, "amount", "must be a plain decimal number such as 12.50");
            return null;
        }
        if (decimal.group(1).length() > 13) {
            errors.add(row, "amount", "must have at most 13 digits before the decimal point");
            return null;
        }
        if (decimal.group(2) != null && decimal.group(2).length() > 6) {
            errors.add(row, "amount", "must have at most 6 decimal places");
            return null;
        }
        return new BigDecimal(value);
    }

    // Spreadsheets run cells starting with these characters as formulas when the data is exported later.
    private static boolean startsLikeFormula(String value) {
        return !value.isEmpty() && "=+@".indexOf(value.charAt(0)) >= 0;
    }

    private CostImportResponse save(List<ValidRow> rows) {
        var accountIds = rows.stream().map(row -> row.account().getId()).collect(Collectors.toSet());
        var from = rows.stream().map(ValidRow::date).min(Comparator.naturalOrder()).orElseThrow();
        var to = rows.stream().map(ValidRow::date).max(Comparator.naturalOrder()).orElseThrow();
        var existing = new HashMap<CostKey, CostRecord>();
        for (var record : costRepository.findByCloudAccountIdInAndUsageDateBetween(accountIds, from, to)) {
            var resource = record.getCloudResource();
            existing.put(new CostKey(record.getCloudAccount().getId(), resource == null ? null : resource.getId(),
                    record.getProviderService(), record.getUsageDate()), record);
        }

        int updated = 0;
        int unchanged = 0;
        var created = new ArrayList<CostRecord>();
        for (var row : rows) {
            var record = existing.get(row.key());
            if (record == null) {
                created.add(new CostRecord(row.account(), row.resource(), row.service(), row.date(), row.amount(),
                        currency));
            } else if (record.getAmount().compareTo(row.amount()) == 0) {
                unchanged++;
            } else {
                record.replaceAmount(row.amount());
                updated++;
            }
        }

        try {
            costRepository.saveAll(created);
            costRepository.flush();
        } catch (DataIntegrityViolationException failure) {
            // Another import inserted one of these keys after this import loaded the existing records.
            if (UniqueConstraintViolations.isViolationOf(failure, UNIQUE_CONSTRAINT)) {
                throw new ConcurrentCostImportException(failure);
            }
            throw failure;
        }
        return new CostImportResponse(rows.size(), created.size(), updated, unchanged);
    }

    /** Counts every error but keeps only the first ones, so a fully invalid file cannot produce a huge response. */
    private static final class ImportErrors {
        private final List<CostImportRowError> listed = new ArrayList<>();
        private int total;

        void add(int row, String column, String message) {
            total++;
            if (listed.size() < MAX_LISTED_ERRORS) {
                listed.add(new CostImportRowError(row, column, message));
            }
        }
    }
}
