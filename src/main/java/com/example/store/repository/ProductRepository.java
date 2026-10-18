package com.example.store.repository;

import com.example.store.entity.Product;
import com.example.store.repository.view.ProductOrderRow;
import com.example.store.repository.view.ProductRow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query(
            value = "select new com.example.store.repository.view.ProductRow(p.id, p.description) from Product p",
            countQuery = "select count(p) from Product p")
    Page<ProductRow> findRows(Pageable pageable);

    @Query("select new com.example.store.repository.view.ProductOrderRow(p.id, o.id)"
            + " from Order o join o.products p where p.id in :ids order by p.id, o.id")
    List<ProductOrderRow> findOrderRows(@Param("ids") Collection<Long> ids);
}
