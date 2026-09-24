package dev.sami.platform.service;

import dev.sami.platform.dto.CloudResourceQuery;
import dev.sami.platform.dto.CloudResourceRequest;
import dev.sami.platform.dto.CloudResourceResponse;
import dev.sami.platform.dto.PageResponse;
import dev.sami.platform.dto.UpdateCloudResourceRequest;
import dev.sami.platform.exception.CloudAccountNotFoundException;
import dev.sami.platform.exception.CloudResourceNotFoundException;
import dev.sami.platform.exception.DuplicateCloudResourceException;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.repository.CloudAccountRepository;
import dev.sami.platform.repository.CloudResourceRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class CloudResourceService {
    private static final String UNIQUE_CONSTRAINT = "uk_cloud_resource_account_external_id";
    private static final Pattern H2_UNIQUE_INDEX = Pattern.compile(UNIQUE_CONSTRAINT + "_index_[0-9a-f]+");

    private final CloudResourceRepository repository;
    private final CloudAccountRepository accountRepository;
    private final Validator validator;

    public CloudResourceService(CloudResourceRepository repository, CloudAccountRepository accountRepository,
                                Validator validator) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.validator = validator;
    }

    @Transactional
    public CloudResourceResponse create(CloudResourceRequest request) {
        validate(request);
        var account = accountRepository.findById(request.cloudAccountId())
                .orElseThrow(() -> new CloudAccountNotFoundException(request.cloudAccountId()));
        // Provider identifiers are case-sensitive: use the exact value for both checking and saving.
        if (repository.existsByCloudAccountIdAndExternalResourceId(account.getId(), request.externalResourceId())) {
            throw new DuplicateCloudResourceException(account.getId(), request.externalResourceId());
        }
        var resource = new CloudResource(account, request.externalResourceId(), request.name().trim(),
                request.category(), request.providerService().trim(), request.region(), request.status());
        return saveAndMap(resource);
    }

    public CloudResourceResponse findById(Long id) {
        return CloudResourceResponse.from(requireResource(id));
    }

    public PageResponse<CloudResourceResponse> findAll(CloudResourceQuery query) {
        validate(query);
        // JPA accepts an int offset even though Spring Data calculates it as a long.
        if ((long) query.page() * query.size() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Page offset must not exceed " + Integer.MAX_VALUE);
        }
        if (query.cloudAccountId() != null && !accountRepository.existsById(query.cloudAccountId())) {
            throw new CloudAccountNotFoundException(query.cloudAccountId());
        }

        Sort sort = Sort.unsorted();
        if (query.sort() != null) {
            var parts = query.sort().split(",");
            sort = Sort.by(Sort.Direction.fromString(parts[1]), parts[0]);
        }
        // The repository applies its specifications, fetches accounts, and adds the stable ID tie-breaker.
        var page = repository.findByFilters(query.cloudAccountId(), query.provider(), query.category(),
                query.region(), query.status(), PageRequest.of(query.page(), query.size(), sort));
        return new PageResponse<>(page.getContent().stream().map(CloudResourceResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional
    public CloudResourceResponse update(Long id, UpdateCloudResourceRequest request) {
        validate(request);
        var resource = requireResource(id);
        resource.update(request.name().trim(), request.category(), request.providerService().trim(),
                request.region(), request.status());
        return saveAndMap(resource);
    }

    private CloudResource requireResource(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Cloud resource ID must be positive");
        }
        return repository.findById(id).orElseThrow(() -> new CloudResourceNotFoundException(id));
    }

    private void validate(Object request) {
        if (request == null) {
            throw new IllegalArgumentException("Request must not be null");
        }
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private CloudResourceResponse saveAndMap(CloudResource resource) {
        try {
            return CloudResourceResponse.from(repository.saveAndFlush(resource));
        } catch (DataIntegrityViolationException failure) {
            if (isDuplicateResource(failure)) {
                throw new DuplicateCloudResourceException(resource.getCloudAccount().getId(),
                        resource.getExternalResourceId(), failure);
            }
            throw failure;
        }
    }

    private boolean isDuplicateResource(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState()) && violation.getConstraintName() != null) {
                // Normalize database identifier spelling only, never externalResourceId or exception message text.
                String name = violation.getConstraintName().replace("\"", "").trim().toLowerCase(Locale.ROOT);
                name = name.substring(name.lastIndexOf('.') + 1);
                if (UNIQUE_CONSTRAINT.equals(name)) {
                    return true;
                }
                // H2 reports the generated backing index; PostgreSQL reports the declared constraint itself.
                if (violation.getErrorCode() == 23505 && H2_UNIQUE_INDEX.matcher(name).matches()) {
                    return true;
                }
            }
        }
        return false;
    }
}
