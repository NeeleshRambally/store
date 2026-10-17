package com.example.store.service;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.entity.Product;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.ProductMapper;
import com.example.store.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @Override
    @Transactional
    public ProductDTO create(CreateProductRequest request) {
        return productMapper.productToProductDTO(productRepository.save(productMapper.toEntity(request)));
    }

    @Override
    public ProductDTO findById(Long id) {
        return productRepository
                .findById(id)
                .map(productMapper::productToProductDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    @Override
    public Page<ProductDTO> findAll(Pageable pageable) {
        Page<Product> page = productRepository.findAll(pageable);
        if (page.hasContent()) {
            productRepository.findAllWithOrdersByIdIn(
                    page.getContent().stream().map(Product::getId).toList());
        }
        return page.map(productMapper::productToProductDTO);
    }
}
