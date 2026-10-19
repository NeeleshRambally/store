package com.example.store.integration;

import com.example.store.entity.Product;
import com.example.store.repository.OrderRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Forces sequential scans and nested loops so order 15's join rows arrive in stored order: 16, then 6.
class OrderRepositoryIT extends AbstractIntegrationTest {

    private static final List<String> PLANNER_SETTINGS = List.of(
            "enable_indexscan", "enable_bitmapscan", "enable_indexonlyscan", "enable_hashjoin", "enable_mergejoin");

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findByIdSortsTheProductsByIdWhenTheJoinRowsArriveInStoredOrder() {
        assertThat(jdbcTemplate.queryForList(
                        "select product_id from order_product where order_id = 15 order by ctid", Long.class))
                .containsExactly(16L, 6L);

        List<Long> productIds = transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            PLANNER_SETTINGS.forEach(setting -> entityManager
                    .createNativeQuery("set local " + setting + " = off")
                    .executeUpdate());
            return orderRepository.findById(15L).orElseThrow().getProducts().stream()
                    .map(Product::getId)
                    .toList();
        });

        assertThat(productIds).containsExactly(6L, 16L);
    }
}
