package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;

import java.util.List;

public interface CustomerService {
    CustomerDTO create(CreateCustomerRequest request);

    List<CustomerDTO> findAll();
}
