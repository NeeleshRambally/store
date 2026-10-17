package com.example.store.controller;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderCustomerDTO;
import com.example.store.dto.OrderDTO;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.service.OrderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
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

@WebMvcTest(OrderController.class)
class OrderControllerTests {

    private static final OrderDTO ORDER = new OrderDTO(1L, "Test Order", new OrderCustomerDTO(1L, "John Doe"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void createReturnsCreatedOrder() throws Exception {
        when(orderService.create(new CreateOrderRequest("Test Order", 1L))).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.description").value("Test Order"))
                .andExpect(jsonPath("$.customer.name").value("John Doe"));
    }

    @Test
    void createRejectsMissingCustomerId() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("customerId"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createReturnsNotFoundForUnknownCustomer() throws Exception {
        when(orderService.create(new CreateOrderRequest("Test Order", 99L)))
                .thenThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Customer with id 99 was not found"));
    }

    @Test
    void createReturnsConflictOnDataIntegrityViolation() throws Exception {
        when(orderService.create(any())).thenThrow(new DataIntegrityViolationException("constraint"));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        when(orderService.findAll(any())).thenThrow(new IllegalStateException("secret internal detail"));

        mockMvc.perform(get("/order"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void getByIdReturnsOrder() throws Exception {
        when(orderService.findById(1L)).thenReturn(ORDER);

        mockMvc.perform(get("/order/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.description").value("Test Order"))
                .andExpect(jsonPath("$.customer.name").value("John Doe"));
    }

    @Test
    void getByIdReturnsNotFoundForUnknownOrder() throws Exception {
        when(orderService.findById(99L)).thenThrow(new ResourceNotFoundException("Order", 99L));

        mockMvc.perform(get("/order/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Order with id 99 was not found"));
    }

    @Test
    void getByIdRejectsNonNumericId() throws Exception {
        mockMvc.perform(get("/order/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void getAllReturnsPageOfOrders() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(orderService.findAll(pageable)).thenReturn(new PageImpl<>(List.of(ORDER), pageable, 10000));

        mockMvc.perform(get("/order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Test Order"))
                .andExpect(jsonPath("$.content[0].customer.name").value("John Doe"))
                .andExpect(jsonPath("$.totalElements").value(10000))
                .andExpect(jsonPath("$.totalPages").value(500));
    }
}
