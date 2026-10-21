package com.example.store.service;

import com.example.store.config.IdempotencyProperties;
import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.IdempotencyKey;
import com.example.store.exception.IdempotencyKeyReusedException;
import com.example.store.exception.IdempotencyRequestInProgressException;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.service.IdempotencyKeyStore.StoredKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentRequestsTest {

    private static final IdempotencyKey KEY = new IdempotencyKey("customer-7f3c");
    private static final CreateCustomerRequest REQUEST = new CreateCustomerRequest("Ada Lovelace");
    private static final CustomerDTO CREATED = new CustomerDTO(101L, "Ada Lovelace", List.of(), 0);
    private static final String CREATED_JSON = "{\"id\":101,\"name\":\"Ada Lovelace\",\"orders\":[],\"orderCount\":0}";
    private static final IdempotencyProperties PROPERTIES =
            new IdempotencyProperties(Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofMinutes(10));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Logger logger = (Logger) LoggerFactory.getLogger(IdempotentRequests.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @Mock
    private IdempotencyKeyStore store;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private Supplier<ResponseEntity<CustomerDTO>> action;

    private IdempotentRequests requests;
    private String fingerprint;

    @BeforeEach
    void setUp() {
        requests = new IdempotentRequests(
                store, new AnonymousClientIdentity(), objectMapper, PROPERTIES, transactionManager);
        fingerprint = RequestFingerprint.of(objectMapper.writer(), "/customer", REQUEST);
        lenient()
                .when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void runsTheActionAloneWithoutAKey() {
        ResponseEntity<CustomerDTO> created = created();
        when(action.get()).thenReturn(created);

        ResponseEntity<CustomerDTO> response = requests.execute(null, "/customer", REQUEST, CustomerDTO.class, action);

        assertThat(response).isSameAs(created);
        verifyNoInteractions(store, transactionManager);
    }

    @Test
    void claimsTheKeyInItsOwnTransactionThenRecordsTheResponseWithTheCreate() {
        when(store.claim(
                        "anonymous",
                        "customer-7f3c",
                        fingerprint,
                        "/customer",
                        Duration.ofHours(24),
                        Duration.ofSeconds(30)))
                .thenReturn(1);
        ResponseEntity<CustomerDTO> created = created();
        when(action.get()).thenReturn(created);

        ResponseEntity<CustomerDTO> response = requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action);

        assertThat(response).isSameAs(created);
        InOrder inOrder = inOrder(store, transactionManager, action);
        inOrder.verify(store).claim(any(), any(), any(), any(), any(), any());
        inOrder.verify(transactionManager).commit(any());
        inOrder.verify(action).get();
        inOrder.verify(store).complete("anonymous", "customer-7f3c", 201, "/customer/101", CREATED_JSON);
        inOrder.verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
        verify(store, never()).release(any(), any());
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .extracting(TransactionDefinition::getPropagationBehavior)
                .containsExactly(
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW, TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void recordsNoLocationWhenTheResponseCarriesNone() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(action.get()).thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(CREATED));

        requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action);

        verify(store).complete("anonymous", "customer-7f3c", 201, null, CREATED_JSON);
    }

    @Test
    void replaysACompletedKeyWithoutRunningTheAction() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(store.find("anonymous", "customer-7f3c"))
                .thenReturn(Optional.of(new StoredKey(fingerprint, "COMPLETED", 201, "/customer/101", CREATED_JSON)));

        ResponseEntity<CustomerDTO> response = requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create("/customer/101"));
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(response.getBody()).isEqualTo(CREATED);
        verifyNoInteractions(action);
        verify(store, never()).complete(any(), any(), anyInt(), any(), any());
        verify(transactionManager, times(1)).commit(any());
    }

    @Test
    void replaysWithoutALocationWhenNoneWasStored() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(store.find(any(), any()))
                .thenReturn(Optional.of(new StoredKey(fingerprint, "COMPLETED", 201, null, CREATED_JSON)));

        ResponseEntity<CustomerDTO> response = requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNull();
        assertThat(response.getBody()).isEqualTo(CREATED);
    }

    @Test
    void rejectsAKeyReusedWithAnotherRequestWhateverItsState() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(store.find(any(), any()))
                .thenReturn(Optional.of(new StoredKey("0".repeat(64), "IN_PROGRESS", null, null, null)));

        assertThatThrownBy(() -> requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verifyNoInteractions(action);
    }

    @Test
    void answersInProgressWhileTheFirstRequestStillRuns() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(store.find(any(), any()))
                .thenReturn(Optional.of(new StoredKey(fingerprint, "IN_PROGRESS", null, null, null)));

        assertThatThrownBy(() -> requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
        verifyNoInteractions(action);
    }

    @Test
    void answersInProgressWhenTheRowVanishesBetweenTheClaimAndTheRead() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(store.find(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
        verifyNoInteractions(action);
        verify(transactionManager).rollback(any());
    }

    @Test
    void releasesTheKeyInItsOwnTransactionAndRethrowsWhenTheActionFails() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        ResourceNotFoundException failure = new ResourceNotFoundException("Customer", 99L);
        when(action.get()).thenThrow(failure);

        assertThatThrownBy(() -> requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action))
                .isSameAs(failure);

        verify(store).release("anonymous", "customer-7f3c");
        verify(store, never()).complete(any(), any(), anyInt(), any(), any());
        verify(transactionManager, times(1)).rollback(any());
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(3)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .extracting(TransactionDefinition::getPropagationBehavior)
                .containsExactly(
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                        TransactionDefinition.PROPAGATION_REQUIRED,
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void releasesTheKeyWhenTheResponseCannotBeRecorded() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(action.get()).thenReturn(created());
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("connection lost");
        when(store.complete(any(), any(), anyInt(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action))
                .isSameAs(failure);

        verify(store).release("anonymous", "customer-7f3c");
        verify(transactionManager).rollback(any());
    }

    @Test
    void releasesTheKeyWhenTheResponseCannotBeWrittenAsJson() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        Supplier<ResponseEntity<Object>> unwritable =
                () -> ResponseEntity.created(URI.create("/customer/101")).body(new Object());

        assertThatThrownBy(() -> requests.execute(KEY, "/customer", REQUEST, Object.class, unwritable))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);

        verify(store).release("anonymous", "customer-7f3c");
        verify(store, never()).complete(any(), any(), anyInt(), any(), any());
    }

    @Test
    void logsAReleaseThatFailsAtWarnAndStillRethrowsTheOriginalFailure() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        ResourceNotFoundException failure = new ResourceNotFoundException("Customer", 99L);
        when(action.get()).thenThrow(failure);
        when(store.release(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("Connection reset by peer"));

        assertThatThrownBy(() -> requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action))
                .isSameAs(failure);

        assertThat(event(Level.WARN).getFormattedMessage())
                .contains("org.springframework.dao.DataAccessResourceFailureException")
                .doesNotContain("Connection reset");
        assertThat(event(Level.DEBUG).getThrowableProxy().getMessage()).contains("Connection reset by peer");
    }

    @Test
    void failsWhenAStoredResponseCannotBeReadBack() {
        when(store.claim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(store.find(any(), any()))
                .thenReturn(Optional.of(new StoredKey(fingerprint, "COMPLETED", 201, "/customer/101", "not json")));

        assertThatThrownBy(() -> requests.execute(KEY, "/customer", REQUEST, CustomerDTO.class, action))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    private static ResponseEntity<CustomerDTO> created() {
        return ResponseEntity.created(URI.create("/customer/101")).body(CREATED);
    }

    private ILoggingEvent event(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + level + " event; got " + appender.list));
    }
}
