package com.example.store.controller;

import com.example.store.config.JacksonConfig;
import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CursorPageResponse;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.CustomerOrderDTO;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.service.CustomerService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
@Import(JacksonConfig.class)
class CustomerControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    @Test
    void createReturnsCreatedCustomer() throws Exception {
        when(customerService.create(new CreateCustomerRequest("John Doe")))
                .thenReturn(new CustomerDTO(1L, "John Doe", List.of(), 0));

        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"John Doe\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/customer/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.orderCount").value(0));
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
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Unknown property 'id'"))
                .andExpect(jsonPath("$.errors[0].field").value("id"))
                .andExpect(jsonPath("$.errors[0].message").value("is not a known property"));
        verify(customerService, never()).create(any());
    }

    @Test
    void createRejectsABooleanName() throws Exception {
        when(customerService.create(any())).thenReturn(new CustomerDTO(1L, "true", List.of(), 0));

        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a string"));
        verify(customerService, never()).create(any());
    }

    @Test
    void createRejectsANameThatIsNotAString() throws Exception {
        mockMvc.perform(post("/customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":[\"John Doe\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request content."))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a string"));
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
    void getByIdReturnsCustomerWithOrdersAndTheTotal() throws Exception {
        when(customerService.findById(1L))
                .thenReturn(new CustomerDTO(1L, "John Doe", List.of(new CustomerOrderDTO(43L, "Engine")), 7));

        mockMvc.perform(get("/customer/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.orders[0].id").value(43))
                .andExpect(jsonPath("$.orders[0].description").value("Engine"))
                .andExpect(jsonPath("$.orderCount").value(7));
    }

    @Test
    void getByIdReturnsNotFoundForUnknownCustomer() throws Exception {
        when(customerService.findById(99L)).thenThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(get("/customer/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Customer with id 99 was not found"));
    }

    @Test
    void getByIdRejectsNonNumericId() throws Exception {
        mockMvc.perform(get("/customer/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: 'abc'"));
        verify(customerService, never()).findById(any());
    }

    @Test
    void getAllReturnsPageOfCustomers() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(customerService.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(new CustomerDTO(1L, "John Doe", List.of(), 0)), pageable, 100));

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
                .thenReturn(new PageImpl<>(List.of(new CustomerDTO(1L, "Muriel Donnelly", List.of(), 0)), pageable, 1));

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
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        verify(customerService, never()).search(any(), any());
    }

    @Test
    void getAllRejectsEmptyNameSearch() throws Exception {
        mockMvc.perform(get("/customer").param("name", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        verify(customerService, never()).search(any(), any());
        verify(customerService, never()).findAll(any());
    }

    @Test
    void getAllRejectsMultiWordNameSearch() throws Exception {
        mockMvc.perform(get("/customer").param("name", "Muriel Donnelly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be a single word without whitespace or control characters"));
        verify(customerService, never()).search(any(), any());
    }

    @Test
    void getAllRejectsANulCharacterInTheName() throws Exception {
        when(customerService.search(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/customer").param("name", "a\u0000b"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be a single word without whitespace or control characters"));
        verify(customerService, never()).search(any(), any());
    }

    @Test
    void getAllRejectsADeleteCharacterInTheName() throws Exception {
        when(customerService.search(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/customer").param("name", "a\u007fb"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        verify(customerService, never()).search(any(), any());
    }

    @Test
    void getAllTreatsAC1ControlCharacterAsPartOfTheWord() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(customerService.search("a\u0085b", pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        mockMvc.perform(get("/customer").param("name", "a\u0085b")).andExpect(status().isOk());

        verify(customerService).search("a\u0085b", pageable);
    }

    @Test
    void getAllWithAfterReturnsACursorPage() throws Exception {
        when(customerService.findAfter(new CursorRequest(0L, 5)))
                .thenReturn(new CursorPageResponse<>(List.of(new CustomerDTO(1L, "John Doe", List.of(), 0)), 5, 1L));

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
                .thenReturn(new CursorPageResponse<>(
                        List.of(new CustomerDTO(7L, "Muriel Donnelly", List.of(), 0)), 20, null));

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
    void getAllRejectsANegativePage() throws Exception {
        when(customerService.findAll(any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/customer").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 0"));
        verify(customerService, never()).findAll(any());
    }

    @Test
    void getAllRejectsAZeroSize() throws Exception {
        when(customerService.findAll(any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/customer").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 1"));
        verify(customerService, never()).findAll(any());
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
