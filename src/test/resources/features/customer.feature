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
    And the response problem detail is "Unknown property 'id'"
    And the response problem lists a validation error for "id"
    And customer 5 is still named "Jean Daniel"

  Scenario: Browse customers page by page
    When I request customers with page 1 and size 5
    Then the response status is 200
    And the page has 5 customers starting at id 6
    And the page reports page 1, size 5 and at least 100 total elements
    And every customer on the page includes its newest orders and the total count

  Scenario: Page size is capped at 100
    When I request customers with page 0 and size 1000
    Then the response status is 200
    And the page reports size 100

  Scenario: Sorting by an unknown property is rejected
    When I request customers sorted by "nope"
    Then the response status is 400
    And the response problem detail is "Unknown sort property: nope"

  Scenario: Search customers by part of a word in their name
    When I search customers by name "onne"
    Then the response status is 200
    And every customer on the page has a name containing "onne"
    And the page contains customer "Muriel Donnelly"

  Scenario: Name search is case insensitive
    When I search customers by name "MURIEL"
    Then the response status is 200
    And every customer on the page has a name containing "muriel"
    And the page contains customer "Muriel Donnelly"

  Scenario: A name search spanning more than one word is rejected
    When I search customers by name "Muriel Donnelly"
    Then the response status is 400
    And the response problem lists a validation error for "name"

  Scenario: Name search with no match returns an empty page
    When I search customers by name "zzzzzzzz"
    Then the response status is 200
    And the page reports page 0, size 20 and at least 0 total elements
    And the page is empty

  Scenario: A blank name search is rejected
    When I search customers by name " "
    Then the response status is 400
    And the response problem lists a validation error for "name"

  Scenario: An empty name query is rejected
    When I search customers by name ""
    Then the response status is 400
    And the response problem lists a validation error for "name"

  Scenario: A name search can be sorted
    When I search customers by name "a" sorted by "name,desc" with size 5
    Then the response status is 200
    And every customer on the page has a name containing "a"
    And the page lists the customers in name descending order

  Scenario: A name search can be walked with a cursor
    When I search customers by name "a" after 0 with size 3
    Then the response status is 200
    And every customer on the page has a name containing "a"
    And the page has 3 customers starting at id 2
    And the next cursor is 4

  Scenario: Fetch a single customer by id
    When I request customer 1
    Then the response status is 200
    And the response customer has id 1 named "Muriel Donnelly" with its 20 newest orders and the total count

  Scenario: Fetching an unknown customer is a not found
    When I request customer 999999
    Then the response status is 404
    And the response problem detail is "Customer with id 999999 was not found"
