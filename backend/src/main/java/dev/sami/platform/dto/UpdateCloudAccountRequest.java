package dev.sami.platform.dto;

import dev.sami.platform.model.CloudEnvironment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCloudAccountRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull CloudEnvironment environment,
        @NotBlank @Size(max = 40)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "must be a valid cloud region")
        String region) {}
