package com.example.store.controller;

import com.example.store.config.JacksonConfig;
import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.CursorPageResponse;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.OrderCustomerDTO;
import com.example.store.dto.OrderDTO;
import com.example.store.dto.OrderFilter;
import com.example.store.dto.OrderProductDTO;
import com.example.store.exception.InvalidOrderFilterException;
import com.example.store.exception.InvalidSortPropertyException;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.service.OrderService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

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

@WebMvcTest(OrderController.class)
@Import(JacksonConfig.class)
class OrderControllerTests {

    private static final OrderDTO ORDER = new OrderDTO(
            1L, "Test Order", new OrderCustomerDTO(1L, "John Doe"), List.of(new OrderProductDTO(2L, "Rustic Chair")));
    private static final OrderFilter NO_FILTER = new OrderFilter(null, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void createReturnsCreatedOrder() throws Exception {
        when(orderService.create(new CreateOrderRequest("Test Order", 1L, List.of(2L))))
                .thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":[2]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/order/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.description").value("Test Order"))
                .andExpect(jsonPath("$.customer.name").value("John Doe"))
                .andExpect(jsonPath("$.products[0].id").value(2))
                .andExpect(jsonPath("$.products[0].description").value("Rustic Chair"));
    }

    @Test
    void createRejectsMissingCustomerId() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("customerId"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsOrderWithoutProducts() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productIds"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsMissingProductIds() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"No product list\",\"customerId\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productIds"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be empty"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsANullProductId() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Null product id\",\"customerId\":2,\"productIds\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productIds[0]"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be null"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsRepeatedProductIds() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Twice\",\"customerId\":1,\"productIds\":[2,2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productIds"))
                .andExpect(jsonPath("$.errors[0].message").value("must only contain unique elements"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsMoreThanOneHundredProducts() throws Exception {
        String productIds =
                LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(",", "[", "]"));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":" + productIds + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productIds"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsAControlCharacterInTheDescription() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Line one\\nLine two\",\"customerId\":5,\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].message").value("must not contain control characters"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsAFractionalCustomerId() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Fraction\",\"customerId\":5.7,\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request content."))
                .andExpect(jsonPath("$.errors[0].field").value("customerId"))
                .andExpect(jsonPath("$.errors[0].message").value("must be an integer"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsAnIntegralCustomerIdWrittenAsAFloat() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Float\",\"customerId\":5.0,\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customerId"));
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Exponent\",\"customerId\":5e0,\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customerId"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsANumericStringCustomerId() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Quoted\",\"customerId\":\"5\",\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customerId"))
                .andExpect(jsonPath("$.errors[0].message").value("must be an integer"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsAnEmptyStringCustomerIdAsNotAnInteger() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Empty\",\"customerId\":\"\",\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customerId"))
                .andExpect(jsonPath("$.errors[0].message").value("must be an integer"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsANumericDescription() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":123,\"customerId\":5,\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a string"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsFractionalAndQuotedProductIds() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Coerced\",\"customerId\":5,\"productIds\":[1.9,\"2\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productIds[0]"))
                .andExpect(jsonPath("$.errors[0].message").value("must be an integer"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createReportsOnlyTheFirstFieldThatDoesNotBind() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":123,\"customerId\":\"5\",\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("description"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsAProductListThatIsNotAnArray() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Not a list\",\"customerId\":1,\"productIds\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request content."))
                .andExpect(jsonPath("$.errors[0].field").value("productIds"))
                .andExpect(jsonPath("$.errors[0].message").value("must be an array"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsAProductIdThatIsNotANumber() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Not a number\",\"customerId\":1,\"productIds\":[\"x\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request content."))
                .andExpect(jsonPath("$.errors[0].field").value("productIds[0]"))
                .andExpect(jsonPath("$.errors[0].message").value("must be an integer"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsACustomerIdBeyondTheSixtyFourBitRange() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Big\",\"customerId\":9223372036854775808,\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request content."))
                .andExpect(jsonPath("$.errors[0].field").value("customerId"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a 64-bit integer"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsAProductIdBeyondTheSixtyFourBitRange() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Big\",\"customerId\":5,\"productIds\":[2,99999999999999999999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request content."))
                .andExpect(jsonPath("$.errors[0].field").value("productIds[1]"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a 64-bit integer"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsANumberBeyondTheParserLimitWithoutNamingAField() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Long\",\"customerId\":" + "9".repeat(1001)
                                + ",\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to read request"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsABodyThatIsNotAnObjectWithoutNamingAField() throws Exception {
        mockMvc.perform(post("/order").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to read request"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsContentAfterTheBody() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Trailing\",\"customerId\":5,\"productIds\":[2]} x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to read request"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Trailing\",\"customerId\":5,\"productIds\":[2]} {}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to read request"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsABodyThatRepeatsAProperty() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Dup\",\"customerId\":5,\"customerId\":6,\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to read request"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"A\",\"description\":\"B\",\"customerId\":5,\"productIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to read request"));
        verify(orderService, never()).create(any());
    }

    @Test
    void createRejectsAPropertyRepeatedAfterTheLastField() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Late\",\"customerId\":5,\"productIds\":[2],\"customerId\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to read request"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verify(orderService, never()).create(any());
    }

    @Test
    void createReturnsNotFoundForUnknownCustomer() throws Exception {
        when(orderService.create(new CreateOrderRequest("Test Order", 99L, List.of(2L))))
                .thenThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":99,\"productIds\":[2]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Customer with id 99 was not found"));
    }

    @Test
    void createReturnsConflictOnDataIntegrityViolation() throws Exception {
        when(orderService.create(any())).thenThrow(new DataIntegrityViolationException("constraint"));

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":[2]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void createWithANonJsonAcceptHeaderIsNotAcceptableBeforeTheOrderIsCreated() throws Exception {
        when(orderService.create(any())).thenReturn(ORDER);

        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content("{\"description\":\"Test Order\",\"customerId\":1,\"productIds\":[2]}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("not_acceptable"));
        verify(orderService, never()).create(any());
    }

    @Test
    void aNonJsonAcceptHeaderIsReportedBeforeTheBodyIsRead() throws Exception {
        mockMvc.perform(post("/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content("{\"broken\":"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("not_acceptable"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        when(orderService.findAll(any(), any())).thenThrow(new IllegalStateException("secret internal detail"));

        mockMvc.perform(get("/order"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void unknownSortPropertyIsABadRequest() throws Exception {
        when(orderService.findAll(any(), any())).thenThrow(new InvalidSortPropertyException("customer.name"));

        mockMvc.perform(get("/order").param("sort", "customer.name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Unknown sort property: customer.name"));
    }

    @Test
    void getByIdReturnsOrder() throws Exception {
        when(orderService.findById(1L)).thenReturn(ORDER);

        mockMvc.perform(get("/order/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.description").value("Test Order"))
                .andExpect(jsonPath("$.customer.name").value("John Doe"))
                .andExpect(jsonPath("$.products[0].id").value(2));
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
        mockMvc.perform(get("/order/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: 'abc'"));
        verify(orderService, never()).findById(any());
    }

    @Test
    void getByIdRejectsAHexadecimalId() throws Exception {
        when(orderService.findById(any())).thenReturn(ORDER);

        mockMvc.perform(get("/order/{id}", "0x1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: '0x1'"));
        verify(orderService, never()).findById(any());
    }

    @Test
    void getByIdRejectsAnIdWithAHashPrefix() throws Exception {
        when(orderService.findById(any())).thenReturn(ORDER);

        mockMvc.perform(get("/order/{id}", "#1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: '#1'"));
        verify(orderService, never()).findById(any());
    }

    @Test
    void getByIdRejectsAnIdWhoseDigitsAreSplitByASpace() throws Exception {
        when(orderService.findById(any())).thenReturn(ORDER);

        mockMvc.perform(get("/order/{id}", "1 0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: '1 0'"));
        verify(orderService, never()).findById(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"+1", "01", "\u0661", "\uFF11"})
    void getByIdRejectsAnIdThatIsNotInCanonicalDecimalForm(String id) throws Exception {
        when(orderService.findById(any())).thenReturn(ORDER);

        mockMvc.perform(get("/order/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: '" + id + "'"));
        verify(orderService, never()).findById(any());
    }

    @Test
    void getByIdRejectsAnIdBeyondTheSixtyFourBitRange() throws Exception {
        when(orderService.findById(any())).thenReturn(ORDER);

        mockMvc.perform(get("/order/{id}", "9223372036854775808"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: '9223372036854775808'"));
        verify(orderService, never()).findById(any());
    }

    @Test
    void getByIdWithANonJsonAcceptHeaderIsNotAcceptableBeforeTheLookup() throws Exception {
        when(orderService.findById(1L)).thenReturn(ORDER);

        mockMvc.perform(get("/order/1").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verify(orderService, never()).findById(any());
    }

    @Test
    void getByIdAcceptingOnlyAProblemDetailStillReachesTheHandler() throws Exception {
        when(orderService.findById(99L)).thenThrow(new ResourceNotFoundException("Order", 99L));

        mockMvc.perform(get("/order/99").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void getAllWithAfterReturnsACursorPage() throws Exception {
        when(orderService.findAfter(NO_FILTER, new CursorRequest(5L, 20)))
                .thenReturn(new CursorPageResponse<>(List.of(ORDER), 20, 1L));

        mockMvc.perform(get("/order").param("after", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].products[0].description").value("Rustic Chair"))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.nextCursor").value(1))
                .andExpect(jsonPath("$.totalElements").doesNotExist());
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllWithAfterSerialisesAnExhaustedCursorAsNull() throws Exception {
        when(orderService.findAfter(NO_FILTER, new CursorRequest(10000L, 5)))
                .thenReturn(new CursorPageResponse<>(List.of(), 5, null));

        mockMvc.perform(get("/order").param("after", "10000").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
    }

    @Test
    void getAllRejectsAfterCombinedWithPage() throws Exception {
        mockMvc.perform(get("/order").param("after", "0").param("page", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("after cannot be combined with page or sort"));
        verify(orderService, never()).findAfter(any(), any());
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsAfterCombinedWithSort() throws Exception {
        mockMvc.perform(get("/order").param("after", "0").param("sort", "id,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("after cannot be combined with page or sort"));
        verify(orderService, never()).findAfter(any(), any());
    }

    @Test
    void getAllRejectsNegativeAfter() throws Exception {
        mockMvc.perform(get("/order").param("after", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("after"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 0"));
        verify(orderService, never()).findAfter(any(), any());
    }

    @Test
    void getAllRejectsANegativePage() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 0"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsAZeroSize() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 1"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsAZeroSizeInCursorMode() throws Exception {
        when(orderService.findAfter(any(), any())).thenReturn(new CursorPageResponse<>(List.of(ORDER), 20, null));

        mockMvc.perform(get("/order").param("after", "0").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        verify(orderService, never()).findAfter(any(), any());
    }

    @Test
    void getAllRejectsANonNumericPage() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'page' with value: 'abc'"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsAPageWithALeadingSpace() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("page", " 2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'page' with value: ' 2'"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsAHexadecimalPage() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("page", "0x2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'page' with value: '0x2'"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsAHexadecimalSize() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("size", "0x10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'size' with value: '0x10'"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsAHexadecimalCursor() throws Exception {
        when(orderService.findAfter(any(), any())).thenReturn(new CursorPageResponse<>(List.of(ORDER), 20, null));

        mockMvc.perform(get("/order").param("after", "0x10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.detail").value("Failed to convert 'after' with value: '0x10'"));
        verify(orderService, never()).findAfter(any(), any());
    }

    @Test
    void getAllRejectsAHexadecimalCustomerId() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("customerId", "0x1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'customerId' with value: '0x1'"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsAProductIdWithAHashPrefix() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("productId", "#2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to convert 'productId' with value: '#2'"));
        verify(orderService, never()).findAll(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"customerId, +1", "productId, 02", "after, -0", "page, \u0661", "size, \uFF15"})
    void getAllRejectsANumberThatIsNotInCanonicalDecimalForm(String parameter, String value) throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));
        when(orderService.findAfter(any(), any())).thenReturn(new CursorPageResponse<>(List.of(ORDER), 20, null));

        mockMvc.perform(get("/order").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.detail")
                        .value("Failed to convert '" + parameter + "' with value: '" + value + "'"));
        verify(orderService, never()).findAll(any(), any());
        verify(orderService, never()).findAfter(any(), any());
    }

    @Test
    void getAllTreatsAnEmptyOrBlankFilterAndCursorAsAbsent() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(orderService.findAll(NO_FILTER, pageable)).thenReturn(new PageImpl<>(List.of(ORDER), pageable, 1));

        mockMvc.perform(get("/order")
                        .param("customerId", "")
                        .param("productId", " ")
                        .param("after", " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(orderService).findAll(NO_FILTER, pageable);
    }

    @Test
    void getAllRejectsAZeroCustomerId() throws Exception {
        mockMvc.perform(get("/order").param("customerId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("customerId"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 1"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsAZeroProductId() throws Exception {
        mockMvc.perform(get("/order").param("productId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productId"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsARepeatedCustomerId() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("customerId", "1", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'customerId' must not be repeated"))
                .andExpect(jsonPath("$.errors[0].field").value("customerId"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be repeated"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsARepeatedProductId() throws Exception {
        when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(List.of(ORDER)));

        mockMvc.perform(get("/order").param("productId", "2", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'productId' must not be repeated"))
                .andExpect(jsonPath("$.errors[0].field").value("productId"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be repeated"));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsARepeatedCursor() throws Exception {
        when(orderService.findAfter(any(), any())).thenReturn(new CursorPageResponse<>(List.of(ORDER), 20, null));

        mockMvc.perform(get("/order").param("after", "0", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'after' must not be repeated"))
                .andExpect(jsonPath("$.errors[0].field").value("after"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be repeated"));
        verify(orderService, never()).findAfter(any(), any());
    }

    @Test
    void getAllReportsARepeatedPageBeforeItsRange() throws Exception {
        mockMvc.perform(get("/order").param("page", "-1", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'page' must not be repeated"))
                .andExpect(jsonPath("$.errors.length()").value(1));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllAcceptsARepeatedSort() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("description"), Sort.Order.desc("id")));
        when(orderService.findAll(NO_FILTER, pageable)).thenReturn(new PageImpl<>(List.of(ORDER), pageable, 1));

        mockMvc.perform(get("/order").param("sort", "description,desc", "id,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));

        verify(orderService).findAll(NO_FILTER, pageable);
    }

    @Test
    void getAllPassesTheCustomerFilterToTheService() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(orderService.findAll(new OrderFilter(1L, null), pageable))
                .thenReturn(new PageImpl<>(List.of(ORDER), pageable, 1));

        mockMvc.perform(get("/order").param("customerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getAllPassesTheProductFilterToTheService() throws Exception {
        Pageable pageable = PageRequest.of(0, 5, Sort.by("id"));
        when(orderService.findAll(new OrderFilter(null, 2L), pageable))
                .thenReturn(new PageImpl<>(List.of(ORDER), pageable, 1));

        mockMvc.perform(get("/order").param("productId", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].products[0].id").value(2))
                .andExpect(jsonPath("$.size").value(5));
    }

    @Test
    void getAllWithAfterPassesTheFilterToTheCursorService() throws Exception {
        when(orderService.findAfter(new OrderFilter(1L, null), new CursorRequest(0L, 20)))
                .thenReturn(new CursorPageResponse<>(List.of(ORDER), 20, null));

        mockMvc.perform(get("/order").param("customerId", "1").param("after", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
        verify(orderService, never()).findAll(any(), any());
    }

    @Test
    void getAllRejectsCombinedFilters() throws Exception {
        when(orderService.findAll(any(), any())).thenThrow(new InvalidOrderFilterException());

        mockMvc.perform(get("/order").param("customerId", "1").param("productId", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("customerId and productId cannot be combined"));
    }

    @Test
    void getAllReturnsPageOfOrders() throws Exception {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(orderService.findAll(NO_FILTER, pageable)).thenReturn(new PageImpl<>(List.of(ORDER), pageable, 10000));

        mockMvc.perform(get("/order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Test Order"))
                .andExpect(jsonPath("$.content[0].customer.name").value("John Doe"))
                .andExpect(jsonPath("$.content[0].products[0].description").value("Rustic Chair"))
                .andExpect(jsonPath("$.totalElements").value(10000))
                .andExpect(jsonPath("$.totalPages").value(500));
    }
}
