package dev.sami.platform.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "cloud_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cloud_account_provider_external_id",
                columnNames = {"provider", "external_account_id"})
)
public class CloudAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CloudProvider provider;

    @Column(name = "external_account_id", nullable = false, length = 120)
    private String externalAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CloudEnvironment environment;

    @Column(nullable = false, length = 40)
    private String region;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CloudAccount() {}

    public CloudAccount(String name, CloudProvider provider, String externalAccountId,
                        CloudEnvironment environment, String region) {
        this.name = name;
        this.provider = provider;
        this.externalAccountId = externalAccountId;
        this.environment = environment;
        this.region = region;
        this.active = true;
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

    public void update(String name, CloudEnvironment environment, String region) {
        this.name = name;
        this.environment = environment;
        this.region = region;
    }

    public void setActive(boolean active) { this.active = active; }
    public Long getId() { return id; }
    public String getName() { return name; }
    public CloudProvider getProvider() { return provider; }
    public String getExternalAccountId() { return externalAccountId; }
    public CloudEnvironment getEnvironment() { return environment; }
    public String getRegion() { return region; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
