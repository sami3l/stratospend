package dev.sami.platform.controller;

import dev.sami.platform.dto.CloudResourceQuery;
import dev.sami.platform.dto.CloudResourceRequest;
import dev.sami.platform.dto.CloudResourceResponse;
import dev.sami.platform.dto.PageResponse;
import dev.sami.platform.dto.UpdateCloudResourceRequest;
import dev.sami.platform.service.CloudResourceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/cloud-resources")
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:3000}")
public class CloudResourceController {
    private final CloudResourceService service;

    public CloudResourceController(CloudResourceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CloudResourceResponse> create(@Valid @RequestBody CloudResourceRequest request) {
        var response = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/cloud-resources/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public CloudResourceResponse findById(@PathVariable @Positive Long id) {
        return service.findById(id);
    }

    @GetMapping
    public PageResponse<CloudResourceResponse> findAll(@Valid @ModelAttribute CloudResourceQuery query) {
        return service.findAll(query);
    }

    @PutMapping("/{id}")
    public CloudResourceResponse update(@PathVariable @Positive Long id,
                                        @Valid @RequestBody UpdateCloudResourceRequest request) {
        return service.update(id, request);
    }
}
