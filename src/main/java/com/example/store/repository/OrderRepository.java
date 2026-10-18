package com.example.store.repository;

import com.example.store.entity.Order;
import com.example.store.repository.view.OrderProductRow;
import com.example.store.repository.view.OrderRow;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Override
    @EntityGraph(attributePaths = {"customer", "products"})
    Optional<Order> findById(Long id);

    @Query(
            value = "select new com.example.store.repository.view.OrderRow(o.id, o.description, c.id, c.name)"
                    + " from Order o join o.customer c",
            countQuery = "select count(o) from Order o")
    Page<OrderRow> findRows(Pageable pageable);

    @Query("select new com.example.store.repository.view.OrderRow(o.id, o.description, c.id, c.name)"
            + " from Order o join o.customer c where o.id > :after order by o.id")
    List<OrderRow> findRowsAfter(@Param("after") long after, Limit limit);

    @Query(
            value = "select new com.example.store.repository.view.OrderRow(o.id, o.description, c.id, c.name)"
                    + " from Order o join o.customer c where c.id = :customerId",
            countQuery = "select count(o) from Order o where o.customer.id = :customerId")
    Page<OrderRow> findRowsByCustomer(@Param("customerId") long customerId, Pageable pageable);

    @Query("select new com.example.store.repository.view.OrderRow(o.id, o.description, c.id, c.name)"
            + " from Order o join o.customer c where c.id = :customerId and o.id > :after order by o.id")
    List<OrderRow> findRowsByCustomerAfter(
            @Param("customerId") long customerId, @Param("after") long after, Limit limit);

    @Query(
            value = "select new com.example.store.repository.view.OrderRow(o.id, o.description, c.id, c.name)"
                    + " from Order o join o.customer c join o.products p where p.id = :productId",
            countQuery = "select count(o) from Order o join o.products p where p.id = :productId")
    Page<OrderRow> findRowsByProduct(@Param("productId") long productId, Pageable pageable);

    @Query("select new com.example.store.repository.view.OrderRow(o.id, o.description, c.id, c.name)"
            + " from Order o join o.customer c join o.products p where p.id = :productId and o.id > :after"
            + " order by o.id")
    List<OrderRow> findRowsByProductAfter(@Param("productId") long productId, @Param("after") long after, Limit limit);

    @Query("select new com.example.store.repository.view.OrderProductRow(o.id, p.id, p.description)"
            + " from Order o join o.products p where o.id in :ids order by o.id, p.id")
    List<OrderProductRow> findProductRows(@Param("ids") Collection<Long> ids);
}
