package com.example.store.integration;

import com.example.store.StoreApplication;
import com.example.store.config.IdempotencyProperties;
import com.example.store.config.IdempotencyStaleWindowGuard;
import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.IdempotencyKey;
import com.example.store.dto.ProductDTO;
import com.example.store.exception.IdempotencyRequestInProgressException;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.service.CustomerService;
import com.example.store.service.IdempotencyKeyPurge;
import com.example.store.service.IdempotentRequests;
import com.example.store.service.RequestFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.SneakyThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.testcontainers.containers.PostgreSQLContainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

// Keyed POSTs against the real key store: replay, reuse, release, expiry, takeover and the 409 of a request in flight.
class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotentRequests idempotentRequests;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private PostgreSQLContainer<?> container;

    @Autowired
    private IdempotencyKeyPurge purge;

    @Autowired
    private IdempotencyProperties properties;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private IdempotencyStaleWindowGuard staleWindowGuard;

    @ParameterizedTest(name = "POST {0}")
    @MethodSource("keyedCreations")
    void aRepeatWithTheSameKeyReplaysTheFirstResponseAndInsertsNothing(
            String path, Map<String, Object> body, String table, String column) {
        String key = "replay-" + UUID.randomUUID();
        String text = body.get(column).toString();

        ResponseEntity<String> first = post(path, body, key);
        ResponseEntity<String> repeat = post(path, body, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(repeat.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(repeat.getHeaders().getLocation())
                .isEqualTo(first.getHeaders().getLocation());
        assertThat(json(repeat)).isEqualTo(json(first));
        assertThat(json(repeat).get("id").asLong())
                .isEqualTo(json(first).get("id").asLong());
        assertThat(rows(table, column, text)).isEqualTo(1L);
        assertThat(storedStatus(key)).isEqualTo("COMPLETED");
    }

    static Stream<Arguments> keyedCreations() {
        return Stream.of(
                arguments("/customer", Map.of("name", "Replayed customer " + UUID.randomUUID()), "customer", "name"),
                arguments(
                        "/order",
                        Map.of(
                                "description",
                                "Replayed order " + UUID.randomUUID(),
                                "customerId",
                                1,
                                "productIds",
                                List.of(2, 8)),
                        "\"order\"",
                        "description"),
                arguments(
                        "/products",
                        Map.of("description", "Replayed product " + UUID.randomUUID()),
                        "product",
                        "description"));
    }

    @Test
    void aRepeatSentWithOtherWhitespaceAndPropertyOrderIsTheSameRequest() {
        String key = "canonical-" + UUID.randomUUID();
        String description = "Canonical order " + UUID.randomUUID();

        ResponseEntity<String> first = postJson(
                "/order", "{\"description\":\"" + description + "\",\"customerId\":1,\"productIds\":[2,8]}", key);
        ResponseEntity<String> repeat = postJson(
                "/order",
                "{ \"productIds\" : [ 2, 8 ],\n \"customerId\" : 1,\n \"description\" : \"" + description + "\" }",
                key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(repeat.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(rows("\"order\"", "description", description)).isEqualTo(1L);
    }

    @Test
    void aKeyReusedWithAnotherBodyIsUnprocessableAndInsertsNothing() {
        String key = "reuse-" + UUID.randomUUID();
        String name = "Reused key " + UUID.randomUUID();
        assertThat(post("/customer", Map.of("name", name), key).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> reuse = post("/customer", Map.of("name", name + " changed"), key);

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reuse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(json(reuse).get("code").asText()).isEqualTo("idempotency_key_reused");
        assertThat(json(reuse).get("detail").asText())
                .isEqualTo("Idempotency-Key was already used with a different request");
        assertThat(rows("customer", "name", name + " changed")).isZero();
        assertThat(storedStatus(key)).isEqualTo("COMPLETED");
    }

    @Test
    void aKeyReusedOnAnotherPathIsUnprocessable() {
        String key = "path-" + UUID.randomUUID();
        String text = "Same text " + UUID.randomUUID();
        assertThat(post("/products", Map.of("description", text), key).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> reuse = post("/customer", Map.of("name", text), key);

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(reuse).get("code").asText()).isEqualTo("idempotency_key_reused");
        assertThat(rows("customer", "name", text)).isZero();
    }

    @Test
    void aKeyWhoseCreateFailsIsReleasedForTheNextAttempt() {
        String key = "released-" + UUID.randomUUID();
        String description = "Released order " + UUID.randomUUID();

        ResponseEntity<String> failed =
                post("/order", Map.of("description", description, "customerId", 999999, "productIds", List.of(1)), key);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(failed).get("code").asText()).isEqualTo("not_found");
        assertThat(keyRows(key)).isZero();

        ResponseEntity<String> retried =
                post("/order", Map.of("description", description, "customerId", 1, "productIds", List.of(1)), key);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retried.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(rows("\"order\"", "description", description)).isEqualTo(1L);
    }

    @Test
    void aKeyWhoseInsertTheDatabaseRejectsIsReleasedAndNothingIsStored() {
        IdempotencyKey key = new IdempotencyKey("rejected-" + UUID.randomUUID());
        CreateCustomerRequest tooLongForTheColumn = new CreateCustomerRequest("x".repeat(256));

        assertThatThrownBy(() ->
                        idempotentRequests.execute(key, "/customer", tooLongForTheColumn, CustomerDTO.class, () -> {
                            CustomerDTO created = customerService.create(tooLongForTheColumn);
                            return ResponseEntity.created(URI.create("/customer/" + created.id()))
                                    .body(created);
                        }))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(keyRows(key.value())).isZero();
        assertThat(rows("customer", "name", tooLongForTheColumn.name())).isZero();
    }

    @Test
    void anExpiredKeyIsTakenOverByTheNextRequest() {
        String key = "expired-" + UUID.randomUUID();
        String name = "Expired key " + UUID.randomUUID();
        ResponseEntity<String> first = post("/customer", Map.of("name", name), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        jdbcTemplate.update(
                "update idempotency_key set expires_at = now() - interval '1 second' where idempotency_key = ?", key);

        ResponseEntity<String> again = post("/customer", Map.of("name", name), key);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(json(again).get("id").asLong())
                .isNotEqualTo(json(first).get("id").asLong());
        assertThat(rows("customer", "name", name)).isEqualTo(2L);
        assertThat(keyRows(key)).isEqualTo(1L);
        assertThat(post("/customer", Map.of("name", name), key).getHeaders().getFirst("Idempotent-Replayed"))
                .isEqualTo("true");
    }

    @Test
    void aKeyLeftInProgressBeyondTheStaleWindowIsTakenOver() {
        String key = "stale-" + UUID.randomUUID();
        String name = "Stale key " + UUID.randomUUID();
        jdbcTemplate.update(
                "insert into idempotency_key (client_id, idempotency_key, claim_token, request_fingerprint,"
                        + " request_path, status, created_at, expires_at)"
                        + " values ('anonymous', ?, gen_random_uuid(), ?, '/customer', 'IN_PROGRESS',"
                        + " now() - interval '31 seconds', now() + interval '1 day')",
                key,
                "0".repeat(64));

        ResponseEntity<String> taken = post("/customer", Map.of("name", name), key);

        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(taken.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(rows("customer", "name", name)).isEqualTo(1L);
        assertThat(storedStatus(key)).isEqualTo("COMPLETED");
    }

    @Test
    void aKeyStillInProgressWithinTheStaleWindowIsAConflictToRetryAfterASecond() {
        String key = "fresh-" + UUID.randomUUID();
        String name = "Fresh key " + UUID.randomUUID();
        jdbcTemplate.update(
                "insert into idempotency_key (client_id, idempotency_key, claim_token, request_fingerprint,"
                        + " request_path, status, created_at, expires_at)"
                        + " values ('anonymous', ?, gen_random_uuid(), ?, '/customer', 'IN_PROGRESS',"
                        + " now() - interval '5 seconds', now() + interval '1 day')",
                key,
                fingerprint("/customer", new CreateCustomerRequest(name)));

        ResponseEntity<String> conflict = post("/customer", Map.of("name", name), key);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(json(conflict).get("code").asText()).isEqualTo("idempotency_request_in_progress");
        assertThat(rows("customer", "name", name)).isZero();
    }

    @Test
    void aRepeatWhileTheFirstRequestIsStillRunningIsAConflict() throws Exception {
        IdempotencyKey key = new IdempotencyKey("running-" + UUID.randomUUID());
        CreateCustomerRequest request = new CreateCustomerRequest("Running " + UUID.randomUUID());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<CustomerDTO>> first = CompletableFuture.supplyAsync(
                () -> idempotentRequests.execute(key, "/customer", request, CustomerDTO.class, () -> {
                    started.countDown();
                    await(released);
                    CustomerDTO created = customerService.create(request);
                    return ResponseEntity.created(URI.create("/customer/" + created.id()))
                            .body(created);
                }));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> idempotentRequests.execute(
                        key, "/customer", request, CustomerDTO.class, () -> fail("the repeat must not create")))
                .isInstanceOf(IdempotencyRequestInProgressException.class);

        released.countDown();
        assertThat(first).succeedsWithin(Duration.ofSeconds(10)).satisfies(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        });
        ResponseEntity<CustomerDTO> replay = idempotentRequests.execute(
                key, "/customer", request, CustomerDTO.class, () -> fail("the replay must not create"));
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.get().getBody());
        assertThat(rows("customer", "name", request.name())).isEqualTo(1L);
    }

    @Test
    void aRepeatWhileTheFirstInsertWaitsForALockIsAConflictAndTheFirstStillCompletes() throws Exception {
        String key = "locked-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("name", "Locked " + UUID.randomUUID());
        CompletableFuture<ResponseEntity<String>> held;
        ResponseEntity<String> repeat;
        try (TableLock lock = TableLock.on(container, "customer")) {
            held = lock.holdWaiting(() -> post("/customer", body, key));
            repeat = post("/customer", body, key);
        }

        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(repeat.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(json(repeat).get("code").asText()).isEqualTo("idempotency_request_in_progress");
        assertThat(held).succeedsWithin(Duration.ofSeconds(30)).satisfies(first -> {
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        });
        assertThat(post("/customer", body, key).getHeaders().getFirst("Idempotent-Replayed"))
                .isEqualTo("true");
        assertThat(rows("customer", "name", body.get("name").toString())).isEqualTo(1L);
    }

    @Test
    void aKeyTakenOverWhileItsRequestStillRunsRollsThatRequestBackAndCreatesOnce() throws Exception {
        IdempotencyKey key = new IdempotencyKey("taken-" + UUID.randomUUID());
        CreateCustomerRequest request = new CreateCustomerRequest("Taken over " + UUID.randomUUID());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<CustomerDTO>> first = CompletableFuture.supplyAsync(
                () -> idempotentRequests.execute(key, "/customer", request, CustomerDTO.class, () -> {
                    started.countDown();
                    await(released);
                    CustomerDTO created = customerService.create(request);
                    return ResponseEntity.created(URI.create("/customer/" + created.id()))
                            .body(created);
                }));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        ageBeyondTheStaleWindow(key.value());

        ResponseEntity<String> second = post("/customer", Map.of("name", request.name()), key.value());
        released.countDown();

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(first)
                .failsWithin(Duration.ofSeconds(10))
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(IdempotencyRequestInProgressException.class);
        assertThat(rows("customer", "name", request.name())).isEqualTo(1L);
        assertThat(storedStatus(key.value())).isEqualTo("COMPLETED");
        ResponseEntity<String> replay = post("/customer", Map.of("name", request.name()), key.value());
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json(replay).get("id").asLong())
                .isEqualTo(json(second).get("id").asLong());
    }

    @Test
    void aReleaseAfterATakeoverLeavesTheNewClaimInPlace() throws Exception {
        IdempotencyKey key = new IdempotencyKey("kept-" + UUID.randomUUID());
        CreateCustomerRequest request = new CreateCustomerRequest("Kept claim " + UUID.randomUUID());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstReleased = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondReleased = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<CustomerDTO>> first = CompletableFuture.supplyAsync(
                () -> idempotentRequests.execute(key, "/customer", request, CustomerDTO.class, () -> {
                    firstStarted.countDown();
                    await(firstReleased);
                    throw new ResourceNotFoundException("Customer", 99L);
                }));
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
        ageBeyondTheStaleWindow(key.value());
        CompletableFuture<ResponseEntity<CustomerDTO>> second = CompletableFuture.supplyAsync(
                () -> idempotentRequests.execute(key, "/customer", request, CustomerDTO.class, () -> {
                    secondStarted.countDown();
                    await(secondReleased);
                    CustomerDTO created = customerService.create(request);
                    return ResponseEntity.created(URI.create("/customer/" + created.id()))
                            .body(created);
                }));
        assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();

        firstReleased.countDown();
        assertThat(first)
                .failsWithin(Duration.ofSeconds(10))
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(ResourceNotFoundException.class);

        assertThat(storedStatus(key.value())).isEqualTo("IN_PROGRESS");
        secondReleased.countDown();
        assertThat(second).succeedsWithin(Duration.ofSeconds(10)).satisfies(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        });
        assertThat(rows("customer", "name", request.name())).isEqualTo(1L);
        ResponseEntity<CustomerDTO> replay = idempotentRequests.execute(
                key, "/customer", request, CustomerDTO.class, () -> fail("the replay must not create"));
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(second.get().getBody());
    }

    @Test
    void theAfterCommitListenersStillRunForAKeyedOrder() {
        Long productId = restTemplate
                .postForEntity("/products", Map.of("description", "Keyed lamp " + UUID.randomUUID()), ProductDTO.class)
                .getBody()
                .id();
        assertThat(restTemplate
                        .getForEntity("/products/" + productId, ProductDTO.class)
                        .getBody()
                        .orders())
                .isEmpty();

        ResponseEntity<String> created = post(
                "/order",
                Map.of("description", "Keyed lamp order", "customerId", 1, "productIds", List.of(productId)),
                "evict-" + UUID.randomUUID());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(restTemplate
                        .getForEntity("/products/" + productId, ProductDTO.class)
                        .getBody()
                        .orders())
                .containsExactly(json(created).get("id").asLong());
    }

    @Test
    void aKeyedPostClaimsTheKeyInsertsTheRowAndRecordsTheResponseInThatOrder() {
        List<String> statements = statementsSentFor(
                () -> post("/customer", Map.of("name", "Ordered " + UUID.randomUUID()), "order-" + UUID.randomUUID()));

        assertThat(statements)
                .filteredOn(sql -> sql.contains("idempotency_key") || sql.contains("into customer"))
                .satisfiesExactly(
                        claim -> assertThat(claim)
                                .startsWith("insert into idempotency_key")
                                .contains("on conflict"),
                        insert -> assertThat(insert).startsWith("insert into customer"),
                        completion -> assertThat(completion).startsWith("update idempotency_key"));
    }

    @Test
    void thePurgeDeletesExpiredKeysAndKeepsLiveOnesInItsOwnTransaction() {
        String expired = "purge-expired-" + UUID.randomUUID();
        String live = "purge-live-" + UUID.randomUUID();
        insertCompletedKey(expired, "now() - interval '1 second'");
        insertCompletedKey(live, "now() + interval '1 day'");
        Logger logger = (Logger) LoggerFactory.getLogger(IdempotencyKeyPurge.class);
        Level level = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            purge.purgeExpiredKeys();
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
        }

        assertThat(keyRows(expired)).isZero();
        assertThat(keyRows(live)).isEqualTo(1L);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).matches("Purged [1-9][0-9]* expired idempotency keys");
        });
    }

    @Test
    void thePurgeIsScheduledAtTheConfiguredIntervalAfterTheSameInitialDelay() {
        List<FixedDelayTask> sweeps = scheduledTasks.getScheduledTasks().stream()
                .filter(task -> task.toString().equals(IdempotencyKeyPurge.class.getName() + ".purgeExpiredKeys"))
                .map(ScheduledTask::getTask)
                .map(FixedDelayTask.class::cast)
                .toList();

        assertThat(properties.purgeInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(sweeps).singleElement().satisfies(sweep -> {
            assertThat(sweep.getIntervalDuration()).isEqualTo(properties.purgeInterval());
            assertThat(sweep.getInitialDelayDuration()).isEqualTo(properties.purgeInterval());
        });
    }

    @Test
    void theSweepAndTheStaleWindowCheckStayOutOfAProcessWithoutAWebServer() {
        assertThat(staleWindowGuard).isNotNull();
        SpringApplicationBuilder job =
                new SpringApplicationBuilder(StoreApplication.class).web(WebApplicationType.NONE);

        try (ConfigurableApplicationContext context = job.run(
                "--spring.datasource.url=" + container.getJdbcUrl(),
                "--spring.datasource.username=" + container.getUsername(),
                "--spring.datasource.password=" + container.getPassword(),
                "--spring.liquibase.enabled=false")) {
            assertThat(context.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
            assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
            assertThat(context.getBeansOfType(IdempotencyKeyPurge.class)).isEmpty();
            assertThat(context.getBeansOfType(IdempotencyStaleWindowGuard.class))
                    .isEmpty();
        }
    }

    @Test
    void anUnkeyedPostStoresNoKey() {
        long before = jdbcTemplate.queryForObject("select count(*) from idempotency_key", Long.class);

        ResponseEntity<String> created = post("/customer", Map.of("name", "Unkeyed " + UUID.randomUUID()), null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from idempotency_key", Long.class))
                .isEqualTo(before);
    }

    @Test
    void keysAreComparedExactly() {
        String name = "Exact key " + UUID.randomUUID();
        String key = "Exact-" + UUID.randomUUID();

        assertThat(post("/customer", Map.of("name", name), key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> otherCase = post("/customer", Map.of("name", name), key.toLowerCase());

        assertThat(otherCase.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(otherCase.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(rows("customer", "name", name)).isEqualTo(2L);
    }

    private void ageBeyondTheStaleWindow(String key) {
        jdbcTemplate.update(
                "update idempotency_key set created_at = now() - interval '31 seconds'"
                        + " where client_id = 'anonymous' and idempotency_key = ?",
                key);
    }

    private void insertCompletedKey(String key, String expiresAt) {
        jdbcTemplate.update(
                "insert into idempotency_key (client_id, idempotency_key, claim_token, request_fingerprint,"
                        + " request_path, status, response_status, response_location, response_body, created_at,"
                        + " expires_at) values ('anonymous', ?, gen_random_uuid(), ?, '/customer', 'COMPLETED', 201,"
                        + " '/customer/1', '{}', now(), " + expiresAt + ")",
                key,
                "0".repeat(64));
    }

    private List<String> statementsSentFor(Runnable request) {
        Logger sql = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level level = sql.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sql.addAppender(appender);
        sql.setLevel(Level.DEBUG);
        try {
            request.run();
        } finally {
            sql.setLevel(level);
            sql.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body, String key) {
        return postJson(path, toJson(body), key);
    }

    private ResponseEntity<String> postJson(String path, String json, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set(IdempotencyKey.HEADER, key);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private Long rows(String table, String column, String value) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Long.class, value);
    }

    private Long keyRows(String key) {
        return jdbcTemplate.queryForObject(
                "select count(*) from idempotency_key where client_id = 'anonymous' and idempotency_key = ?",
                Long.class,
                key);
    }

    private String storedStatus(String key) {
        return jdbcTemplate.queryForObject(
                "select status from idempotency_key where client_id = 'anonymous' and idempotency_key = ?",
                String.class,
                key);
    }

    private String fingerprint(String path, Object request) {
        return RequestFingerprint.of(objectMapper.writer(), path, request);
    }

    @SneakyThrows
    private String toJson(Object body) {
        return objectMapper.writeValueAsString(body);
    }

    @SneakyThrows
    private JsonNode json(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody());
    }

    @SneakyThrows
    private static void await(CountDownLatch latch) {
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
