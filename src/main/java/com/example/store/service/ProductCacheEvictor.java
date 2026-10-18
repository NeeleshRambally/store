package com.example.store.service;

import lombok.RequiredArgsConstructor;

import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Runs after the creating transaction commits, so a concurrent read never re-caches the pre-commit state.
@Component
@RequiredArgsConstructor
public class ProductCacheEvictor {

    private final CacheManager cacheManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        event.productIds().forEach(cacheManager.getCache("products")::evict);
    }
}
