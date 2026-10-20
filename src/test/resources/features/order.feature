Feature: Order management
  As a store operator
  I want to place orders for existing customers
  So that each order is always attributed to a customer

  Scenario: Create an order for an existing customer with products
    When I create an order "Analytical Engine" for customer 1 with products 8 and 2
    Then the response status is 201
    And the response order is "Analytical Engine" for customer "Muriel Donnelly" with a generated id
    And the response order contains products 2 and 8

  Scenario: An order must contain at least one product
    When I create an order with payload:
      """
      {"description": "Empty", "customerId": 1, "productIds": []}
      """
    Then the response status is 400
    And the response problem code is "validation_failed"
    And the response problem lists a validation error for "productIds"

  Scenario: An order with more than 100 products is rejected
    When I create an order "Bulk" for customer 1 with 101 products
    Then the response status is 400
    And the response problem lists a validation error for "productIds"

  Scenario: An order with a repeated product is rejected
    When I create an order "Twice" for customer 1 with products 2 and 2
    Then the response status is 400
    And the response problem lists a validation error for "productIds"

  Scenario: An order without a product list is rejected
    When I create an order with payload:
      """
      {"description": "No product list", "customerId": 2}
      """
    Then the response status is 400
    And the response problem lists a validation error for "productIds"

  Scenario: An order with a null product id is rejected
    When I create an order with payload:
      """
      {"description": "Null product id", "customerId": 2, "productIds": [null]}
      """
    Then the response status is 400
    And the response problem lists a validation error for "productIds[0]"

  Scenario: An oversized order body is rejected
    When I create an order with a body of 65537 bytes
    Then the response status is 413
    And the response problem detail is "Request body exceeds 65536 bytes"

  Scenario: An order with an unknown product is rejected
    When I create an order "Analytical Engine" for customer 1 with products 2 and 999999
    Then the response status is 404
    And the response problem detail is "Product with id 999999 was not found"

  Scenario: An order for an unknown customer is rejected
    When I create an order "Analytical Engine" for customer 999999 with products 2 and 8
    Then the response status is 404
    And the response problem detail is "Customer with id 999999 was not found"

  Scenario: An order without a customer is rejected
    When I create an order with payload:
      """
      {"description": "Orphan", "productIds": [2]}
      """
    Then the response status is 400
    And the response problem lists a validation error for "customerId"

  Scenario: An order description containing a NUL character is rejected
    When I create an order with payload:
      """
      {"description": "Nul\u0000order", "customerId": 5, "productIds": [2]}
      """
    Then the response status is 400
    And the response problem lists a validation error for "description"

  Scenario: A customer id sent as a string is rejected
    When I create an order with payload:
      """
      {"description": "Quoted customer id", "customerId": "5", "productIds": [2]}
      """
    Then the response status is 400
    And the response problem lists a validation error for "customerId"

  Scenario: A customer id beyond the 64-bit range is rejected and named
    When I create an order with payload:
      """
      {"description": "Overflowing customer id", "customerId": 9223372036854775808, "productIds": [2]}
      """
    Then the response status is 400
    And the response problem detail is "Invalid request content."
    And the response problem lists a validation error for "customerId"

  Scenario: Content after the order body is rejected
    When I create an order with payload:
      """
      {"description": "Content after the body", "customerId": 5, "productIds": [2]} x
      """
    Then the response status is 400
    And the response problem detail is "Failed to read request"

  Scenario: An order requested as XML is rejected before it is created
    Given the orders described as "Requested as XML" are counted
    When I create an order "Requested as XML" for customer 5 with products 2 and 8 accepting "application/xml"
    Then the response status is 406
    And the response problem code is "not_acceptable"
    And the number of orders described as "Requested as XML" is unchanged

  Scenario: An order body that repeats a property is rejected
    When I create an order with payload:
      """
      {"description": "Repeated property", "customerId": 5, "customerId": 6, "productIds": [2]}
      """
    Then the response status is 400
    And the response problem detail is "Failed to read request"

  Scenario: Browse orders with their customer
    When I request orders with page 0 and size 20
    Then the response status is 200
    And the page has 20 orders starting at id 1
    And the first order on the page belongs to customer "Vicki Kutch"
    And every order on the page lists at least one product

  Scenario: A negative page number is rejected
    When I request orders with page -1 and size 20
    Then the response status is 400
    And the response problem lists a validation error for "page"

  Scenario: A page number above 21474836 is rejected
    When I request orders with page 21474837 and size 100
    Then the response status is 400
    And the response problem code is "validation_failed"
    And the response problem lists a validation error for "page"

  Scenario: Orders of one customer
    When I request orders of customer 1
    Then the response status is 200
    And every order on the page belongs to customer 1
    And every order on the page lists at least one product

  Scenario: Orders containing one product
    When I request orders containing product 2
    Then the response status is 200
    And every order on the page contains product 2

  Scenario: The customer and product filters cannot be combined
    When I request orders of customer 1 containing product 2
    Then the response status is 400
    And the response problem detail is "customerId and productId cannot be combined"

  Scenario: Sorting orders by a property outside the documented list is rejected
    When I request orders sorted by "customer.name"
    Then the response status is 400
    And the response problem detail is "Unknown sort property: customer.name"

  Scenario: Sorted pages break ties by id in the same direction
    When I request orders sorted by "description,desc" with page 0 and size 50
    Then the response status is 200
    And the page lists the orders in description descending order with ties broken by id descending

  Scenario: Fetch a single order by id
    When I request order 1
    Then the response status is 200
    And the response order is "Handcrafted Soft Chair" for customer "Vicki Kutch" with id 1
    And the response order contains products 2 and 8

  Scenario: An order's products are listed in ascending id order
    When I request order 15
    Then the response status is 200
    And the response order contains products 6 and 16

  Scenario: Fetching an unknown order is a not found
    When I request order 999999
    Then the response status is 404
    And the response problem code is "not_found"
    And the response problem detail is "Order with id 999999 was not found"

  Scenario: Fetching an order with a non-numeric id is rejected
    When I request order "abc"
    Then the response status is 400
    And the response problem detail is "Failed to convert 'id' with value: 'abc'"

  Scenario: Fetching an order with a hexadecimal id is rejected
    When I request order "0x1"
    Then the response status is 400
    And the response problem code is "invalid_parameter"
    And the response problem detail is "Failed to convert 'id' with value: '0x1'"

  Scenario: Fetching an order with a plus sign before its id is rejected
    When I request order "+1"
    Then the response status is 400
    And the response problem code is "invalid_parameter"
    And the response problem detail is "Failed to convert 'id' with value: '+1'"

  Scenario: Walk orders with a cursor
    When I request orders after 0 with size 5
    Then the response status is 200
    And the page has 5 orders starting at id 1
    And the next cursor is 5
    When I request orders after 5 with size 5
    Then the page has 5 orders starting at id 6
    And the next cursor is 10

  Scenario: A negative cursor is rejected
    When I request orders after -1 with size 5
    Then the response status is 400
    And the response problem lists a validation error for "after"

  Scenario: A cursor cannot be combined with a page number
    When I request orders after 0 on page 1
    Then the response status is 400
    And the response problem detail is "after cannot be combined with page or sort"

  Scenario: A statement cancelled while it waits for a lock is a retryable 503
    When I request orders with size 5 while the order table is locked and the waiting statement is cancelled
    Then the response status is 503
    And the response problem code is "database_timeout"
    And the response asks to retry after 5 seconds

  Scenario: A database session terminated while orders are read is a retryable 503
    When I request orders with size 5 while the order table is locked and the waiting session is terminated
    Then the response status is 503
    And the response problem code is "service_unavailable"
    And the response asks to retry after 5 seconds

  Scenario: A repeated read of an order with its ETag is not modified
    When I request order 1
    Then the response status is 200
    And the response carries a weak ETag
    When I request order 1 again with that ETag
    Then the response status is 304
