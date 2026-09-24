package dev.sami.platform.exception;

public class DuplicateCloudResourceException extends RuntimeException {
    public DuplicateCloudResourceException(Long cloudAccountId, String externalResourceId) {
        this(cloudAccountId, externalResourceId, null);
    }

    public DuplicateCloudResourceException(Long cloudAccountId, String externalResourceId, Throwable cause) {
        super("A resource with external ID " + externalResourceId
                + " already exists in cloud account " + cloudAccountId, cause);
    }
}
