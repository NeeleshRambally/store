package com.example.store.service;

import com.example.store.config.IdempotencyProperties;
import com.example.store.dto.IdempotencyKey;
import com.example.store.exception.IdempotencyKeyReusedException;
import com.example.store.exception.IdempotencyRequestInProgressException;
import com.example.store.service.IdempotencyKeyStore.StoredKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;
import java.util.function.Supplier;

// Claims the key in a transaction of its own, runs the create and records its response in a second one, so the
// resource and the stored response are committed together, and answers a repeat from the stored row.
@Slf4j
@Component
public class IdempotentRequests {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IdempotencyKeyStore store;
    private final ClientIdentity clientIdentity;
    private final ObjectWriter writer;
    private final ObjectReader reader;
    private final IdempotencyProperties properties;
    private final TransactionTemplate ownTransaction;
    private final TransactionTemplate createTransaction;

    public IdempotentRequests(
            IdempotencyKeyStore store,
            ClientIdentity clientIdentity,
            ObjectMapper objectMapper,
            IdempotencyProperties properties,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.clientIdentity = clientIdentity;
        this.writer = objectMapper.writer();
        this.reader = objectMapper.reader();
        this.properties = properties;
        this.ownTransaction = new TransactionTemplate(
                transactionManager, new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        this.createTransaction = new TransactionTemplate(transactionManager);
    }

    public <T> ResponseEntity<T> execute(
            IdempotencyKey key,
            String path,
            Object request,
            Class<T> responseType,
            Supplier<ResponseEntity<T>> action) {
        if (key == null) {
            return action.get();
        }
        String client = clientIdentity.id();
        UUID token = UUID.randomUUID();
        String fingerprint = RequestFingerprint.of(writer, path, request);
        StoredKey live = ownTransaction.execute(status -> claimOrLiveRow(client, key, token, fingerprint, path));
        if (live != null) {
            return replay(live, fingerprint, responseType);
        }
        try {
            return createTransaction.execute(status -> record(client, key, token, path, action.get()));
        } catch (RuntimeException ex) {
            release(client, key, token);
            throw ex;
        }
    }

    private StoredKey claimOrLiveRow(String client, IdempotencyKey key, UUID token, String fingerprint, String path) {
        if (store.claim(client, key.value(), token, fingerprint, path, properties.ttl(), properties.staleAfter())
                == 1) {
            return null;
        }
        return store.find(client, key.value()).orElseThrow(IdempotencyRequestInProgressException::new);
    }

    private <T> ResponseEntity<T> replay(StoredKey stored, String fingerprint, Class<T> responseType) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        if (!stored.completed()) {
            throw new IdempotencyRequestInProgressException();
        }
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(stored.responseStatus()).header(REPLAYED_HEADER, "true");
        if (stored.responseLocation() != null) {
            response.location(URI.create(stored.responseLocation()));
        }
        return response.body(read(stored.responseBody(), responseType));
    }

    private <T> ResponseEntity<T> record(
            String client, IdempotencyKey key, UUID token, String path, ResponseEntity<T> response) {
        URI location = response.getHeaders().getLocation();
        int recorded = store.complete(
                client,
                key.value(),
                token,
                response.getStatusCode().value(),
                location == null ? null : location.toString(),
                write(response.getBody()));
        if (recorded == 0) {
            log.warn(
                    "Idempotency key for {} taken over while its request was still running; the create is rolled back",
                    path);
            throw new IdempotencyRequestInProgressException();
        }
        return response;
    }

    private void release(String client, IdempotencyKey key, UUID token) {
        try {
            ownTransaction.executeWithoutResult(status -> store.release(client, key.value(), token));
        } catch (RuntimeException releaseFailure) {
            log.warn(
                    "Idempotency key not released after a failed request: {}",
                    releaseFailure.getClass().getName());
            log.debug("Idempotency key not released", releaseFailure);
        }
    }

    private String write(Object body) {
        try {
            return writer.writeValueAsString(body);
        } catch (IOException e) {
            throw new UncheckedIOException("The response could not be written as JSON", e);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return reader.readValue(body, type);
        } catch (IOException e) {
            throw new UncheckedIOException("The stored response could not be read as JSON", e);
        }
    }
}
