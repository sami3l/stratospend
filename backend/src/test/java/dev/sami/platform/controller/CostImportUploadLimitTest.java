package dev.sami.platform.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Upload limits are enforced by the real servlet container, which MockMvc does not run.
// The properties repeat the main application.yml values, which the test configuration replaces.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=5MB",
        "spring.servlet.multipart.max-request-size=6MB",
        "server.tomcat.max-swallow-size=10MB",
        "spring.datasource.url=jdbc:h2:mem:cost-import-upload;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class CostImportUploadLimitTest {
    @LocalServerPort int port;

    private record Result(HttpStatus status, Map<String, Object> body) {}

    private Result upload(byte[] content) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "costs.csv";
            }
        });
        return RestClient.create("http://localhost:" + port).post().uri("/api/v1/cost-records/import")
                .contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                .exchange((request, response) -> new Result(HttpStatus.valueOf(response.getStatusCode().value()),
                        response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {})));
    }

    @Test
    void rejectsFilesOverFiveMegabytesWithAReadableMessage() {
        var result = upload(new byte[5 * 1024 * 1024 + 1]);

        assertThat(result.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(result.body()).containsEntry("status", 413)
                .containsEntry("message", "File is too large; the maximum is 5 MB");
    }

    @Test
    void reachesTheImportForFilesWithinTheLimit() {
        var result = upload("date,provider,account_id,service,resource_id,amount,currency\n"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.body()).containsEntry("message", "Cost file has 1 error");
    }
}
