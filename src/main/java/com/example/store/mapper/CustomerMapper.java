package com.example.store.mapper;

import com.example.store.dto.CustomerDTO;
import com.example.store.entity.Customer;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CustomerMapper {
    @Mapping(target = "orderCount", expression = "java(customer.getOrders().size())")
    CustomerDTO customerToCustomerDTO(Customer customer);
}
