package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomerService {
    CustomerDTO create(CreateCustomerRequest request);

    Page<CustomerDTO> findAll(Pageable pageable);

    Page<CustomerDTO> search(String nameWord, Pageable pageable);
}
