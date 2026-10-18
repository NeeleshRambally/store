package com.example.store.service;

import com.example.store.config.NestedOrdersProperties;
import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.CursorPageResponse;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.entity.Product;
import com.example.store.exception.InvalidSortPropertyException;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.ProductMapper;
import com.example.store.repository.ProductRepository;
import com.example.store.repository.view.ProductOrderRow;
import com.example.store.repository.view.ProductRow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    private ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductServiceImpl(
                productRepository, Mappers.getMapper(ProductMapper.class), new NestedOrdersProperties(20));
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
        assertThat(result.orderCount()).isZero();
    }

    @Test
    void findByIdReturnsProductWithItsNewestOrderIdsAndTheTotal() {
        service = new ProductServiceImpl(
                productRepository, Mappers.getMapper(ProductMapper.class), new NestedOrdersProperties(2));
        when(productRepository.findRow(2L)).thenReturn(Optional.of(new ProductRow(2L, "Rustic Chair")));
        when(productRepository.findOrderRows(List.of(2L), 2))
                .thenReturn(List.of(new ProductOrderRow(2L, 1L, 3), new ProductOrderRow(2L, 43L, 3)));

        ProductDTO result = service.findById(2L);

        assertThat(result.description()).isEqualTo("Rustic Chair");
        assertThat(result.orders()).containsExactly(1L, 43L);
        assertThat(result.orderCount()).isEqualTo(3);
    }

    @Test
    void findByIdReturnsAProductWithoutOrders() {
        when(productRepository.findRow(9L)).thenReturn(Optional.of(new ProductRow(9L, "Lamp")));
        when(productRepository.findOrderRows(List.of(9L), 20)).thenReturn(List.of());

        ProductDTO result = service.findById(9L);

        assertThat(result.orders()).isEmpty();
        assertThat(result.orderCount()).isZero();
    }

    @Test
    void findByIdRejectsUnknownProduct() {
        when(productRepository.findRow(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product with id 99 was not found");
    }

    @Test
    void findAllBuildsProductsFromRowsAndGroupsOrderIdsByProduct() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "description"));
        Pageable tieBroken = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "description", "id"));
        when(productRepository.findRows(tieBroken))
                .thenReturn(new PageImpl<>(
                        List.of(new ProductRow(2L, "Rustic Chair"), new ProductRow(8L, "Oak Table")), tieBroken, 100));
        when(productRepository.findOrderRows(List.of(2L, 8L), 20))
                .thenReturn(List.of(new ProductOrderRow(2L, 1L, 2), new ProductOrderRow(2L, 43L, 2)));

        Page<ProductDTO> result = service.findAll(pageable);

        assertThat(result.getTotalElements()).isEqualTo(100);
        assertThat(result.getContent()).extracting(ProductDTO::id).containsExactly(2L, 8L);
        assertThat(result.getContent().get(0).description()).isEqualTo("Rustic Chair");
        assertThat(result.getContent().get(0).orders()).containsExactly(1L, 43L);
        assertThat(result.getContent().get(0).orderCount()).isEqualTo(2);
        assertThat(result.getContent().get(1).orders()).isEmpty();
        assertThat(result.getContent().get(1).orderCount()).isZero();
    }

    @Test
    void findAllIssuesNoOrdersQueryForAnEmptyPage() {
        Pageable pageable = PageRequest.of(50, 20);
        when(productRepository.findRows(pageable)).thenReturn(Page.empty(pageable));

        Page<ProductDTO> result = service.findAll(pageable);

        assertThat(result.getContent()).isEmpty();
        verify(productRepository, never()).findOrderRows(any(), anyInt());
    }

    @Test
    void findAfterTrimsTheExtraRowAndGroupsOrderIdsByProduct() {
        when(productRepository.findRowsAfter(1L, Limit.of(3)))
                .thenReturn(List.of(
                        new ProductRow(2L, "Rustic Chair"),
                        new ProductRow(8L, "Oak Table"),
                        new ProductRow(9L, "Lamp")));
        when(productRepository.findOrderRows(List.of(2L, 8L), 20))
                .thenReturn(List.of(new ProductOrderRow(2L, 1L, 2), new ProductOrderRow(2L, 43L, 2)));

        CursorPageResponse<ProductDTO> result = service.findAfter(new CursorRequest(1L, 2));

        assertThat(result.content()).extracting(ProductDTO::id).containsExactly(2L, 8L);
        assertThat(result.content().get(0).orders()).containsExactly(1L, 43L);
        assertThat(result.content().get(1).orders()).isEmpty();
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.nextCursor()).isEqualTo(8L);
    }

    @Test
    void findAfterReturnsNoCursorWhenExactlySizeRowsRemain() {
        when(productRepository.findRowsAfter(1L, Limit.of(3)))
                .thenReturn(List.of(new ProductRow(2L, "Rustic Chair"), new ProductRow(8L, "Oak Table")));
        when(productRepository.findOrderRows(List.of(2L, 8L), 20)).thenReturn(List.of());

        CursorPageResponse<ProductDTO> result = service.findAfter(new CursorRequest(1L, 2));

        assertThat(result.content()).extracting(ProductDTO::id).containsExactly(2L, 8L);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void findAfterIssuesNoOrdersQueryPastTheEnd() {
        when(productRepository.findRowsAfter(100L, Limit.of(21))).thenReturn(List.of());

        CursorPageResponse<ProductDTO> result = service.findAfter(new CursorRequest(100L, 20));

        assertThat(result.content()).isEmpty();
        verify(productRepository, never()).findOrderRows(any(), anyInt());
    }

    @Test
    void findAllRejectsSortPropertiesOutsideTheWhitelist() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("orders"));

        assertThatThrownBy(() -> service.findAll(pageable))
                .isInstanceOf(InvalidSortPropertyException.class)
                .hasMessage("Unknown sort property: orders");
        verify(productRepository, never()).findRows(any());
    }
}
