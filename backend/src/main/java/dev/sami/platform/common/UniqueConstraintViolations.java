package dev.sami.platform.common;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;
import java.util.regex.Pattern;

/** Recognizes a violation of one named unique constraint, as reported by PostgreSQL or by H2 in tests. */
public final class UniqueConstraintViolations {
    private UniqueConstraintViolations() {}

    public static boolean isViolationOf(DataIntegrityViolationException failure, String constraintName) {
        var h2UniqueIndex = Pattern.compile(Pattern.quote(constraintName) + "_index_[0-9a-f]+");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState()) && violation.getConstraintName() != null) {
                // Normalize database identifier spelling only, never values or exception message text.
                String name = violation.getConstraintName().replace("\"", "").trim().toLowerCase(Locale.ROOT);
                name = name.substring(name.lastIndexOf('.') + 1);
                if (constraintName.equals(name)) {
                    return true;
                }
                // H2 reports the generated backing index; PostgreSQL reports the declared constraint itself.
                if (violation.getErrorCode() == 23505 && h2UniqueIndex.matcher(name).matches()) {
                    return true;
                }
            }
        }
        return false;
    }
}
