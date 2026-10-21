package com.example.store.service;

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Deletes the keys whose ttl has passed, in a transaction of its own, every purge interval after the same delay.
@Slf4j
@Component
public class IdempotencyKeyPurge {

    private final IdempotencyKeyStore store;
    private final TransactionTemplate transaction;

    public IdempotencyKeyPurge(IdempotencyKeyStore store, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(
            fixedDelayString = "${store.idempotency.purge-interval}",
            initialDelayString = "${store.idempotency.purge-interval}")
    public void purgeExpiredKeys() {
        Integer purged = transaction.execute(status -> store.purgeExpired());
        log.debug("Purged {} expired idempotency keys", purged);
    }
}
