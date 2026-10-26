package dev.sami.platform.service;

import dev.sami.platform.dto.DemoDataSet;
import dev.sami.platform.repository.CloudAccountRepository;
import dev.sami.platform.repository.CloudResourceRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds missing demo accounts and resources. Existing records are reused as they are, never modified or
 * deleted, so running the import again only adds what is missing and keeps any edits.
 */
@Service
public class DemoDataService {
    private final CloudAccountService accountService;
    private final CloudResourceService resourceService;
    private final CloudAccountRepository accountRepository;
    private final CloudResourceRepository resourceRepository;
    private final Validator validator;

    public DemoDataService(CloudAccountService accountService, CloudResourceService resourceService,
                           CloudAccountRepository accountRepository, CloudResourceRepository resourceRepository,
                           Validator validator) {
        this.accountService = accountService;
        this.resourceService = resourceService;
        this.accountRepository = accountRepository;
        this.resourceRepository = resourceRepository;
        this.validator = validator;
    }

    public record ImportSummary(int accountsCreated, int accountsExisting, int resourcesCreated, int resourcesExisting) {}

    /** Imports everything or nothing: an invalid entry rolls back the records created before it. */
    @Transactional
    public ImportSummary importData(DemoDataSet data) {
        int accountsCreated = 0;
        int accountsExisting = 0;
        int resourcesCreated = 0;
        int resourcesExisting = 0;

        for (var account : data.accounts()) {
            var request = account.toRequest();
            // CloudAccountService relies on controller validation, so validate here before creating.
            var violations = validator.validate(request);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }
            var existing = accountRepository.findByProviderAndExternalAccountId(
                    request.provider(), request.externalAccountId());
            Long accountId;
            if (existing.isPresent()) {
                accountId = existing.get().getId();
                accountsExisting++;
            } else {
                accountId = accountService.create(request).id();
                accountsCreated++;
            }

            for (var resource : account.resources()) {
                // Provider identifiers are case-sensitive and stored exactly, as in CloudResourceService.
                if (resourceRepository.existsByCloudAccountIdAndExternalResourceId(accountId,
                        resource.externalResourceId())) {
                    resourcesExisting++;
                } else {
                    resourceService.create(resource.toRequest(accountId));
                    resourcesCreated++;
                }
            }
        }
        return new ImportSummary(accountsCreated, accountsExisting, resourcesCreated, resourcesExisting);
    }
}
