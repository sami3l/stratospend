package dev.sami.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CloudResourceRequest(
        @NotNull @Positive Long cloudAccountId,
        @NotBlank @Size(max = 512) String externalResourceId,
        @NotBlank @Size(max = 120) String name,
        @NotNull ResourceCategory category,
        @NotBlank @Size(max = 80) String providerService,
        @NotBlank @Size(max = 40)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "must contain only lowercase letters, digits or hyphens")
        String region,
        @NotNull ResourceStatus status) {

    // Reject extra JSON properties even when the application mapper ignores unknown properties.
    @JsonAnySetter
    public void rejectUnknownProperty(String property, JsonNode value) {
        throw new IllegalArgumentException("Unknown or immutable property: " + property);
    }
}
