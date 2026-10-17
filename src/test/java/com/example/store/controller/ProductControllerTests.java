package com.example.store.controller;

import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.service.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTests {

    private static final ProductDTO PRODUCT = new ProductDTO(2L, "Rustic Chair", List.of(1L, 43L));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void createReturnsCreatedProduct() throws Exception {
        when(productService.create(new CreateProductRequest("Rustic Chair")))
                .thenReturn(new ProductDTO(101L, "Rustic Chair", List.of()));

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Rustic Chair\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.description").value("Rustic Chair"))
                .andExpect(jsonPath("$.orders").isEmpty());
    }

    @Test
    void createRejectsBlankDescription() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"));
        verify(productService, never()).create(any());
    }

    @Test
    void createRejectsClientSuppliedId() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":5,\"description\":\"Rustic Chair\"}"))
                .andExpect(status().isBadRequest());
        verify(productService, never()).create(any());
    }

    @Test
    void getByIdReturnsProductWithOrderIds() throws Exception {
        when(productService.findById(2L)).thenReturn(PRODUCT);

        mockMvc.perform(get("/products/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Rustic Chair"))
                .andExpect(jsonPath("$.orders[0]").value(1))
                .andExpect(jsonPath("$.orders[1]").value(43));
    }

    @Test
    void getByIdReturnsNotFoundForUnknownProduct() throws Exception {
        when(productService.findById(99L)).thenThrow(new ResourceNotFoundException("Product", 99L));

        mockMvc.perform(get("/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Product with id 99 was not found"));
    }

    @Test
    void getAllReturnsPageOfProducts() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(productService.findAll(pageable)).thenReturn(new PageImpl<>(List.of(PRODUCT), pageable, 100));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].orders.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(100));
    }
}
