package dev.sami.platform.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dev.sami.platform.dto.DemoDataSet;
import dev.sami.platform.dto.DemoDataSet.DemoAccount;
import dev.sami.platform.dto.DemoDataSet.DemoResource;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import dev.sami.platform.repository.CloudAccountRepository;
import dev.sami.platform.repository.CloudResourceRepository;
import dev.sami.platform.service.DemoDataService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DemoDataLoaderTest {
    private final DemoDataLoader loader = new DemoDataLoader(mock(DemoDataService.class), new ObjectMapper());

    private DemoDataSet bundledData() throws IOException {
        try (var input = new ClassPathResource(DemoDataLoader.DEMO_DATA_FILE).getInputStream()) {
            return loader.read(input);
        }
    }

    private DemoDataSet read(String json) throws IOException {
        return loader.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void existsOnlyWhenTheDemoProfileIsActive() {
        var runner = new ApplicationContextRunner()
                .withBean(DemoDataService.class, () -> mock(DemoDataService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(DemoDataLoader.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(DemoDataLoader.class));
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataLoader.class));
        runner.withPropertyValues("spring.profiles.active=demo")
                .run(context -> assertThat(context).hasSingleBean(DemoDataLoader.class));
    }

    @Test
    void bundledDataHasUniqueKeysAndCoversTheInventoryFeatures() throws IOException {
        var accounts = bundledData().accounts();
        var resources = accounts.stream().flatMap(account -> account.resources().stream()).toList();

        assertThat(accounts).extracting(DemoAccount::provider).containsExactlyInAnyOrder(CloudProvider.values());
        assertThat(accounts.stream().map(a -> a.provider() + ":" + a.externalAccountId())).doesNotHaveDuplicates();
        for (var account : accounts) {
            assertThat(account.resources()).extracting(DemoResource::externalResourceId).doesNotHaveDuplicates();
        }
        // More than one page at the default page size of 20, so pagination has something to show.
        assertThat(resources).hasSizeGreaterThan(20);
        assertThat(resources.stream().map(DemoResource::category).collect(Collectors.toSet()))
                .isEqualTo(new HashSet<>(Arrays.asList(ResourceCategory.values())));
        assertThat(resources.stream().map(DemoResource::status).collect(Collectors.toSet()))
                .isEqualTo(new HashSet<>(Arrays.asList(ResourceStatus.values())));
        assertThat(resources).extracting(DemoResource::region).contains("global");
        assertThat(accounts).allSatisfy(account -> assertThat(account.name()).startsWith("Demo "));
    }

    @Test
    void rejectsMisspelledFields() {
        assertThatThrownBy(() -> read("{\"accounts\":[{\"nmae\":\"Demo AWS\"}]}"))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("nmae");
    }

    @Test
    void rejectsUnknownProviders() {
        assertThatThrownBy(() -> read("{\"accounts\":[{\"provider\":\"ORACLE\"}]}"))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("ORACLE");
    }

    @Test
    void treatsMissingListsAsEmpty() throws IOException {
        assertThat(read("{}").accounts()).isEmpty();
        assertThat(read("{\"accounts\":[{\"name\":\"Demo AWS\"}]}").accounts())
                .singleElement().satisfies(account -> assertThat(account.resources()).isEmpty());
    }

    // The import commits, so the profile test uses its own database instead of the shared test database.
    @Nested
    @SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:demo-loader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    @ActiveProfiles("demo")
    class WithDemoProfile {
        @Autowired DemoDataLoader demoDataLoader;
        @Autowired CloudAccountRepository accounts;
        @Autowired CloudResourceRepository resources;

        @Test
        void importsTheBundledDataAtStartupAndOnlyOnce() throws Exception {
            var expected = bundledData();
            long expectedResources = expected.accounts().stream().mapToLong(a -> a.resources().size()).sum();

            // Startup already ran the loader, which validated every entry through the services.
            assertThat(accounts.count()).isEqualTo(expected.accounts().size());
            assertThat(resources.count()).isEqualTo(expectedResources);

            demoDataLoader.run(null);

            assertThat(accounts.count()).isEqualTo(expected.accounts().size());
            assertThat(resources.count()).isEqualTo(expectedResources);
            assertThat(accounts.findAll()).extracting("name")
                    .containsExactlyInAnyOrderElementsOf(expected.accounts().stream().map(DemoAccount::name).toList());
        }
    }
}
