package com.example.store.service;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.CursorPageResponse;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.ProductMapper;
import com.example.store.repository.ProductRepository;
import com.example.store.repository.view.ProductOrderRow;
import com.example.store.repository.view.ProductRow;

import lombok.RequiredArgsConstructor;

import org.springframework.cache.annotation.Cacheable;
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
public class ProductServiceImpl implements ProductService {

    private static final SortPolicy SORT_POLICY = SortPolicy.of("id", "description");

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @Override
    @Transactional
    public ProductDTO create(CreateProductRequest request) {
        return productMapper.productToProductDTO(productRepository.save(productMapper.toEntity(request)));
    }

    @Override
    @Cacheable("products")
    public ProductDTO findById(Long id) {
        return productRepository
                .findById(id)
                .map(productMapper::productToProductDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    @Override
    public Page<ProductDTO> findAll(Pageable pageable) {
        Page<ProductRow> page = productRepository.findRows(SORT_POLICY.validate(pageable));
        Map<Long, List<Long>> orders = orderIdsByProduct(page.getContent());
        return page.map(row -> toProduct(row, orders));
    }

    @Override
    public CursorPageResponse<ProductDTO> findAfter(CursorRequest request) {
        List<ProductRow> rows = productRepository.findRowsAfter(request.after(), Limit.of(request.size() + 1));
        return CursorPageResponse.from(rows, request.size(), ProductRow::id, this::toProducts);
    }

    private List<ProductDTO> toProducts(List<ProductRow> rows) {
        Map<Long, List<Long>> orders = orderIdsByProduct(rows);
        return rows.stream().map(row -> toProduct(row, orders)).toList();
    }

    private static ProductDTO toProduct(ProductRow row, Map<Long, List<Long>> orders) {
        return new ProductDTO(row.id(), row.description(), orders.getOrDefault(row.id(), List.of()), 0);
    }

    private Map<Long, List<Long>> orderIdsByProduct(List<ProductRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> orders = new LinkedHashMap<>();
        for (ProductOrderRow row : productRepository.findOrderRows(
                rows.stream().map(ProductRow::id).toList())) {
            orders.computeIfAbsent(row.productId(), id -> new ArrayList<>()).add(row.orderId());
        }
        return orders;
    }
}
