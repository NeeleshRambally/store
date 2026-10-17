package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.entity.Customer;
import com.example.store.mapper.CustomerMapper;
import com.example.store.repository.CustomerRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    @Override
    @Transactional
    public CustomerDTO create(CreateCustomerRequest request) {
        return customerMapper.customerToCustomerDTO(customerRepository.save(customerMapper.toEntity(request)));
    }

    @Override
    public Page<CustomerDTO> findAll(Pageable pageable) {
        Page<Customer> page = customerRepository.findAll(pageable);
        if (page.hasContent()) {
            customerRepository.findAllWithOrdersByIdIn(
                    page.getContent().stream().map(Customer::getId).toList());
        }
        return page.map(customerMapper::customerToCustomerDTO);
    }
}
