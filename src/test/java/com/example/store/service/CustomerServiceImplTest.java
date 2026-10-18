package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.CustomerOrderDTO;
import com.example.store.entity.Customer;
import com.example.store.exception.InvalidSortPropertyException;
import com.example.store.mapper.CustomerMapper;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.view.CustomerOrderRow;
import com.example.store.repository.view.CustomerRow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock
    private CustomerRepository customerRepository;

    private CustomerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerServiceImpl(customerRepository, Mappers.getMapper(CustomerMapper.class));
    }

    @Test
    void createPersistsNameAndReturnsAssignedId() {
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> {
            Customer saved = invocation.getArgument(0);
            assertThat(saved.getId()).isNull();
            saved.setId(101L);
            return saved;
        });

        CustomerDTO result = service.create(new CreateCustomerRequest("Ada Lovelace"));

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Ada Lovelace");
        assertThat(result.id()).isEqualTo(101L);
        assertThat(result.name()).isEqualTo("Ada Lovelace");
        assertThat(result.orders()).isEmpty();
    }

    @Test
    void searchMatchesWordSubstringAndAttachesOrders() {
        Pageable pageable = PageRequest.of(0, 20);
        when(customerRepository.findRowsByNameContainingWord("onne", pageable))
                .thenReturn(new PageImpl<>(List.of(new CustomerRow(1L, "Muriel Donnelly")), pageable, 1));
        when(customerRepository.findOrderRows(List.of(1L)))
                .thenReturn(List.of(new CustomerOrderRow(1L, 43L, "Analytical Engine")));

        Page<CustomerDTO> result = service.search("onne", pageable);

        assertThat(result.getContent()).extracting(CustomerDTO::name).containsExactly("Muriel Donnelly");
        assertThat(result.getContent().get(0).orders()).containsExactly(new CustomerOrderDTO(43L, "Analytical Engine"));
    }

    @Test
    void searchEscapesLikeWildcardsInTheQuery() {
        Pageable pageable = PageRequest.of(0, 20);
        when(customerRepository.findRowsByNameContainingWord("50!%!_!!", pageable))
                .thenReturn(Page.empty(pageable));

        Page<CustomerDTO> result = service.search("50%_!", pageable);

        assertThat(result.getContent()).isEmpty();
        verify(customerRepository, never()).findOrderRows(any());
    }

    @Test
    void searchRejectsSortPropertiesOutsideTheWhitelist() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("orders.description"));

        assertThatThrownBy(() -> service.search("onne", pageable))
                .isInstanceOf(InvalidSortPropertyException.class)
                .hasMessage("Unknown sort property: orders.description");
        verify(customerRepository, never()).findRowsByNameContainingWord(any(), any());
    }

    @Test
    void findAllBuildsCustomersFromRowsAndGroupsOrdersByCustomer() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "name"));
        when(customerRepository.findRows(pageable))
                .thenReturn(new PageImpl<>(
                        List.of(new CustomerRow(1L, "Ada Lovelace"), new CustomerRow(2L, "Charles Babbage")),
                        pageable,
                        100));
        when(customerRepository.findOrderRows(List.of(1L, 2L)))
                .thenReturn(List.of(
                        new CustomerOrderRow(1L, 5L, "Analytical Engine"),
                        new CustomerOrderRow(1L, 7L, "Difference Engine")));

        Page<CustomerDTO> result = service.findAll(pageable);

        assertThat(result.getTotalElements()).isEqualTo(100);
        assertThat(result.getContent()).extracting(CustomerDTO::id).containsExactly(1L, 2L);
        assertThat(result.getContent().get(0).orders())
                .containsExactly(
                        new CustomerOrderDTO(5L, "Analytical Engine"), new CustomerOrderDTO(7L, "Difference Engine"));
        assertThat(result.getContent().get(1).orders()).isEmpty();
    }

    @Test
    void findAllIssuesNoOrdersQueryForAnEmptyPage() {
        Pageable pageable = PageRequest.of(50, 20);
        when(customerRepository.findRows(pageable)).thenReturn(Page.empty(pageable));

        Page<CustomerDTO> result = service.findAll(pageable);

        assertThat(result.getContent()).isEmpty();
        verify(customerRepository, never()).findOrderRows(any());
    }

    @Test
    void findAllRejectsSortPropertiesOutsideTheWhitelist() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("nope"));

        assertThatThrownBy(() -> service.findAll(pageable))
                .isInstanceOf(InvalidSortPropertyException.class)
                .hasMessage("Unknown sort property: nope");
        verify(customerRepository, never()).findRows(any());
    }
}
