package com.example.store.service;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.ProductDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductService {
    ProductDTO create(CreateProductRequest request);

    ProductDTO findById(Long id);

    Page<ProductDTO> findAll(Pageable pageable);
}
