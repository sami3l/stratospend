package dev.sami.platform.dto;

import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import java.time.Instant;

public record CloudResourceResponse(
        Long id,
        Long cloudAccountId,
        String cloudAccountName,
        CloudProvider provider,
        String externalResourceId,
        String name,
        ResourceCategory category,
        String providerService,
        String region,
        ResourceStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static CloudResourceResponse from(CloudResource resource) {
        var account = resource.getCloudAccount();
        return new CloudResourceResponse(
                resource.getId(), account.getId(), account.getName(), account.getProvider(),
                resource.getExternalResourceId(), resource.getName(), resource.getCategory(),
                resource.getProviderService(), resource.getRegion(), resource.getStatus(),
                resource.getCreatedAt(), resource.getUpdatedAt());
    }
}
