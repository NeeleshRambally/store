package com.example.store.repository;

import com.example.store.entity.Product;
import com.example.store.repository.view.ProductOrderRow;
import com.example.store.repository.view.ProductRow;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("select new com.example.store.repository.view.ProductRow(p.id, p.description) from Product p"
            + " where p.id = :id")
    Optional<ProductRow> findRow(@Param("id") long id);

    @Query(
            value = "select new com.example.store.repository.view.ProductRow(p.id, p.description) from Product p",
            countQuery = "select count(p) from Product p")
    Page<ProductRow> findRows(Pageable pageable);

    @Query("select new com.example.store.repository.view.ProductRow(p.id, p.description) from Product p"
            + " where p.id > :after order by p.id")
    List<ProductRow> findRowsAfter(@Param("after") long after, Limit limit);

    @Query(
            nativeQuery = true,
            value = "select product_id as \"productId\", order_id as \"orderId\", total as \"orderCount\""
                    + " from (select op.product_id, op.order_id,"
                    + " row_number() over (partition by op.product_id order by op.order_id desc) as rn,"
                    + " count(*) over (partition by op.product_id) as total from order_product op"
                    + " where op.product_id in (:ids)) t where rn <= :max order by product_id, order_id")
    List<ProductOrderRow> findOrderRows(@Param("ids") Collection<Long> ids, @Param("max") int max);
}
