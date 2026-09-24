package dev.sami.platform.service;

import dev.sami.platform.dto.CloudResourceQuery;
import dev.sami.platform.dto.CloudResourceRequest;
import dev.sami.platform.dto.CloudResourceResponse;
import dev.sami.platform.dto.UpdateCloudResourceRequest;
import dev.sami.platform.exception.CloudAccountNotFoundException;
import dev.sami.platform.exception.CloudResourceNotFoundException;
import dev.sami.platform.exception.DuplicateCloudResourceException;
import dev.sami.platform.model.CloudAccount;
import dev.sami.platform.model.CloudEnvironment;
import dev.sami.platform.model.CloudProvider;
import dev.sami.platform.model.CloudResource;
import dev.sami.platform.model.ResourceCategory;
import dev.sami.platform.model.ResourceStatus;
import dev.sami.platform.repository.CloudAccountRepository;
import dev.sami.platform.repository.CloudResourceRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.flywaydb.core.Flyway;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CloudResourceServiceTest {
    private static final String EXTERNAL_ID = "  /subscriptions/Sub-A/resources/MyVM:AbC  ";
    private static final String UNIQUE_CONSTRAINT = "uk_cloud_resource_account_external_id";
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-02-01T00:00:00Z");
    private static ValidatorFactory validatorFactory;

    @Mock CloudResourceRepository repository;
    @Mock CloudAccountRepository accountRepository;

    private CloudResourceService service;
    private CloudAccount account;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        service = new CloudResourceService(repository, accountRepository, validatorFactory.getValidator());
        account = account(7L, "Production Azure", CloudProvider.AZURE);
    }

    @Test
    void createsResourcePreservingExactExternalIdAndMapsAfterFlush() {
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            CloudResource saved = invocation.getArgument(0);
            assertThat(saved.getId()).isNull();
            stamp(saved, 42L, CREATED, CREATED);
            return saved;
        });

        var response = service.create(request(7L, EXTERNAL_ID));

        assertThat(response).isEqualTo(new CloudResourceResponse(42L, 7L, "Production Azure", CloudProvider.AZURE,
                EXTERNAL_ID, "Resource", ResourceCategory.COMPUTE, "Virtual Machines", "global",
                ResourceStatus.ACTIVE, CREATED, CREATED));
        var saved = ArgumentCaptor.forClass(CloudResource.class);
        var order = inOrder(accountRepository, repository);
        order.verify(accountRepository).findById(7L);
        order.verify(repository).existsByCloudAccountIdAndExternalResourceId(7L, EXTERNAL_ID);
        order.verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCloudAccount()).isSameAs(account);
        assertThat(saved.getValue().getExternalResourceId()).isEqualTo(EXTERNAL_ID);
    }

    @Test
    void refusesCreationWhenAccountIsMissing() {
        assertThatThrownBy(() -> service.create(request(7L, EXTERNAL_ID)))
                .isInstanceOf(CloudAccountNotFoundException.class).hasMessage("Cloud account not found: 7");
        verifyNoInteractions(repository);
    }

    @Test
    void refusesExactDuplicateBeforeInsert() {
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
        when(repository.existsByCloudAccountIdAndExternalResourceId(7L, EXTERNAL_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.create(request(7L, EXTERNAL_ID)))
                .isInstanceOf(DuplicateCloudResourceException.class).hasMessageContaining(EXTERNAL_ID)
                .hasMessageContaining("cloud account 7");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void allowsSameExternalIdInDifferentAccounts() {
        var secondAccount = account(8L, "Other account", CloudProvider.AWS);
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
        when(accountRepository.findById(8L)).thenReturn(Optional.of(secondAccount));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var first = service.create(request(7L, EXTERNAL_ID));
        var second = service.create(request(8L, EXTERNAL_ID));

        assertThat(first.externalResourceId()).isEqualTo(second.externalResourceId());
        assertThat(first.cloudAccountId()).isEqualTo(7L);
        assertThat(second.cloudAccountId()).isEqualTo(8L);
        assertThat(second.provider()).isEqualTo(CloudProvider.AWS);
        verify(repository).existsByCloudAccountIdAndExternalResourceId(7L, EXTERNAL_ID);
        verify(repository).existsByCloudAccountIdAndExternalResourceId(8L, EXTERNAL_ID);
        verify(repository, times(2)).saveAndFlush(any());
    }

    @Test
    void returnsExistingResourceWithAccountDetailsAndTimestamps() {
        var resource = resource();
        when(repository.findById(42L)).thenReturn(Optional.of(resource));

        assertThat(service.findById(42L)).isEqualTo(expectedResponse());
        verify(repository).findById(42L);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void throwsNotFoundForAbsentResource() {
        assertThatThrownBy(() -> service.findById(42L)).isInstanceOf(CloudResourceNotFoundException.class)
                .hasMessage("Cloud resource not found: 42");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveIdsBeforeRepositoryAccess(Long id) {
        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cloud resource ID must be positive");
        assertThatThrownBy(() -> service.update(id, updateRequest())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, accountRepository);
    }

    @Test
    void listsCombinedFiltersWithStableDtoPageMetadata() {
        var query = new CloudResourceQuery(7L, CloudProvider.AZURE, ResourceCategory.COMPUTE, "global",
                ResourceStatus.ACTIVE, 2, 2, "name,desc");
        var pageable = PageRequest.of(2, 2, Sort.by(Sort.Order.desc("name")));
        var resource = resource();
        when(accountRepository.existsById(7L)).thenReturn(true);
        when(repository.findByFilters(7L, CloudProvider.AZURE, ResourceCategory.COMPUTE, "global",
                ResourceStatus.ACTIVE, pageable)).thenReturn(new PageImpl<>(List.of(resource), pageable, 9));

        var result = service.findAll(query);

        assertThat(result.content()).containsExactly(expectedResponse());
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(9);
        assertThat(result.totalPages()).isEqualTo(5);
        resource.update("Later change", ResourceCategory.OTHER, "Other service", "eu-west-1", ResourceStatus.UNKNOWN);
        assertThat(result.content().get(0).name()).isEqualTo("Resource");
        verify(accountRepository).existsById(7L);
        verify(repository).findByFilters(7L, CloudProvider.AZURE, ResourceCategory.COMPUTE, "global",
                ResourceStatus.ACTIVE, pageable);
    }

    @Test
    void retainsRepositoryDefaultSortAndDoesNotCheckAccountWhenFilterIsOmitted() {
        var pageable = PageRequest.of(0, 20);
        when(repository.findByFilters(null, null, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(resource()), pageable, 1));

        var result = service.findAll(defaultQuery());

        assertThat(result.content()).containsExactly(expectedResponse());
        verify(repository).findByFilters(null, null, null, null, null, pageable);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void rejectsMissingExplicitlyFilteredAccount() {
        var query = new CloudResourceQuery(99L, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.findAll(query)).isInstanceOf(CloudAccountNotFoundException.class)
                .hasMessage("Cloud account not found: 99");
        verify(accountRepository).existsById(99L);
        verifyNoInteractions(repository);
    }

    @Test
    void returnsEmptyPageForValidFiltersWithoutMatches() {
        var pageable = PageRequest.of(1, 10);
        when(accountRepository.existsById(7L)).thenReturn(true);
        when(repository.findByFilters(7L, CloudProvider.AWS, null, null, null, pageable))
                .thenReturn(Page.empty(pageable));

        var result = service.findAll(new CloudResourceQuery(7L, CloudProvider.AWS, null, null, null, 1, 10, null));

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void preservesTotalsForEmptyPageBeyondLastPage() {
        var pageable = PageRequest.of(4, 20);
        when(repository.findByFilters(null, null, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 2));

        var result = service.findAll(new CloudResourceQuery(null, null, null, null, null, 4, 20, null));

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(4);
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void validatesDirectCreateAndUpdateCalls() {
        assertThatThrownBy(() -> service.create(request(0L, EXTERNAL_ID)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> service.update(42L,
                new UpdateCloudResourceRequest(" ", null, " ", "GLOBAL", null)))
                .isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(repository, accountRepository);
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void validatesQueriesBeforeDatabaseAccess(CloudResourceQuery query) {
        assertThatThrownBy(() -> service.findAll(query)).isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(repository, accountRepository);
    }

    static Stream<CloudResourceQuery> invalidQueries() {
        return Stream.of(
                new CloudResourceQuery(0L, null, null, null, null, 0, 20, null),
                new CloudResourceQuery(null, null, null, "GLOBAL", null, 0, 20, null),
                new CloudResourceQuery(null, null, null, null, null, -1, 20, null),
                new CloudResourceQuery(null, null, null, null, null, 0, 0, null),
                new CloudResourceQuery(null, null, null, null, null, 0, 101, null),
                new CloudResourceQuery(null, null, null, null, null, 0, 20, "cloudAccount.provider,asc"),
                new CloudResourceQuery(null, null, null, null, null, 0, 20, "name,sideways"));
    }

    @Test
    void rejectsPageOffsetOutsideJpaIntegerRange() {
        assertThatThrownBy(() -> service.findAll(new CloudResourceQuery(null, null, null, null, null,
                Integer.MAX_VALUE, 100, null))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page offset");
        verifyNoInteractions(repository, accountRepository);
    }

    @Test
    void rejectsNullRequestsBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.create(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(42L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findAll(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, accountRepository);
    }

    @Test
    void updatesOnlyEditableFieldsAndMapsFlushedTimestamp() {
        var existing = resource();
        var newTimestamp = UPDATED.plusSeconds(60);
        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(existing, "updatedAt", newTimestamp);
            return existing;
        });

        var response = service.update(42L, updateRequest());

        assertThat(existing.getCloudAccount()).isSameAs(account);
        assertThat(existing.getExternalResourceId()).isEqualTo(EXTERNAL_ID);
        assertThat(response).isEqualTo(new CloudResourceResponse(42L, 7L, "Production Azure", CloudProvider.AZURE,
                EXTERNAL_ID, "Updated resource", ResourceCategory.STORAGE, "Blob Storage", "westeurope",
                ResourceStatus.INACTIVE, CREATED, newTimestamp));
        verify(repository).saveAndFlush(existing);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void throwsNotFoundWhenUpdatingAbsentResource() {
        assertThatThrownBy(() -> service.update(42L, updateRequest())).isInstanceOf(CloudResourceNotFoundException.class)
                .hasMessage("Cloud resource not found: 42");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void translatesPostgresUniqueConstraintRaceAndRetainsCause() {
        var sqlFailure = new SQLException("ERROR: duplicate key value violates unique constraint \""
                + UNIQUE_CONSTRAINT + "\"", "23505", 0);
        var constraint = new PostgreSQLDialect().getViolatedConstraintNameExtractor().extractConstraintName(sqlFailure);
        assertThat(constraint).isEqualTo(UNIQUE_CONSTRAINT);
        var failure = integrityFailure(sqlFailure, constraint);
        failInsertAfterSuccessfulPrecheck(failure);

        assertThatThrownBy(() -> service.create(request(7L, EXTERNAL_ID)))
                .isInstanceOf(DuplicateCloudResourceException.class).hasCause(failure);
        verify(repository).existsByCloudAccountIdAndExternalResourceId(7L, EXTERNAL_ID);
        verify(repository).saveAndFlush(any());
    }

    @Test
    void translatesActualH2UniqueIndexFailureAfterSuccessfulPrecheck() throws Exception {
        var sqlFailure = h2DuplicateFailure();
        var constraint = new H2Dialect().getViolatedConstraintNameExtractor().extractConstraintName(sqlFailure);
        assertThat(sqlFailure.getSQLState()).isEqualTo("23505");
        assertThat(constraint).containsIgnoringCase(UNIQUE_CONSTRAINT + "_INDEX_");
        var failure = integrityFailure(sqlFailure, constraint);
        failInsertAfterSuccessfulPrecheck(failure);

        assertThatThrownBy(() -> service.create(request(7L, EXTERNAL_ID)))
                .isInstanceOf(DuplicateCloudResourceException.class).hasCause(failure);
    }

    @ParameterizedTest
    @MethodSource("unrelatedConstraints")
    void propagatesOtherIntegrityViolationsUnchanged(String state, int vendorCode, String constraint) {
        var failure = integrityFailure(new SQLException("Message mentioning " + UNIQUE_CONSTRAINT, state, vendorCode),
                constraint);
        failInsertAfterSuccessfulPrecheck(failure);

        assertThatThrownBy(() -> service.create(request(7L, EXTERNAL_ID))).isSameAs(failure);
    }

    static Stream<Arguments> unrelatedConstraints() {
        return Stream.of(
                Arguments.of("23503", 0, "fk_cloud_resource_account"),
                Arguments.of("23502", 0, null),
                Arguments.of("23505", 0, "another_unique_constraint"),
                Arguments.of("23505", 0, UNIQUE_CONSTRAINT + "_other"),
                Arguments.of("23505", 0, null),
                Arguments.of("23505", 0, UNIQUE_CONSTRAINT + "_index_1"),
                Arguments.of("23505", 23505, "PUBLIC." + UNIQUE_CONSTRAINT + "_INDEX_1_EXTRA"),
                Arguments.of("23505", 23505, "PUBLIC.PRIMARY_KEY_1"),
                Arguments.of("23514", 23505, UNIQUE_CONSTRAINT));
    }

    @Test
    void doesNotClassifyIntegrityErrorsFromMessageTextAlone() {
        var failure = new DataIntegrityViolationException("23505 " + UNIQUE_CONSTRAINT);
        failInsertAfterSuccessfulPrecheck(failure);

        assertThatThrownBy(() -> service.create(request(7L, EXTERNAL_ID))).isSameAs(failure);
    }

    @Test
    void preservesUnrelatedUpdateIntegrityFailure() {
        var existing = resource();
        var failure = integrityFailure(new SQLException("Foreign key violation", "23503"), "fk_cloud_resource_account");
        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenThrow(failure);

        assertThatThrownBy(() -> service.update(42L, updateRequest())).isSameAs(failure);
    }

    @Test
    void usesReadOnlyTransactionsForReadsAndWriteTransactionsForMutations() {
        var transactionManager = mock(PlatformTransactionManager.class);
        var transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        var transactionalService = transactionalProxy(transactionManager);
        when(repository.findById(42L)).thenReturn(Optional.of(resource()));
        when(repository.findByFilters(null, null, null, null, null, PageRequest.of(0, 20)))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        transactionalService.findById(42L);
        transactionalService.findAll(defaultQuery());
        transactionalService.create(request(7L, EXTERNAL_ID));
        transactionalService.update(42L, updateRequest());

        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(4)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).extracting(TransactionDefinition::isReadOnly)
                .containsExactly(true, true, false, false);
        verify(transactionManager, times(4)).commit(transactionStatus);
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void rollsBackTransactionWhenFlushedInsertBecomesDomainDuplicate() {
        var transactionManager = mock(PlatformTransactionManager.class);
        var transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        var failure = integrityFailure(new SQLException("Duplicate", "23505"), UNIQUE_CONSTRAINT);
        failInsertAfterSuccessfulPrecheck(failure);

        assertThatThrownBy(() -> transactionalProxy(transactionManager).create(request(7L, EXTERNAL_ID)))
                .isInstanceOf(DuplicateCloudResourceException.class).hasCause(failure);

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        verify(repository, times(1)).saveAndFlush(any());
    }

    private CloudResourceService transactionalProxy(PlatformTransactionManager manager) {
        var factory = new ProxyFactory(service);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        return (CloudResourceService) factory.getProxy();
    }

    private void failInsertAfterSuccessfulPrecheck(DataIntegrityViolationException failure) {
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
        when(repository.existsByCloudAccountIdAndExternalResourceId(7L, EXTERNAL_ID)).thenReturn(false);
        when(repository.saveAndFlush(any())).thenThrow(failure);
    }

    private DataIntegrityViolationException integrityFailure(SQLException sqlFailure, String constraint) {
        var hibernateFailure = new org.hibernate.exception.ConstraintViolationException("Integrity failure",
                sqlFailure, constraint);
        return new DataIntegrityViolationException("Could not execute statement", hibernateFailure);
    }

    private SQLException h2DuplicateFailure() throws Exception {
        String url = "jdbc:h2:mem:resource-service-" + UUID.randomUUID() + ";MODE=PostgreSQL";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            Flyway.configure().dataSource(url, "sa", "").load().migrate();
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO cloud_accounts (id, name, provider, external_account_id, environment, region,
                                                    active, created_at, updated_at)
                        VALUES (7, 'Account', 'AZURE', 'subscription-1', 'PRODUCTION', 'global',
                                TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """);
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO cloud_resources (cloud_account_id, external_resource_id, name, category,
                                                 provider_service, region, status, created_at, updated_at)
                    VALUES (7, ?, 'Resource', 'COMPUTE', 'Virtual Machines', 'global', 'ACTIVE',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """)) {
                statement.setString(1, EXTERNAL_ID);
                statement.executeUpdate();
                return assertThrows(SQLException.class, statement::executeUpdate);
            }
        }
    }

    private CloudResourceRequest request(Long accountId, String externalId) {
        return new CloudResourceRequest(accountId, externalId, "  Resource  ", ResourceCategory.COMPUTE,
                "  Virtual Machines  ", "global", ResourceStatus.ACTIVE);
    }

    private UpdateCloudResourceRequest updateRequest() {
        return new UpdateCloudResourceRequest("  Updated resource  ", ResourceCategory.STORAGE,
                "  Blob Storage  ", "westeurope", ResourceStatus.INACTIVE);
    }

    private CloudResourceQuery defaultQuery() {
        return new CloudResourceQuery(null, null, null, null, null, null, null, null);
    }

    private CloudAccount account(Long id, String name, CloudProvider provider) {
        var value = new CloudAccount(name, provider, "account-" + id, CloudEnvironment.PRODUCTION, "westeurope");
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private CloudResource resource() {
        var value = new CloudResource(account, EXTERNAL_ID, "Resource", ResourceCategory.COMPUTE,
                "Virtual Machines", "global", ResourceStatus.ACTIVE);
        stamp(value, 42L, CREATED, UPDATED);
        return value;
    }

    private void stamp(CloudResource resource, Long id, Instant createdAt, Instant updatedAt) {
        ReflectionTestUtils.setField(resource, "id", id);
        ReflectionTestUtils.setField(resource, "createdAt", createdAt);
        ReflectionTestUtils.setField(resource, "updatedAt", updatedAt);
    }

    private CloudResourceResponse expectedResponse() {
        return new CloudResourceResponse(42L, 7L, "Production Azure", CloudProvider.AZURE, EXTERNAL_ID, "Resource",
                ResourceCategory.COMPUTE, "Virtual Machines", "global", ResourceStatus.ACTIVE, CREATED, UPDATED);
    }
}
