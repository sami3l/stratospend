package dev.sami.platform.common;

import dev.sami.platform.exception.CloudAccountNotFoundException;
import dev.sami.platform.exception.DuplicateCloudAccountException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DuplicateCloudAccountException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Problem conflict(DuplicateCloudAccountException exception) {
        return new Problem(Instant.now(), HttpStatus.CONFLICT.value(), exception.getMessage(), Map.of());
    }

    @ExceptionHandler(CloudAccountNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Problem notFound(CloudAccountNotFoundException exception) {
        return new Problem(Instant.now(), HttpStatus.NOT_FOUND.value(), exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Problem validation(MethodArgumentNotValidException exception) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage(),
                        (first, ignored) -> first));
        return new Problem(Instant.now(), HttpStatus.BAD_REQUEST.value(), "Validation failed", errors);
    }

    record Problem(Instant timestamp, int status, String message, Map<String, String> errors) {}
}
