package dev.sami.platform.common;

import dev.sami.platform.exception.CloudAccountNotFoundException;
import dev.sami.platform.exception.CloudResourceNotFoundException;
import dev.sami.platform.exception.DuplicateCloudAccountException;
import dev.sami.platform.exception.DuplicateCloudResourceException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({DuplicateCloudAccountException.class, DuplicateCloudResourceException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    Problem conflict(RuntimeException exception) {
        return new Problem(Instant.now(), HttpStatus.CONFLICT.value(), exception.getMessage(), Map.of());
    }

    @ExceptionHandler({CloudAccountNotFoundException.class, CloudResourceNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Problem notFound(RuntimeException exception) {
        return new Problem(Instant.now(), HttpStatus.NOT_FOUND.value(), exception.getMessage(), Map.of());
    }

    // MethodArgumentNotValidException extends BindException; covers @RequestBody and @ModelAttribute.
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Problem validation(BindException exception) {
        var errors = new TreeMap<String, String>();
        addBindingErrors(errors, exception.getBindingResult());
        return badRequest("Validation failed", errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Problem> methodValidation(HandlerMethodValidationException exception) {
        return methodValidationResponse(exception);
    }

    @ExceptionHandler(MethodValidationException.class)
    ResponseEntity<Problem> adaptedMethodValidation(MethodValidationException exception) {
        return methodValidationResponse(exception);
    }

    // Used by service-level validation and @Validated method proxies.
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Problem> constraintValidation(ConstraintViolationException exception) {
        var errors = new TreeMap<String, String>();
        for (var violation : exception.getConstraintViolations()) {
            String field = "request";
            for (var node : violation.getPropertyPath()) {
                if (node.getKind() == ElementKind.RETURN_VALUE) {
                    return serverError();
                }
                if (node.getKind() == ElementKind.PROPERTY || node.getKind() == ElementKind.PARAMETER) {
                    field = node.getName();
                }
            }
            addError(errors, field, violation.getMessage());
        }
        return ResponseEntity.badRequest().body(badRequest("Validation failed", errors));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Problem typeMismatch(MethodArgumentTypeMismatchException exception) {
        return badRequest("Invalid parameter", Map.of(exception.getName(), typeMessage(exception.getRequiredType())));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Problem missingParameter(MissingServletRequestParameterException exception) {
        return badRequest("Missing required parameter", Map.of(exception.getParameterName(), "is required"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Problem unreadableBody(HttpMessageNotReadableException exception) {
        var errors = new TreeMap<String, String>();
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonMappingException mapping) {
                String field = jsonField(mapping);
                if (mapping instanceof UnrecognizedPropertyException unknown) {
                    addError(errors, unknown.getPropertyName(), "is not allowed");
                } else if (field != null) {
                    String message = mapping instanceof InvalidFormatException format
                            ? typeMessage(format.getTargetType()) : "has an invalid value or is not allowed";
                    addError(errors, field, message);
                }
            }
        }
        // Never return Jackson messages: they can contain request values, class names and source excerpts.
        return badRequest("Invalid request body", errors);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unexpected(Exception exception) {
        // Preserve framework HTTP semantics (e.g. 404, 405, 415), without exposing exception details.
        if (exception instanceof ErrorResponse error && !error.getStatusCode().is5xxServerError()) {
            HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
            String message = status == null ? "Request failed" : status.getReasonPhrase();
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .body(new Problem(Instant.now(), error.getStatusCode().value(), message, Map.of()));
        }
        // Includes unrelated DataIntegrityViolationException; only domain duplicate exceptions are conflicts.
        return serverError();
    }

    private ResponseEntity<Problem> methodValidationResponse(MethodValidationResult result) {
        if (result.isForReturnValue()) {
            return serverError();
        }
        var errors = new TreeMap<String, String>();
        for (var parameter : result.getParameterValidationResults()) {
            if (parameter instanceof ParameterErrors beanErrors) {
                addBindingErrors(errors, beanErrors);
            } else {
                String field = parameterName(parameter.getMethodParameter());
                parameter.getResolvableErrors().forEach(error -> addError(errors, field, error.getDefaultMessage()));
            }
        }
        result.getCrossParameterValidationResults()
                .forEach(error -> addError(errors, "request", error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(badRequest("Validation failed", errors));
    }

    private void addBindingErrors(Map<String, String> errors, Errors binding) {
        binding.getFieldErrors().forEach(error -> addError(errors, error.getField(),
                error.isBindingFailure() ? typeMessage(binding.getFieldType(error.getField())) : error.getDefaultMessage()));
        binding.getGlobalErrors().forEach(error -> addError(errors, "request", error.getDefaultMessage()));
    }

    private void addError(Map<String, String> errors, String field, String message) {
        // Multiple constraints on one field have no guaranteed iteration order; select consistently.
        errors.merge(field == null ? "request" : field, message == null ? "is invalid" : message,
                (first, second) -> first.compareTo(second) <= 0 ? first : second);
    }

    private String parameterName(MethodParameter parameter) {
        var query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null) {
            if (!query.name().isEmpty()) return query.name();
            if (!query.value().isEmpty()) return query.value();
        }
        var path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            if (!path.name().isEmpty()) return path.name();
            if (!path.value().isEmpty()) return path.value();
        }
        return parameter.getParameterName() == null ? "arg" + parameter.getParameterIndex() : parameter.getParameterName();
    }

    private String jsonField(JsonMappingException exception) {
        return exception.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                .filter(name -> name != null).reduce((first, last) -> last).orElse(null);
    }

    private String typeMessage(Class<?> type) {
        if (type != null && type.isEnum()) {
            return "must be one of: " + Arrays.stream(type.getEnumConstants())
                    .map(value -> ((Enum<?>) value).name()).collect(Collectors.joining(", "));
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return "must be a valid integer";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "must be true or false";
        }
        return "has an invalid value";
    }

    private Problem badRequest(String message, Map<String, String> errors) {
        return new Problem(Instant.now(), HttpStatus.BAD_REQUEST.value(), message, errors);
    }

    private ResponseEntity<Problem> serverError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Problem(Instant.now(), 500, "Internal server error", Map.of()));
    }

    record Problem(Instant timestamp, int status, String message, Map<String, String> errors) {
        Problem {
            errors = Collections.unmodifiableMap(new TreeMap<>(errors));
        }
    }
}
