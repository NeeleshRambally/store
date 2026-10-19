package com.example.store.service;

import lombok.RequiredArgsConstructor;

import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Evicts after commit; a read that missed before the commit can still re-cache the old value for up to CACHE_TTL.
@Component
@RequiredArgsConstructor
public class ProductCacheEvictor {

    private final CacheManager cacheManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        event.productIds().forEach(cacheManager.getCache("products")::evict);
    }
}
