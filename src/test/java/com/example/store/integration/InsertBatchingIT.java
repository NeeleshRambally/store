package com.example.store.integration;

import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;
import com.example.store.repository.ProductRepository;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

// Proves that several orders flushed together become one batched insert per table instead of one insert per row.
class InsertBatchingIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void threeOrdersFlushedTogetherUseTwoStatementsPlusTheBlockAllocation() {
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        transactionTemplate.executeWithoutResult(status -> {
            Customer customer = customerRepository.getReferenceById(1L);
            List<Order> orders = LongStream.rangeClosed(1, 3)
                    .mapToObj(i -> {
                        Order order = new Order();
                        order.setDescription("Batched order " + i);
                        order.setCustomer(customer);
                        LongStream.rangeClosed(1, 3)
                                .mapToObj(productRepository::getReferenceById)
                                .forEach(order.getProducts()::add);
                        return order;
                    })
                    .toList();
            orderRepository.saveAll(orders);
            orderRepository.flush();
            status.setRollbackOnly();
        });

        assertThat(statistics.getEntityInsertCount()).isEqualTo(3);
        assertThat(statistics.getPrepareStatementCount()).isBetween(2L, 3L);
    }
}
