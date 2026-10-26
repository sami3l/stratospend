package dev.sami.platform.exception;

import dev.sami.platform.dto.CostImportRowError;

import java.util.List;

/** A cost file was rejected; nothing from it was saved. Lists the first errors and counts all of them. */
public class InvalidCostFileException extends RuntimeException {
    private final List<CostImportRowError> errors;
    private final int totalErrors;

    public InvalidCostFileException(List<CostImportRowError> errors, int totalErrors) {
        super(totalErrors == 1 ? "Cost file has 1 error"
                : totalErrors > errors.size()
                ? "Cost file has " + totalErrors + " errors; the first " + errors.size() + " are listed"
                : "Cost file has " + totalErrors + " errors");
        this.errors = List.copyOf(errors);
        this.totalErrors = totalErrors;
    }

    public InvalidCostFileException(CostImportRowError error) {
        this(List.of(error), 1);
    }

    public List<CostImportRowError> getErrors() { return errors; }
    public int getTotalErrors() { return totalErrors; }
}
