package dev.sami.platform.exception;

import dev.sami.platform.model.CloudProvider;
public class DuplicateCloudAccountException extends RuntimeException {
    public DuplicateCloudAccountException(CloudProvider provider, String externalAccountId) {
        super("A " + provider + " account with ID " + externalAccountId + " already exists");
    }
}
