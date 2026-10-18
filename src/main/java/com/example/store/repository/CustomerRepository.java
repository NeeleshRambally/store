package com.example.store.repository;

import com.example.store.entity.Customer;
import com.example.store.repository.view.CustomerOrderRow;
import com.example.store.repository.view.CustomerRow;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    @Query(
            value = "select new com.example.store.repository.view.CustomerRow(c.id, c.name) from Customer c",
            countQuery = "select count(c) from Customer c")
    Page<CustomerRow> findRows(Pageable pageable);

    @Query(
            value = "select new com.example.store.repository.view.CustomerRow(c.id, c.name) from Customer c"
                    + " where lower(c.name) like lower(concat('%', :word, '%')) escape '!'",
            countQuery = "select count(c) from Customer c"
                    + " where lower(c.name) like lower(concat('%', :word, '%')) escape '!'")
    Page<CustomerRow> findRowsByNameContainingWord(@Param("word") String escapedWord, Pageable pageable);

    @Query("select new com.example.store.repository.view.CustomerRow(c.id, c.name) from Customer c"
            + " where c.id > :after order by c.id")
    List<CustomerRow> findRowsAfter(@Param("after") long after, Limit limit);

    @Query("select new com.example.store.repository.view.CustomerRow(c.id, c.name) from Customer c"
            + " where lower(c.name) like lower(concat('%', :word, '%')) escape '!' and c.id > :after order by c.id")
    List<CustomerRow> findRowsByNameContainingWordAfter(
            @Param("word") String escapedWord, @Param("after") long after, Limit limit);

    @Query(
            nativeQuery = true,
            value = "select customer_id as \"customerId\", id as \"orderId\", description as \"description\","
                    + " total as \"orderCount\" from (select o.customer_id, o.id, o.description,"
                    + " row_number() over (partition by o.customer_id order by o.id desc) as rn,"
                    + " count(*) over (partition by o.customer_id) as total from \"order\" o"
                    + " where o.customer_id in (:ids)) t where rn <= :max order by customer_id, id")
    List<CustomerOrderRow> findOrderRows(@Param("ids") Collection<Long> ids, @Param("max") int max);
}
