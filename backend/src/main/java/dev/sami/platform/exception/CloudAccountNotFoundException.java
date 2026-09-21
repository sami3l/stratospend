package dev.sami.platform.exception;

public class CloudAccountNotFoundException extends RuntimeException {
    public CloudAccountNotFoundException(Long id) { super("Cloud account not found: " + id); }
}
