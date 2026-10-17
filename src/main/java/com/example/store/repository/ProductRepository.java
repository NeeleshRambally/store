package com.example.store.repository;

import com.example.store.entity.Product;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Override
    @EntityGraph(attributePaths = "orders")
    Optional<Product> findById(Long id);

    @Query("select p from Product p left join fetch p.orders where p.id in :ids")
    List<Product> findAllWithOrdersByIdIn(@Param("ids") Collection<Long> ids);
}
