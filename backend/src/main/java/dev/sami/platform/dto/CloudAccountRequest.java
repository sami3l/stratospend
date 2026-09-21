package dev.sami.platform.dto;

import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CloudAccountRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull CloudProvider provider,
        @NotBlank @Size(max = 120)
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$", message = "must contain only letters, numbers, dots, colons, underscores or hyphens")
        String externalAccountId,
        @NotNull CloudEnvironment environment,
        @NotBlank @Size(max = 40)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "must be a valid cloud region")
        String region) {}
