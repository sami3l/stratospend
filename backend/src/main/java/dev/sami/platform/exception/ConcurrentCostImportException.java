package dev.sami.platform.exception;

/** Another import created the same cost records while this one was running; retrying resolves it. */
public class ConcurrentCostImportException extends RuntimeException {
    public ConcurrentCostImportException(Throwable cause) {
        super("Cost records changed during the import. Please retry.", cause);
    }
}
