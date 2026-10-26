package dev.sami.platform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One day of cost for an account and provider service, optionally attributed to a resource.
 * The unique key (account, resource or none, service, date) is defined in migration V3, because
 * JPA cannot express UNIQUE NULLS NOT DISTINCT.
 */
@Entity
@Table(name = "cost_records")
public class CostRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cloud_account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_cost_record_account"))
    private CloudAccount cloudAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_resource_id")
    private CloudResource cloudResource;

    @Column(name = "provider_service", nullable = false, length = 80)
    private String providerService;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CostRecord() {}

    public CostRecord(CloudAccount cloudAccount, CloudResource cloudResource, String providerService,
                      LocalDate usageDate, BigDecimal amount, String currency) {
        // The database enforces this too, through the (resource, account) foreign key.
        if (cloudResource != null
                && !Objects.equals(cloudResource.getCloudAccount().getId(), cloudAccount.getId())) {
            throw new IllegalArgumentException("Cost record resource must belong to the cost record account");
        }
        this.cloudAccount = cloudAccount;
        this.cloudResource = cloudResource;
        this.providerService = providerService;
        this.usageDate = usageDate;
        this.amount = amount;
        this.currency = currency;
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

    /** Replaces the amount when a provider revises a day that was already imported. The key never changes. */
    public void replaceAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Long getId() { return id; }
    public CloudAccount getCloudAccount() { return cloudAccount; }
    public CloudResource getCloudResource() { return cloudResource; }
    public String getProviderService() { return providerService; }
    public LocalDate getUsageDate() { return usageDate; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
