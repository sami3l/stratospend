package dev.sami.platform.exception;

public class CloudResourceNotFoundException extends RuntimeException {
    public CloudResourceNotFoundException(Long id) { super("Cloud resource not found: " + id); }
}
