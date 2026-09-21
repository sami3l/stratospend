package dev.sami.platform.dto;

import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import java.time.Instant;

public record CloudAccountResponse(
        Long id,
        String name,
        CloudProvider provider,
        String externalAccountId,
        CloudEnvironment environment,
        String region,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static CloudAccountResponse from(CloudAccount account) {
        return new CloudAccountResponse(
                account.getId(), account.getName(), account.getProvider(),
                account.getExternalAccountId(), account.getEnvironment(), account.getRegion(),
                account.isActive(), account.getCreatedAt(), account.getUpdatedAt());
    }
}
