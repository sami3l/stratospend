package dev.sami.platform.controller;

import dev.sami.platform.dto.CostImportResponse;
import dev.sami.platform.service.CostImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/cost-records")
public class CostRecordController {
    private final CostImportService service;

    public CostRecordController(CostImportService service) {
        this.service = service;
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CostImportResponse importCosts(@RequestPart("file") MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) {
            return service.importCsv(input);
        }
    }
}
