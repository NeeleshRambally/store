package com.example.store.mapper;

import com.example.store.dto.ProductDTO;
import com.example.store.entity.Order;
import com.example.store.entity.Product;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMapperTest {

    private final ProductMapper mapper = new ProductMapperImpl();

    @Test
    void orderCountFollowsTheOrdersCollection() {
        Product product = new Product();
        product.setId(3L);
        product.setDescription("Rustic Chair");
        product.getOrders().add(order(7L));
        product.getOrders().add(order(5L));

        ProductDTO dto = mapper.productToProductDTO(product);

        assertThat(dto.orders()).containsExactly(5L, 7L);
        assertThat(dto.orderCount()).isEqualTo(2L);
    }

    @Test
    void aNewProductHasNoOrders() {
        Product product = new Product();
        product.setId(101L);
        product.setDescription("Lamp");

        ProductDTO dto = mapper.productToProductDTO(product);

        assertThat(dto.orders()).isEmpty();
        assertThat(dto.orderCount()).isZero();
    }

    private static Order order(long id) {
        Order order = new Order();
        order.setId(id);
        return order;
    }
}
