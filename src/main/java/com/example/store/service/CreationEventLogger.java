package com.example.store.service;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// After commit only: the inserts are flushed at commit, so a line written in the service could name a row never stored.
@Slf4j
@Component
public class CreationEventLogger {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info(
                "Created order {} for customer {} with {} products",
                event.orderId(),
                event.customerId(),
                event.productIds().size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCustomerCreated(CustomerCreatedEvent event) {
        log.info("Created customer {}", event.customerId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductCreated(ProductCreatedEvent event) {
        log.info("Created product {}", event.productId());
    }
}
