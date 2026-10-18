package com.example.store.mapper;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderCustomerDTO;
import com.example.store.dto.OrderDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderDTO orderToOrderDTO(Order order);

    OrderCustomerDTO customerToOrderCustomerDTO(Customer customer);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "products", ignore = true)
    Order toEntity(CreateOrderRequest request);
}
