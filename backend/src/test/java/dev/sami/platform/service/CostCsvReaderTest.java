package dev.sami.platform.service;

import dev.sami.platform.dto.CostImportRowError;
import dev.sami.platform.exception.InvalidCostFileException;
import dev.sami.platform.service.CostCsvReader.CostCsvRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class CostCsvReaderTest {
    private static final String HEADER = "date,provider,account_id,service,resource_id,amount,currency\n";
    private static final String ROW = "2026-10-01,AWS,111122223333,EC2,i-web-1,12.50,USD\n";

    private final CostCsvReader reader = new CostCsvReader();

    private List<CostCsvRow> read(String csv) {
        return read(csv.getBytes(StandardCharsets.UTF_8));
    }

    private List<CostCsvRow> read(byte[] bytes) {
        return reader.read(new ByteArrayInputStream(bytes));
    }

    private List<CostImportRowError> errors(String csv) {
        return errors(csv.getBytes(StandardCharsets.UTF_8));
    }

    private List<CostImportRowError> errors(byte[] bytes) {
        return catchThrowableOfType(InvalidCostFileException.class, () -> read(bytes)).getErrors();
    }

    @Test
    void readsRowsWithSpreadsheetRowNumbers() {
        var rows = read(HEADER + ROW + "2026-10-02,GCP,demo-project,Cloud Storage,,0.000001,USD\n");

        assertThat(rows).extracting(CostCsvRow::row).containsExactly(2, 3);
        assertThat(rows.get(0).values()).containsEntry("date", "2026-10-01").containsEntry("provider", "AWS")
                .containsEntry("account_id", "111122223333").containsEntry("service", "EC2")
                .containsEntry("resource_id", "i-web-1").containsEntry("amount", "12.50")
                .containsEntry("currency", "USD");
        assertThat(rows.get(1).get("resource_id")).isEmpty();
        assertThat(rows).extracting(CostCsvRow::problem).containsOnlyNulls();
    }

    @Test
    void acceptsColumnsInAnyOrderWithCaseAndSpacesInTheHeader() {
        var rows = read(" Currency ,AMOUNT,resource_id,service,account_id,provider,date\nUSD,3,,EC2,1,AWS,2026-10-01\n");

        assertThat(rows.get(0).values()).containsEntry("currency", "USD").containsEntry("amount", "3")
                .containsEntry("date", "2026-10-01");
    }

    @Test
    void keepsQuotedCommasQuotesAndLineBreaksInsideOneValue() {
        var rows = read(HEADER + "2026-10-01,AWS,1,\"EC2, \"\"spot\"\"\nfleet\",i-1,1,USD\n" + ROW);

        assertThat(rows.get(0).get("service")).isEqualTo("EC2, \"spot\"\nfleet");
        // A spreadsheet shows a quoted line break inside one cell, so the next record is still row 3.
        assertThat(rows).extracting(CostCsvRow::row).containsExactly(2, 3);
    }

    @Test
    void removesTheExcelByteOrderMark() {
        var bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        var csv = (HEADER + ROW).getBytes(StandardCharsets.UTF_8);
        var withBom = new byte[bom.length + csv.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(csv, 0, withBom, bom.length, csv.length);

        assertThat(read(withBom).get(0).get("date")).isEqualTo("2026-10-01");
    }

    @Test
    void skipsBlankRowsButKeepsCountingThem() {
        var rows = read(HEADER + ROW + "\n,,,,,,\n" + ROW.replace("10-01", "10-02"));

        assertThat(rows).extracting(CostCsvRow::row).containsExactly(2, 5);
    }

    @Test
    void acceptsWindowsLineEndingsAndAMissingFinalNewline() {
        var rows = read((HEADER + ROW).replace("\n", "\r\n") + ROW.strip());

        assertThat(rows).extracting(CostCsvRow::row).containsExactly(2, 3);
        assertThat(rows.get(1).get("currency")).isEqualTo("USD");
    }

    @Test
    void reportsRowsWithTheWrongNumberOfValues() {
        var rows = read(HEADER + "2026-10-01,AWS,1,EC2\n" + ROW + ROW.strip() + ",extra\n");

        assertThat(rows.get(0).problem()).isEqualTo("has 4 values; expected 7");
        assertThat(rows.get(0).values()).isNull();
        assertThat(rows.get(1).problem()).isNull();
        assertThat(rows.get(2).problem()).isEqualTo("has 8 values; expected 7");
    }

    @Test
    void rejectsAnEmptyFileAHeaderOnlyFileAndABlankHeader() {
        assertThat(errors("")).containsExactly(new CostImportRowError(1, null,
                "File is empty; expected a header row: date,provider,account_id,service,resource_id,amount,currency"));
        assertThat(errors(HEADER)).containsExactly(new CostImportRowError(2, null, "File has no cost rows"));
        assertThat(errors(HEADER + "\n,,,,,,\n")).containsExactly(new CostImportRowError(2, null, "File has no cost rows"));
        assertThat(errors(",,\n" + ROW)).containsExactly(new CostImportRowError(1, null,
                "must be the header row: date,provider,account_id,service,resource_id,amount,currency"));
    }

    @Test
    void listsEveryHeaderProblem() {
        assertThat(errors("date,provider,acount_id,service,service,amount,currency\n" + ROW)).containsExactly(
                new CostImportRowError(1, "acount_id", "is not a known column"),
                new CostImportRowError(1, "service", "appears more than once"),
                new CostImportRowError(1, "account_id", "is missing from the header"),
                new CostImportRowError(1, "resource_id", "is missing from the header"));
    }

    @Test
    void acceptsTheMaximumNumberOfRowsAndRejectsOneMore() {
        var atLimit = HEADER + ROW.repeat(CostCsvReader.MAX_ROWS);
        assertThat(read(atLimit)).hasSize(CostCsvReader.MAX_ROWS);

        assertThat(errors(atLimit + ROW)).containsExactly(new CostImportRowError(CostCsvReader.MAX_ROWS + 2, null,
                "File has more than 10000 cost rows"));
    }

    @Test
    void rejectsBrokenQuotingWithoutEchoingTheFile() {
        var errors = errors(HEADER + ROW + "2026-10-02,AWS,1,\"secret-value,i-1,1,USD\n");

        assertThat(errors).containsExactly(new CostImportRowError(3, null,
                "is not valid CSV; check for an unclosed quote"));
    }

    @Test
    void treatsAFailedUploadStreamAsAServerErrorRatherThanAFileError() {
        var broken = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("connection reset");
            }
        };

        assertThatThrownBy(() -> reader.read(broken)).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void rejectsBytesThatAreNotUtf8() {
        var header = HEADER.getBytes(StandardCharsets.UTF_8);
        var latin1Row = "2026-10-01,AWS,1,Café,i-1,1,USD\n".getBytes(StandardCharsets.ISO_8859_1);
        var bytes = new byte[header.length + latin1Row.length];
        System.arraycopy(header, 0, bytes, 0, header.length);
        System.arraycopy(latin1Row, 0, bytes, header.length, latin1Row.length);

        assertThat(errors(bytes)).singleElement().satisfies(error -> {
            assertThat(error.message()).isEqualTo("is not valid UTF-8 text");
            assertThat(error.column()).isNull();
        });
    }
}
