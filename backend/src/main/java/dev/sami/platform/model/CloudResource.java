package dev.sami.platform.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "cloud_resources",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cloud_resource_account_external_id",
                columnNames = {"cloud_account_id", "external_resource_id"})
)
public class CloudResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cloud_account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_cloud_resource_account"))
    private CloudAccount cloudAccount;

    @Column(name = "external_resource_id", nullable = false, length = 512)
    private String externalResourceId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResourceCategory category;

    @Column(name = "provider_service", nullable = false, length = 80)
    private String providerService;

    @Column(nullable = false, length = 40)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResourceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CloudResource() {}

    public CloudResource(CloudAccount cloudAccount, String externalResourceId, String name,
                         ResourceCategory category, String providerService, String region,
                         ResourceStatus status) {
        this.cloudAccount = cloudAccount;
        this.externalResourceId = externalResourceId;
        this.name = name;
        this.category = category;
        this.providerService = providerService;
        this.region = region;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void update(String name, ResourceCategory category, String providerService,
                       String region, ResourceStatus status) {
        this.name = name;
        this.category = category;
        this.providerService = providerService;
        this.region = region;
        this.status = status;
    }

    public Long getId() { return id; }
    public CloudAccount getCloudAccount() { return cloudAccount; }
    public String getExternalResourceId() { return externalResourceId; }
    public String getName() { return name; }
    public ResourceCategory getCategory() { return category; }
    public String getProviderService() { return providerService; }
    public String getRegion() { return region; }
    public ResourceStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
