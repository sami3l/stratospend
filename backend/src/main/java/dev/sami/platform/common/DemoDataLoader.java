package dev.sami.platform.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sami.platform.dto.DemoDataSet;
import dev.sami.platform.service.DemoDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/** Imports the bundled demo data at startup. It exists only when the "demo" profile is active. */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {
    static final String DEMO_DATA_FILE = "demo/demo-data.json";
    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final DemoDataService service;
    private final ObjectMapper objectMapper;

    public DemoDataLoader(DemoDataService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        try (var input = new ClassPathResource(DEMO_DATA_FILE).getInputStream()) {
            var summary = service.importData(read(input));
            log.info("Demo data: {} accounts created, {} existing; {} resources created, {} existing",
                    summary.accountsCreated(), summary.accountsExisting(),
                    summary.resourcesCreated(), summary.resourcesExisting());
        }
    }

    DemoDataSet read(InputStream input) throws IOException {
        // Spring Boot ignores unknown JSON properties by default; a misspelled demo field must fail instead.
        return objectMapper.readerFor(DemoDataSet.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(input);
    }
}
