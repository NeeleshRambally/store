package com.example.store.controller;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CursorPageResponse;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.service.CustomerService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
class CustomerControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    @Test
    void createReturnsCreatedCustomer() throws Exception {
        when(customerService.create(new CreateCustomerRequest("John Doe")))
                .thenReturn(new CustomerDTO(1L, "John Doe", List.of()));

        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"John Doe\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.orders").isEmpty());
    }

    @Test
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        verify(customerService, never()).create(any());
    }

    @Test
    void createRejectsUnknownProperties() throws Exception {
        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":5,\"name\":\"John Doe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verify(customerService, never()).create(any());
    }

    @Test
    void createRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getAllReturnsPageOfCustomers() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(customerService.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(new CustomerDTO(1L, "John Doe", List.of())), pageable, 100));

        mockMvc.perform(get("/customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("John Doe"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(100))
                .andExpect(jsonPath("$.totalPages").value(5));
    }

    @Test
    void getAllWithNameSearchesByWordSubstring() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(customerService.search("onne", pageable))
                .thenReturn(new PageImpl<>(List.of(new CustomerDTO(1L, "Muriel Donnelly", List.of())), pageable, 1));

        mockMvc.perform(get("/customer").param("name", "onne"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Muriel Donnelly"))
                .andExpect(jsonPath("$.totalElements").value(1));
        verify(customerService, never()).findAll(any());
    }

    @Test
    void getAllRejectsBlankNameSearch() throws Exception {
        mockMvc.perform(get("/customer").param("name", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verify(customerService, never()).search(any(), any());
    }

    @Test
    void getAllRejectsMultiWordNameSearch() throws Exception {
        mockMvc.perform(get("/customer").param("name", "Muriel Donnelly")).andExpect(status().isBadRequest());
        verify(customerService, never()).search(any(), any());
    }

    @Test
    void getAllWithAfterReturnsACursorPage() throws Exception {
        when(customerService.findAfter(new CursorRequest(0L, 5)))
                .thenReturn(new CursorPageResponse<>(List.of(new CustomerDTO(1L, "John Doe", List.of())), 5, 1L));

        mockMvc.perform(get("/customer").param("after", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("John Doe"))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.nextCursor").value(1))
                .andExpect(jsonPath("$.page").doesNotExist());
        verify(customerService, never()).findAll(any());
    }

    @Test
    void getAllWithNameAndAfterSearchesAfterTheCursor() throws Exception {
        when(customerService.searchAfter("onne", new CursorRequest(3L, 20)))
                .thenReturn(
                        new CursorPageResponse<>(List.of(new CustomerDTO(7L, "Muriel Donnelly", List.of())), 20, null));

        mockMvc.perform(get("/customer").param("name", "onne").param("after", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
        verify(customerService, never()).search(any(), any());
        verify(customerService, never()).findAfter(any());
    }

    @Test
    void getAllRejectsAfterCombinedWithPage() throws Exception {
        mockMvc.perform(get("/customer").param("after", "0").param("page", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("after cannot be combined with page or sort"));
        verify(customerService, never()).findAfter(any());
    }

    @Test
    void getAllForwardsPagingAndSortParameters() throws Exception {
        when(customerService.findAll(any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/customer").param("page", "2").param("size", "5").param("sort", "name,desc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(customerService).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "name"));
    }
}
