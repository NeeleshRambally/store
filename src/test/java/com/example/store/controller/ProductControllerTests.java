package com.example.store.controller;

import com.example.store.config.JacksonConfig;
import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.CursorPageResponse;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.ProductDTO;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.service.ProductService;

import org.apache.catalina.Globals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import(JacksonConfig.class)
class ProductControllerTests {

    private static final ProductDTO PRODUCT = new ProductDTO(2L, "Rustic Chair", List.of(1L, 43L), 2);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void createReturnsCreatedProduct() throws Exception {
        when(productService.create(new CreateProductRequest("Rustic Chair")))
                .thenReturn(new ProductDTO(101L, "Rustic Chair", List.of(), 0));

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Rustic Chair\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/products/101"))
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.description").value("Rustic Chair"))
                .andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.orderCount").value(0));
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
    void createRejectsAControlCharacterInTheDescription() throws Exception {
        when(productService.create(any())).thenReturn(PRODUCT);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Rustic\\tChair\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].message").value("must not contain control characters"));
        verify(productService, never()).create(any());
    }

    @Test
    void createRejectsClientSuppliedId() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":5,\"description\":\"Rustic Chair\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown property 'id'"))
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[0].message").value("is not a known property"));
        verify(productService, never()).create(any());
    }

    @Test
    void createRejectsAFractionalDescription() throws Exception {
        when(productService.create(any())).thenReturn(PRODUCT);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a string"));
        verify(productService, never()).create(any());
    }

    @Test
    void createWithANonJsonAcceptHeaderIsNotAcceptableBeforeTheProductIsCreated() throws Exception {
        when(productService.create(any())).thenReturn(PRODUCT);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content("{\"description\":\"Rustic Chair\"}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("not_acceptable"));
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
    void getByIdRejectsAHexadecimalId() throws Exception {
        when(productService.findById(any())).thenReturn(PRODUCT);

        mockMvc.perform(get("/products/{id}", "0x2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: '0x2'"));
        verify(productService, never()).findById(any());
    }

    @Test
    void getByIdRejectsAnIdInFullwidthDigits() throws Exception {
        when(productService.findById(any())).thenReturn(PRODUCT);

        mockMvc.perform(get("/products/{id}", "\uFF12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: '\uFF12'"));
        verify(productService, never()).findById(any());
    }

    @Test
    void getAllReturnsPageOfProducts() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(productService.findAll(pageable)).thenReturn(new PageImpl<>(List.of(PRODUCT), pageable, 100));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].orders.length()").value(2))
                .andExpect(jsonPath("$.content[0].orderCount").value(2))
                .andExpect(jsonPath("$.totalElements").value(100));
    }

    @Test
    void getAllRejectsANegativePage() throws Exception {
        when(productService.findAll(any())).thenReturn(new PageImpl<>(List.of(PRODUCT)));

        mockMvc.perform(get("/products").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 0"));
        verify(productService, never()).findAll(any());
    }

    @Test
    void getAllRejectsAPageAboveTheBound() throws Exception {
        when(productService.findAll(any())).thenReturn(new PageImpl<>(List.of(PRODUCT)));

        mockMvc.perform(get("/products").param("page", "21474837"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("must be less than or equal to 21474836"));
        verify(productService, never()).findAll(any());
    }

    @Test
    void getAllRejectsAZeroSize() throws Exception {
        when(productService.findAll(any())).thenReturn(new PageImpl<>(List.of(PRODUCT)));

        mockMvc.perform(get("/products").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 1"));
        verify(productService, never()).findAll(any());
    }

    @Test
    void getAllRejectsAHexadecimalPage() throws Exception {
        when(productService.findAll(any())).thenReturn(new PageImpl<>(List.of(PRODUCT)));

        mockMvc.perform(get("/products").param("page", "0x2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'page' with value: '0x2'"));
        verify(productService, never()).findAll(any());
    }

    @Test
    void getAllRejectsACursorWithAHashPrefix() throws Exception {
        when(productService.findAfter(any())).thenReturn(new CursorPageResponse<>(List.of(PRODUCT), 20, null));

        mockMvc.perform(get("/products").param("after", "#1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'after' with value: '#1'"));
        verify(productService, never()).findAfter(any());
    }

    @Test
    void getAllRejectsARepeatedPage() throws Exception {
        when(productService.findAll(any())).thenReturn(new PageImpl<>(List.of(PRODUCT)));

        mockMvc.perform(get("/products").param("page", "1", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'page' must not be repeated"))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be repeated"));
        verify(productService, never()).findAll(any());
    }

    @Test
    void getAllRejectsAQueryStringTomcatCouldNotParse() throws Exception {
        when(productService.findAll(any())).thenReturn(new PageImpl<>(List.of(PRODUCT)));

        mockMvc.perform(get("/products").param("page", "0").requestAttr(Globals.PARAMETER_PARSE_FAILED_ATTR, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The query string could not be parsed"))
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        verify(productService, never()).findAll(any());
    }

    @Test
    void getAllWithAfterReturnsACursorPage() throws Exception {
        when(productService.findAfter(new CursorRequest(1L, 20)))
                .thenReturn(new CursorPageResponse<>(List.of(PRODUCT), 20, 2L));

        mockMvc.perform(get("/products").param("after", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].orders[1]").value(43))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.nextCursor").value(2))
                .andExpect(jsonPath("$.totalPages").doesNotExist());
        verify(productService, never()).findAll(any());
    }

    @Test
    void getAllRejectsAfterCombinedWithSort() throws Exception {
        mockMvc.perform(get("/products").param("after", "0").param("sort", "description"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("after cannot be combined with page or sort"));
        verify(productService, never()).findAfter(any());
    }
}
