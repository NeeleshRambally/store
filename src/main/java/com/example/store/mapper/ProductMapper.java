package com.example.store.mapper;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.entity.Order;
import com.example.store.entity.Product;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Collection;
import java.util.List;

@Mapper(componentModel = "spring")
public interface ProductMapper {
    @Mapping(target = "orderCount", constant = "0L")
    ProductDTO productToProductDTO(Product product);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "orders", ignore = true)
    Product toEntity(CreateProductRequest request);

    default List<Long> orderIds(Collection<Order> orders) {
        return orders.stream().map(Order::getId).sorted().toList();
    }
}
