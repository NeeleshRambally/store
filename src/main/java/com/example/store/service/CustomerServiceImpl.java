package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CursorPageResponse;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.CustomerOrderDTO;
import com.example.store.mapper.CustomerMapper;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.view.CustomerOrderRow;
import com.example.store.repository.view.CustomerRow;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerServiceImpl implements CustomerService {

    private static final SortPolicy SORT_POLICY = SortPolicy.of("id", "name");

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    @Override
    @Transactional
    public CustomerDTO create(CreateCustomerRequest request) {
        return customerMapper.customerToCustomerDTO(customerRepository.save(customerMapper.toEntity(request)));
    }

    @Override
    public Page<CustomerDTO> findAll(Pageable pageable) {
        return withOrders(customerRepository.findRows(SORT_POLICY.validate(pageable)));
    }

    @Override
    public Page<CustomerDTO> search(String nameWord, Pageable pageable) {
        return withOrders(
                customerRepository.findRowsByNameContainingWord(escapeLike(nameWord), SORT_POLICY.validate(pageable)));
    }

    @Override
    public CursorPageResponse<CustomerDTO> findAfter(CursorRequest request) {
        List<CustomerRow> rows = customerRepository.findRowsAfter(request.after(), Limit.of(request.size() + 1));
        return CursorPageResponse.from(rows, request.size(), CustomerRow::id, this::withOrders);
    }

    @Override
    public CursorPageResponse<CustomerDTO> searchAfter(String nameWord, CursorRequest request) {
        List<CustomerRow> rows = customerRepository.findRowsByNameContainingWordAfter(
                escapeLike(nameWord), request.after(), Limit.of(request.size() + 1));
        return CursorPageResponse.from(rows, request.size(), CustomerRow::id, this::withOrders);
    }

    private Page<CustomerDTO> withOrders(Page<CustomerRow> page) {
        Map<Long, List<CustomerOrderDTO>> orders = ordersByCustomer(page.getContent());
        return page.map(row -> toCustomer(row, orders));
    }

    private List<CustomerDTO> withOrders(List<CustomerRow> rows) {
        Map<Long, List<CustomerOrderDTO>> orders = ordersByCustomer(rows);
        return rows.stream().map(row -> toCustomer(row, orders)).toList();
    }

    private static CustomerDTO toCustomer(CustomerRow row, Map<Long, List<CustomerOrderDTO>> orders) {
        return new CustomerDTO(row.id(), row.name(), orders.getOrDefault(row.id(), List.of()));
    }

    private Map<Long, List<CustomerOrderDTO>> ordersByCustomer(List<CustomerRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CustomerOrderDTO>> orders = new LinkedHashMap<>();
        for (CustomerOrderRow row : customerRepository.findOrderRows(
                rows.stream().map(CustomerRow::id).toList())) {
            orders.computeIfAbsent(row.customerId(), id -> new ArrayList<>())
                    .add(new CustomerOrderDTO(row.orderId(), row.description()));
        }
        return orders;
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
