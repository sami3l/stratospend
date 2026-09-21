package dev.sami.platform.service;

import dev.sami.platform.dto.CloudAccountRequest;
import dev.sami.platform.dto.CloudAccountResponse;
import dev.sami.platform.dto.UpdateCloudAccountRequest;
import dev.sami.platform.exception.CloudAccountNotFoundException;
import dev.sami.platform.exception.DuplicateCloudAccountException;
import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.repository.CloudAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CloudAccountService {
    private final CloudAccountRepository repository;

    public CloudAccountService(CloudAccountRepository repository) {
        this.repository = repository;
    }

    public List<CloudAccountResponse> findAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(CloudAccountResponse::from)
                .toList();
    }

    public CloudAccountResponse findById(Long id) {
        return CloudAccountResponse.from(requireAccount(id));
    }

    @Transactional
    public CloudAccountResponse create(CloudAccountRequest request) {
        if (repository.existsByProviderAndExternalAccountId(request.provider(), request.externalAccountId())) {
            throw new DuplicateCloudAccountException(request.provider(), request.externalAccountId());
        }
        var account = new CloudAccount(request.name().trim(), request.provider(),
                request.externalAccountId().trim(), request.environment(), request.region().trim());
        return CloudAccountResponse.from(repository.save(account));
    }

    @Transactional
    public CloudAccountResponse update(Long id, UpdateCloudAccountRequest request) {
        var account = requireAccount(id);
        account.update(request.name().trim(), request.environment(), request.region().trim());
        return CloudAccountResponse.from(account);
    }

    @Transactional
    public CloudAccountResponse changeStatus(Long id, boolean active) {
        var account = requireAccount(id);
        account.setActive(active);
        return CloudAccountResponse.from(account);
    }

    private CloudAccount requireAccount(Long id) {
        return repository.findById(id).orElseThrow(() -> new CloudAccountNotFoundException(id));
    }
}
