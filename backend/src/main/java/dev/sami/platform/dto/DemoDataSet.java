package dev.sami.platform.dto;

import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;

import java.util.List;

/**
 * Sample accounts and their resources, read from the demo data file. Each entry converts to the
 * regular API request, so demo data passes exactly the same validation as data sent to the API.
 */
public record DemoDataSet(List<DemoAccount> accounts) {

    public DemoDataSet {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    public record DemoAccount(
            String name,
            CloudProvider provider,
            String externalAccountId,
            CloudEnvironment environment,
            String region,
            List<DemoResource> resources) {

        public DemoAccount {
            resources = resources == null ? List.of() : List.copyOf(resources);
        }

        public CloudAccountRequest toRequest() {
            return new CloudAccountRequest(name, provider, externalAccountId, environment, region);
        }
    }

    public record DemoResource(
            String externalResourceId,
            String name,
            ResourceCategory category,
            String providerService,
            String region,
            ResourceStatus status) {

        public CloudResourceRequest toRequest(Long cloudAccountId) {
            return new CloudResourceRequest(cloudAccountId, externalResourceId, name, category, providerService,
                    region, status);
        }
    }
}
