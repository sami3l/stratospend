package dev.sami.platform.repository;

import dev.sami.platform.model.CostRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface CostRecordRepository extends JpaRepository<CostRecord, Long> {

    Optional<CostRecord> findByCloudAccountIdAndCloudResourceIdAndProviderServiceAndUsageDate(
            Long cloudAccountId, Long cloudResourceId, String providerService, LocalDate usageDate);

    Optional<CostRecord> findByCloudAccountIdAndCloudResourceIsNullAndProviderServiceAndUsageDate(
            Long cloudAccountId, String providerService, LocalDate usageDate);

    /**
     * Finds the record with this unique key. A missing resource ID matches only records without a
     * resource, because SQL needs IS NULL rather than = NULL for that comparison.
     */
    default Optional<CostRecord> findByKey(Long cloudAccountId, Long cloudResourceId, String providerService,
                                           LocalDate usageDate) {
        return cloudResourceId == null
                ? findByCloudAccountIdAndCloudResourceIsNullAndProviderServiceAndUsageDate(
                        cloudAccountId, providerService, usageDate)
                : findByCloudAccountIdAndCloudResourceIdAndProviderServiceAndUsageDate(
                        cloudAccountId, cloudResourceId, providerService, usageDate);
    }
}
