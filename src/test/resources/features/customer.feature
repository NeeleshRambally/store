Feature: Customer management
  As a store operator
  I want to create and browse customers
  So that orders can be placed against them

  Scenario: Create a customer
    When I create a customer named "Ada Lovelace"
    Then the response status is 201
    And the response customer is named "Ada Lovelace" with a generated id and no orders

  Scenario: A blank customer name is rejected
    When I create a customer named "   "
    Then the response status is 400
    And the response problem lists a validation error for "name"

  Scenario: A client cannot choose or overwrite a customer id
    When I create a customer with payload:
      """
      {"id": 5, "name": "Overwrite"}
      """
    Then the response status is 400
    And customer 5 is still named "Jean Daniel"

  Scenario: Browse customers page by page
    When I request customers with page 1 and size 5
    Then the response status is 200
    And the page has 5 customers starting at id 6
    And the page reports page 1, size 5 and at least 100 total elements
    And every customer on the page includes its orders

  Scenario: Page size is capped at 100
    When I request customers with page 0 and size 1000
    Then the response status is 200
    And the page reports size 100

  Scenario: Sorting by an unknown property is rejected
    When I request customers sorted by "nope"
    Then the response status is 400
