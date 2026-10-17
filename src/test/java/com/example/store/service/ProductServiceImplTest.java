package com.example.store.service;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.entity.Order;
import com.example.store.entity.Product;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.ProductMapper;
import com.example.store.repository.ProductRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    private ProductServiceImpl service;

    private Product product;

    @BeforeEach
    void setUp() {
        service = new ProductServiceImpl(productRepository, Mappers.getMapper(ProductMapper.class));
        product = new Product();
        product.setId(2L);
        product.setDescription("Rustic Chair");
        Order first = new Order();
        first.setId(43L);
        Order second = new Order();
        second.setId(1L);
        product.getOrders().add(first);
        product.getOrders().add(second);
    }

    @Test
    void createPersistsDescriptionAndReturnsAssignedId() {
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product saved = invocation.getArgument(0);
            assertThat(saved.getId()).isNull();
            saved.setId(101L);
            return saved;
        });

        ProductDTO result = service.create(new CreateProductRequest("Rustic Chair"));

        assertThat(result.id()).isEqualTo(101L);
        assertThat(result.description()).isEqualTo("Rustic Chair");
        assertThat(result.orders()).isEmpty();
    }

    @Test
    void findByIdReturnsProductWithSortedOrderIds() {
        when(productRepository.findById(2L)).thenReturn(Optional.of(product));

        ProductDTO result = service.findById(2L);

        assertThat(result.description()).isEqualTo("Rustic Chair");
        assertThat(result.orders()).containsExactly(1L, 43L);
    }

    @Test
    void findByIdRejectsUnknownProduct() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product with id 99 was not found");
    }

    @Test
    void findAllPagesProductsAndFetchesTheirOrdersInOneQuery() {
        Pageable pageable = PageRequest.of(0, 20);
        when(productRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(product), pageable, 100));
        when(productRepository.findAllWithOrdersByIdIn(List.of(2L))).thenReturn(List.of(product));

        Page<ProductDTO> result = service.findAll(pageable);

        verify(productRepository).findAllWithOrdersByIdIn(List.of(2L));
        assertThat(result.getTotalElements()).isEqualTo(100);
        assertThat(result.getContent().get(0).orders()).containsExactly(1L, 43L);
    }
}
