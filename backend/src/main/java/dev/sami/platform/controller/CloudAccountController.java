package dev.sami.platform.controller;

import dev.sami.platform.dto.CloudAccountRequest;
import dev.sami.platform.dto.CloudAccountResponse;
import dev.sami.platform.dto.UpdateCloudAccountRequest;
import dev.sami.platform.service.CloudAccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/cloud-accounts")
public class CloudAccountController {
    private final CloudAccountService service;

    public CloudAccountController(CloudAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<CloudAccountResponse> findAll() { return service.findAll(); }

    @GetMapping("/{id}")
    public CloudAccountResponse findById(@PathVariable Long id) { return service.findById(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CloudAccountResponse create(@Valid @RequestBody CloudAccountRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public CloudAccountResponse update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateCloudAccountRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public CloudAccountResponse changeStatus(@PathVariable Long id, @RequestParam boolean active) {
        return service.changeStatus(id, active);
    }
}
