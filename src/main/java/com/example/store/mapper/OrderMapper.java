package com.example.store.mapper;

import com.example.store.dto.OrderCustomerDTO;
import com.example.store.dto.OrderDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderDTO orderToOrderDTO(Order order);

    OrderCustomerDTO customerToOrderCustomerDTO(Customer customer);
}
