package com.example.store.service;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Deletes the keys whose ttl has passed, in a transaction of its own, every purge interval after the same delay; a
// duty of the serving process, not of the migration job, which runs without a web server.
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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
