package dev.sami.platform.repository;

import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.model.CloudProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {
    boolean existsByProviderAndExternalAccountId(CloudProvider provider, String externalAccountId);
    Optional<CloudAccount> findByProviderAndExternalAccountId(CloudProvider provider, String externalAccountId);
    List<CloudAccount> findAllByOrderByCreatedAtDesc();
}
