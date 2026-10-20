package com.example.store.controller;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.ListResponse;
import com.example.store.dto.OrderDTO;
import com.example.store.dto.OrderFilter;
import com.example.store.dto.PageResponse;
import com.example.store.service.OrderService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
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
@RequestMapping(
        path = "/order",
        produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public ListResponse<OrderDTO> getAllOrders(
            @RequestParam(required = false) @Min(1) Long customerId,
            @RequestParam(required = false) @Min(1) Long productId,
            @RequestParam(required = false) @Min(0) Long after,
            @RequestParam(required = false) @Min(0) @Max(21474836) Integer page,
            @RequestParam(required = false) @Min(1) Integer size,
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        OrderFilter filter = new OrderFilter(customerId, productId);
        if (after == null) {
            return PageResponse.from(orderService.findAll(filter, pageable));
        }
        return orderService.findAfter(filter, CursorRequest.of(after, pageable));
    }

    @GetMapping("/{id}")
    public OrderDTO getOrder(@PathVariable Long id) {
        return orderService.findById(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderDTO> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderDTO created = orderService.create(request);
        return ResponseEntity.created(URI.create("/order/" + created.id())).body(created);
    }
}
