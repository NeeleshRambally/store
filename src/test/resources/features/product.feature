Feature: Product catalogue
  As a store operator
  I want to manage products and see which orders contain them
  So that an order always refers to real products

  Scenario: Create a product
    When I create a product described as "Brass Astrolabe"
    Then the response status is 201
    And the response product is described as "Brass Astrolabe" with a generated id and no orders

  Scenario: A blank product description is rejected
    When I create a product described as "  "
    Then the response status is 400
    And the response problem lists a validation error for "description"

  Scenario: Fetch a product with the orders that contain it
    When I request product 2
    Then the response status is 200
    And the response product has id 2 and its orders include 1 and 43

  Scenario: Fetching an unknown product is a not found
    When I request product 999999
    Then the response status is 404
    And the response problem detail is "Product with id 999999 was not found"

  Scenario: Browse products with their order ids
    When I request products with page 0 and size 5
    Then the response status is 200
    And the page has 5 products starting at id 1
    And every product on the page lists at least one order id
