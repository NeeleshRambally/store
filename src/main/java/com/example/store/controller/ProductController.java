package com.example.store.controller;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.ListResponse;
import com.example.store.dto.PageResponse;
import com.example.store.dto.ProductDTO;
import com.example.store.service.ProductService;

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
        path = "/products",
        produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ListResponse<ProductDTO> getAllProducts(
            @RequestParam(required = false) @Min(0) Long after,
            @RequestParam(required = false) @Min(0) @Max(21474836) Integer page,
            @RequestParam(required = false) @Min(1) Integer size,
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        if (after == null) {
            return PageResponse.from(productService.findAll(pageable));
        }
        return productService.findAfter(CursorRequest.of(after, pageable));
    }

    @GetMapping("/{id}")
    public ProductDTO getProduct(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProductDTO> createProduct(@Valid @RequestBody CreateProductRequest request) {
        ProductDTO created = productService.create(request);
        return ResponseEntity.created(URI.create("/products/" + created.id())).body(created);
    }
}
