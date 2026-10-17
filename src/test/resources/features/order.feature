Feature: Order management
  As a store operator
  I want to place orders for existing customers
  So that each order is always attributed to a customer

  Scenario: Create an order for an existing customer
    When I create an order "Analytical Engine" for customer 1
    Then the response status is 201
    And the response order is "Analytical Engine" for customer "Muriel Donnelly" with a generated id

  Scenario: An order for an unknown customer is rejected
    When I create an order "Analytical Engine" for customer 999999
    Then the response status is 404
    And the response problem detail is "Customer with id 999999 was not found"

  Scenario: An order without a customer is rejected
    When I create an order with payload:
      """
      {"description": "Orphan"}
      """
    Then the response status is 400
    And the response problem lists a validation error for "customerId"

  Scenario: Browse orders with their customer
    When I request orders with page 0 and size 20
    Then the response status is 200
    And the page has 20 orders starting at id 1
    And the first order on the page belongs to customer "Vicki Kutch"

  Scenario: Fetch a single order by id
    When I request order 1
    Then the response status is 200
    And the response order is "Handcrafted Soft Chair" for customer "Vicki Kutch" with id 1

  Scenario: Fetching an unknown order is a not found
    When I request order 999999
    Then the response status is 404
    And the response problem detail is "Order with id 999999 was not found"
