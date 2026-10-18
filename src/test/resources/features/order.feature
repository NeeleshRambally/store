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
    And the response problem lists a validation error for "productIds"

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

  Scenario: Browse orders with their customer
    When I request orders with page 0 and size 20
    Then the response status is 200
    And the page has 20 orders starting at id 1
    And the first order on the page belongs to customer "Vicki Kutch"
    And every order on the page lists at least one product

  Scenario: Sorting orders by a property outside the documented list is rejected
    When I request orders sorted by "customer.name"
    Then the response status is 400
    And the response problem detail is "Unknown sort property: customer.name"

  Scenario: Fetch a single order by id
    When I request order 1
    Then the response status is 200
    And the response order is "Handcrafted Soft Chair" for customer "Vicki Kutch" with id 1
    And the response order contains products 2 and 8

  Scenario: Fetching an unknown order is a not found
    When I request order 999999
    Then the response status is 404
    And the response problem detail is "Order with id 999999 was not found"

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

  Scenario: A repeated read of an order with its ETag is not modified
    When I request order 1
    Then the response status is 200
    And the response carries a weak ETag
    When I request order 1 again with that ETag
    Then the response status is 304
