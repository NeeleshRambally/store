package com.example.store.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCacheEvictorTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache products;

    @Test
    void evictsEveryProductOfTheCreatedOrder() {
        when(cacheManager.getCache("products")).thenReturn(products);

        new ProductCacheEvictor(cacheManager).onOrderCreated(new OrderCreatedEvent(List.of(2L, 8L)));

        verify(products).evict(2L);
        verify(products).evict(8L);
        verifyNoMoreInteractions(products);
    }
}
