package dev.sami.platform.repository;

import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.lang.Nullable;

import java.util.Optional;

public interface CloudResourceRepository extends JpaRepository<CloudResource, Long>,
        JpaSpecificationExecutor<CloudResource> {

    boolean existsByCloudAccountIdAndExternalResourceId(Long cloudAccountId, String externalResourceId);

    @Override
    @EntityGraph(attributePaths = "cloudAccount")
    Optional<CloudResource> findById(Long id);

    @Override
    @EntityGraph(attributePaths = "cloudAccount")
    Page<CloudResource> findAll(@Nullable Specification<CloudResource> specification, Pageable pageable);

    /** Returns a filtered page with a unique ID tie-breaker to keep ordering deterministic. */
    default Page<CloudResource> findByFilters(Long cloudAccountId, CloudProvider provider,
                                            ResourceCategory category, String region,
                                            ResourceStatus status, Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        } else if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by("id"));
        }
        Pageable orderedPage = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort)
                : Pageable.unpaged(sort);

        return findAll(CloudResourceSpecifications.matching(cloudAccountId, provider, category, region, status),
                orderedPage);
    }
}
