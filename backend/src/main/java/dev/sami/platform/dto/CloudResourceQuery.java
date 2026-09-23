package dev.sami.platform.dto;

import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CloudResourceQuery(
        @Positive Long cloudAccountId,
        CloudProvider provider,
        ResourceCategory category,
        @Size(max = 40)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "must contain only lowercase letters, digits or hyphens")
        String region,
        ResourceStatus status,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size,
        @Pattern(regexp = "^(id|name|category|providerService|region|status|createdAt|updatedAt),(asc|desc)$",
                message = "must use an approved field followed by ,asc or ,desc")
        String sort) {

    public CloudResourceQuery {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        // A missing sort retains the repository default: createdAt DESC, id DESC.
    }
}
