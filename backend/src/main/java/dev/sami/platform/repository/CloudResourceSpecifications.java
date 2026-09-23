package dev.sami.platform.repository;

import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;

public final class CloudResourceSpecifications {
    private CloudResourceSpecifications() {}

    public static Specification<CloudResource> matching(Long cloudAccountId, CloudProvider provider,
                                                        ResourceCategory category, String region,
                                                        ResourceStatus status) {
        return (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (cloudAccountId != null) {
                predicates.add(builder.equal(root.get("cloudAccount").get("id"), cloudAccountId));
            }
            if (provider != null) {
                predicates.add(builder.equal(root.get("cloudAccount").get("provider"), provider));
            }
            if (category != null) {
                predicates.add(builder.equal(root.get("category"), category));
            }
            if (region != null) {
                predicates.add(builder.equal(root.get("region"), region));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
