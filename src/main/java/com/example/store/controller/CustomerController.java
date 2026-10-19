package com.example.store.controller;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.ListResponse;
import com.example.store.dto.PageResponse;
import com.example.store.service.CustomerService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/customer")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public ListResponse<CustomerDTO> getAllCustomers(
            @RequestParam(required = false)
                    @Pattern(
                            regexp = "[^\\s\\p{Cntrl}]+",
                            message = "must be a single word without whitespace or control characters")
                    String name,
            @RequestParam(required = false) @Min(0) Long after,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) Integer size,
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        if (after == null) {
            Page<CustomerDTO> found =
                    name == null ? customerService.findAll(pageable) : customerService.search(name, pageable);
            return PageResponse.from(found);
        }
        CursorRequest request = CursorRequest.of(after, pageable);
        return name == null ? customerService.findAfter(request) : customerService.searchAfter(name, request);
    }

    @GetMapping("/{id}")
    public CustomerDTO getCustomer(@PathVariable Long id) {
        return customerService.findById(id);
    }

    @PostMapping
    public ResponseEntity<CustomerDTO> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerDTO created = customerService.create(request);
        return ResponseEntity.created(URI.create("/customer/" + created.id())).body(created);
    }
}
