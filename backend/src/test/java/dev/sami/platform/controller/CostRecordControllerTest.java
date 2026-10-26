package dev.sami.platform.controller;

import dev.sami.platform.dto.CostImportResponse;
import dev.sami.platform.dto.CostImportRowError;
import dev.sami.platform.exception.ConcurrentCostImportException;
import dev.sami.platform.exception.InvalidCostFileException;
import dev.sami.platform.service.CostImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CostRecordController.class)
class CostRecordControllerTest {
    private static final String IMPORT = "/api/v1/cost-records/import";
    private static final MockMultipartFile FILE = new MockMultipartFile("file", "costs.csv", "text/csv",
            "date,provider,account_id,service,resource_id,amount,currency\n".getBytes(StandardCharsets.UTF_8));

    @Autowired MockMvc mvc;
    @MockitoBean CostImportService service;

    @Test
    void importsTheUploadedFileAndReturnsTheCounts() throws Exception {
        when(service.importCsv(any())).thenAnswer(invocation -> {
            try (InputStream input = invocation.getArgument(0)) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).startsWith("date,provider");
            }
            return new CostImportResponse(10, 6, 3, 1);
        });

        mvc.perform(multipart(IMPORT).file(FILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsRead").value(10))
                .andExpect(jsonPath("$.created").value(6))
                .andExpect(jsonPath("$.updated").value(3))
                .andExpect(jsonPath("$.unchanged").value(1));
    }

    @Test
    void requiresTheFilePart() throws Exception {
        mvc.perform(multipart(IMPORT).file(new MockMultipartFile("upload", "costs.csv", "text/csv", new byte[1])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.file").value("is required"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsRequestsThatAreNotMultipart() throws Exception {
        mvc.perform(post(IMPORT).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    @Test
    void returnsRowErrorsAsAnOrderedList() throws Exception {
        when(service.importCsv(any())).thenThrow(new InvalidCostFileException(List.of(
                new CostImportRowError(2, "amount", "must have at most 6 decimal places"),
                new CostImportRowError(10, null, "has 4 values; expected 7")), 250));

        mvc.perform(multipart(IMPORT).file(FILE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Cost file has 250 errors; the first 2 are listed"))
                .andExpect(jsonPath("$.errors").value(anEmptyMap()))
                .andExpect(jsonPath("$.totalErrors").value(250))
                .andExpect(jsonPath("$.rowErrors[0].row").value(2))
                .andExpect(jsonPath("$.rowErrors[0].column").value("amount"))
                .andExpect(jsonPath("$.rowErrors[0].message").value("must have at most 6 decimal places"))
                .andExpect(jsonPath("$.rowErrors[1].row").value(10))
                .andExpect(jsonPath("$.rowErrors[1].column").value(nullValue()))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void reportsAConcurrentImportAsAConflict() throws Exception {
        when(service.importCsv(any())).thenThrow(new ConcurrentCostImportException(new RuntimeException("secret")));

        mvc.perform(multipart(IMPORT).file(FILE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cost records changed during the import. Please retry."));
    }
}
