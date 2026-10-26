package dev.sami.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import dev.sami.platform.dto.CostImportRowError;
import dev.sami.platform.exception.InvalidCostFileException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the structure of a cost CSV file: encoding, header, quoting and row numbers. Field rules
 * such as dates, amounts and account lookups belong to {@link CostImportService}.
 */
@Component
public class CostCsvReader {
    // New rows are inserted one at a time because the database generates their IDs, so a full import of
    // 10,000 new rows takes about 13 seconds on Windows with Docker Desktop (50,000 took 74). Raise this
    // limit only together with batched inserts.
    public static final int MAX_ROWS = 10_000;
    public static final List<String> COLUMNS =
            List.of("date", "provider", "account_id", "service", "resource_id", "amount", "currency");

    /**
     * One data row. Row numbers match a spreadsheet, so the header is row 1 and blank rows are counted.
     * When the row has the wrong number of values, {@code values} is null and {@code problem} says why.
     */
    public record CostCsvRow(int row, Map<String, String> values, String problem) {
        public String get(String column) {
            return values.get(column);
        }
    }

    private final CsvMapper mapper = new CsvMapper();

    public List<CostCsvRow> read(InputStream input) {
        // Report invalid bytes instead of silently replacing them with U+FFFD.
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        int row = 0;
        try (var reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            skipByteOrderMark(reader);
            MappingIterator<String[]> records = mapper.readerFor(String[].class)
                    .with(CsvParser.Feature.WRAP_AS_ARRAY)
                    .readValues(reader);
            if (!records.hasNextValue()) {
                throw new InvalidCostFileException(new CostImportRowError(1, null,
                        "File is empty; expected a header row: " + String.join(",", COLUMNS)));
            }
            row = 1;
            var columns = readHeader(records.nextValue());

            var rows = new ArrayList<CostCsvRow>();
            while (records.hasNextValue()) {
                String[] values = records.nextValue();
                row++;
                if (isBlank(values)) {
                    continue;
                }
                if (rows.size() == MAX_ROWS) {
                    throw new InvalidCostFileException(new CostImportRowError(row, null,
                            "File has more than " + MAX_ROWS + " cost rows"));
                }
                if (values.length != columns.size()) {
                    rows.add(new CostCsvRow(row, null,
                            "has " + values.length + " values; expected " + columns.size()));
                    continue;
                }
                var mapped = new HashMap<String, String>();
                for (int i = 0; i < values.length; i++) {
                    mapped.put(columns.get(i), values[i] == null ? "" : values[i]);
                }
                rows.add(new CostCsvRow(row, mapped, null));
            }
            if (rows.isEmpty()) {
                throw new InvalidCostFileException(new CostImportRowError(2, null, "File has no cost rows"));
            }
            return rows;
        } catch (IOException failure) {
            // The failing record is the one after the last record read successfully.
            if (hasCause(failure, CharacterCodingException.class)) {
                throw new InvalidCostFileException(new CostImportRowError(Math.max(row + 1, 1), null,
                        "is not valid UTF-8 text"));
            }
            if (failure instanceof JsonProcessingException) {
                // Never echo parser messages: they can contain file contents.
                throw new InvalidCostFileException(new CostImportRowError(row + 1, null,
                        "is not valid CSV; check for an unclosed quote"));
            }
            throw new UncheckedIOException(failure);
        }
    }

    private List<String> readHeader(String[] header) {
        if (isBlank(header)) {
            throw new InvalidCostFileException(new CostImportRowError(1, null,
                    "must be the header row: " + String.join(",", COLUMNS)));
        }
        var names = Arrays.stream(header).map(name -> name == null ? "" : name.trim().toLowerCase(Locale.ROOT))
                .toList();
        var errors = new ArrayList<CostImportRowError>();
        var seen = new HashSet<String>();
        for (var name : names) {
            if (!COLUMNS.contains(name)) {
                errors.add(new CostImportRowError(1, name, "is not a known column"));
            } else if (!seen.add(name)) {
                errors.add(new CostImportRowError(1, name, "appears more than once"));
            }
        }
        for (var column : COLUMNS) {
            if (!seen.contains(column)) {
                errors.add(new CostImportRowError(1, column, "is missing from the header"));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidCostFileException(errors, errors.size());
        }
        return names;
    }

    private static void skipByteOrderMark(Reader reader) throws IOException {
        // Excel adds U+FEFF at the start of UTF-8 files; it would otherwise become part of the first column name.
        reader.mark(1);
        if (reader.read() != '﻿') {
            reader.reset();
        }
    }

    private static boolean isBlank(String[] values) {
        return Arrays.stream(values).allMatch(value -> value == null || value.isBlank());
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
