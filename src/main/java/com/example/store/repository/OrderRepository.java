package com.example.store.repository;

import com.example.store.entity.Order;
import com.example.store.repository.view.OrderProductRow;
import com.example.store.repository.view.OrderRow;

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

    @Query("select new com.example.store.repository.view.OrderProductRow(o.id, p.id, p.description)"
            + " from Order o join o.products p where o.id in :ids order by o.id, p.id")
    List<OrderProductRow> findProductRows(@Param("ids") Collection<Long> ids);
}
