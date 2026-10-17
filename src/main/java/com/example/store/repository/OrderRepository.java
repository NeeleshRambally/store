package com.example.store.repository;

import com.example.store.entity.Order;

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
    @EntityGraph(attributePaths = "customer")
    Page<Order> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"customer", "products"})
    Optional<Order> findById(Long id);

    @Query("select o from Order o left join fetch o.products where o.id in :ids")
    List<Order> findAllWithProductsByIdIn(@Param("ids") Collection<Long> ids);
}
