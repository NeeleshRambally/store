# Assumptions and judgement calls

[Notes for the assessor](../NOTES.md) · [Design](DESIGN.md) · [Assumptions](ASSUMPTIONS.md) · [Performance](PERFORMANCE.md) · [Decision records](adr/README.md)

The [README](../README.md) leaves the tasks deliberately open, so each judgement call is recorded
here to be defended or revisited: what was assumed or decided, why, and where the evidence lives. It
covers [the production posture](#production-posture), [the API contract](#api-contract) and
[its versions](#contract-version-and-client-migration), the four README tasks
([task 1](#order-by-id-readme-task-1), [task 2](#customer-name-search-readme-task-2),
[task 3](#performance-readme-task-3) and [task 4](#products-readme-task-4)),
[testing](#testing-strategy), [delivery](#delivery),
[what the pipeline verifies](#things-the-pipeline-should-verify-before-promotion-readme-note), and
[the defects found in the original code](#defects-found-in-the-original-code-and-what-was-done). How
the service is run and operated is in [Design](DESIGN.md), the measurements are in
[Performance](PERFORMANCE.md), and each larger decision has a record in
[Decision records](adr/README.md).

## Production posture

The service is treated as one that is already live, with real data and real clients. Every change
is made as if it were rolled out to that system: it starts with a failing test, CI gates each
merge, and the deployable jar cannot fall back to sample credentials or sample data.

- **Already in production.** The service is assumed to have been live before any of this work
  began, so every change keeps a live system working:
  - Schema changes are additive Liquibase changesets that keep the rows a database already holds.
  - The id sequences are moved past the highest id already stored, never backwards.
  - The newest indexes are built `CONCURRENTLY`, so writes keep flowing while they build
    ([ADR 0009](adr/0009-index-changesets.md)).
  - Sample data loads only under the `seed` context
    ([Configuration fails closed](#configuration-fails-closed)).
  - Every contract change is versioned in `OpenAPI.yaml`. A breaking one raises the major version
    and comes with a migration note for the clients already using the API
    ([Contract version and client migration](#contract-version-and-client-migration)).
  - On a stop signal, the requests in flight get up to 20 s to finish before the process exits
    ([Shutdown](DESIGN.md#shutdown)).
- **Tests first.** New behaviour starts with a failing test: a unit test, a slice test (one that
  starts only one layer of the application) or an integration test. The integration tests run
  against a real PostgreSQL started by Testcontainers. API behaviour also gets a Cucumber scenario
  ([Development workflow](DESIGN.md#development-workflow)).
- **CI gates.** CI fails on any failing test, a formatting violation, a SpotBugs finding, or line
  coverage below 80%. Coverage leaves out the launcher class and the `mapper` package, which holds
  the generated MapStruct code and the hand-written `ProductMapper.orderIds`, tested but not
  counted ([Testing](DESIGN.md#testing)).
- **Authentication is deferred.** The service is assumed to run behind a gateway that
  authenticates callers. [ADR 0002](adr/0002-authentication-deferred.md) says why, and what ships
  meanwhile. The resource-server step is
  [issue #70](https://github.com/NeeleshRambally/store/issues/70), due before 2027-03-31.
- **Configuration fails closed.** A missing setting stops the service instead of falling back to a
  sample value; [the next section](#configuration-fails-closed) shows how.

### Configuration fails closed

The deployable jar holds no sample credentials and loads no sample data unless asked to. An
environment that forgets a variable gets neither, and a missing credential stops the process. The
table shows what each profile provides.

| Setting | Base profile (bootJar and image) | `local` profile (`./gradlew bootRun` default; not in the bootJar) |
|---|---|---|
| `DB_USERNAME`, `DB_PASSWORD` | No default | `admin`, `admin` (the README path, port 5433) |
| `LIQUIBASE_CONTEXTS` | `prod`: schema changesets only | `seed`: schema and sample data |

- **A missing credential fails at the first connection**, not when the configuration is parsed.
  Spring Boot binds an unresolved placeholder as literal text, so Liquibase's first connection is
  refused: `password authentication failed for user "${DB_USERNAME}"`, or `Connection refused`
  when nothing answers. The process exits before serving a request (`FailClosedStartupIT`;
  [Fail closed](DESIGN.md#fail-closed)).
- **There is no fallback to `admin:admin`.** The original code hard-coded that pair, and a fallback
  to it would be the same defect with an opt-out.
- **The `local` profile is not in the bootJar**, so `SPRING_PROFILES_ACTIVE=local` cannot bring the
  sample credentials or the seed into a deployment ([Running locally](DESIGN.md#running-locally)).
- **Tests and Compose set their own values.** The tests take their credentials from
  Testcontainers, and `compose.yaml` sets every variable explicitly.
- **Sample data loads only under the `seed` context**: in the `local` profile, in the tests and in
  the `migrate` job of `compose.yaml`. The seed changesets are the only ones with a context, so
  `prod`, or any non-blank value other than `seed`, applies the schema alone.
- **A blank context is refused at start-up**, because an empty context set would apply every
  changeset, seed included (`LiquibaseContextsGuard`, pinned by `FailClosedConfigurationTest` and
  `FailClosedStartupIT`).

## API contract

Clients never supply ids. Bodies and parameters are bound strictly, lists are paged, and every
error the service itself handles is a problem detail (the JSON error body of RFC 7807) with a
`code`.

Most rules below replace a Spring default that silently coerced, merged or dropped bad input. That
matters behind a gateway ([ADR 0002](adr/0002-authentication-deferred.md)): a gateway rule holds
only if the gateway reads a request the way the service does, so the service accepts one reading
only. [API](DESIGN.md#api) in Design gives the same rules in operational form; this section records
why each was chosen and what it replaced.

### Request bodies

A `POST` takes one JSON object with exactly the declared fields and types. A body that breaks this
is a 400 that names the problem, where Spring's defaults used to coerce it or accept it silently.
Its size and media type are bounded separately ([Request body bounds](#request-body-bounds)).

- **No ids from clients.** Request bodies are explicit records. An unknown property is a 400 that
  names it (`Unknown property 'id'`, with `id` in `errors`); the original code let a sent `id`
  overwrite data. The three request schemas declare `additionalProperties: false`.
- **JSON types are bound exactly.** Jackson's defaults turned `"customerId": "5"` or `5.7` into
  customer 5, and `"description": 123` into the string `123`. Each is now a 400 naming the field.
  Three details are deliberate:
  - `5.0` and `5e0` are rejected, because an id is a JSON integer.
  - An empty string for an id is `must be an integer`, not `must not be null`.
  - `errors` lists only the first type failure, since Jackson stops at the first field it cannot
    bind. A body that does bind still gets every Bean Validation failure listed.
- **One JSON object, no repeated property.** Anything but whitespace after the object is a 400
  (`Failed to read request`), and so is a repeated property. Jackson used to stop after the first
  value, so an order body followed by a stray `x` created the order. It also kept the last value
  of a repeated property: `{"customerId":5,"customerId":6}` created an order for customer 6, and a
  repetition after every field had appeared once was a 500.
- **No control characters.** A control character in `description` or `name` is a 400
  (`must not contain control characters`). That means U+0000 to U+001F or U+007F, so a tab or a
  line break too. Before, a NUL reached the insert, which PostgreSQL rejects, and came back as a
  409; a tab or a line break was stored. The specification's single `pattern` combines this rule
  with the not-blank rule:
  `^[^\u0000-\u001F\u007F]*[^\s\u0000-\u001F\u007F][^\u0000-\u001F\u007F]*$`.
- **`POST /order` takes `customerId`**, not a nested customer. An unknown customer is a 404 and a
  missing one a 400. A product id listed twice is a 400 naming `productIds` (`uniqueItems: true`,
  `@UniqueElements`). An earlier version collapsed duplicates and answered 201, which hid a client
  bug.
- **Every `POST` answers 201 with a relative `Location`** (`/order/{id}`, `/products/{id}`,
  `/customer/{id}`). It is relative because a gateway may rewrite the host, which RFC 9110
  (section 10.2.2) allows. The header is required on the three 201 responses, and
  [the contract check](DESIGN.md#the-contract-check) enforces it.
- **A `POST` may carry an `Idempotency-Key`.** Without one, a `POST` inserts unconditionally, so a
  retry creates a duplicate. That holds whether the retry follows a timeout or the 503 of a lost
  database, which does not say whether the write applied. With a key:
  - the key is 1 to 255 visible ASCII characters, compared exactly; anything else is a 400
    (`invalid_idempotency_key`);
  - the first request runs as usual, and its response is stored for 24 hours (`IDEMPOTENCY_TTL`);
  - a repeat with the same key and body gets the stored 201, `Location` and body back with
    `Idempotent-Replayed: true`, and inserts nothing;
  - the same key with another body, or on another path, is a 422 (`idempotency_key_reused`);
  - a repeat while the first request still runs is a 409 (`idempotency_request_in_progress`,
    `Retry-After: 1`);
  - error responses are never stored, so a key can be sent again after a 404 or a 503;
  - a key is scoped by a client identity that is `anonymous` for everyone until
    [issue #70](https://github.com/NeeleshRambally/store/issues/70) supplies the token subject, so
    clients must use random keys (UUIDs).

  [ADR 0010](adr/0010-idempotency-keys.md), delivered under
  [issue #71](https://github.com/NeeleshRambally/store/issues/71), says where the store lives, what
  a restart does to a claimed key and how expired keys are removed.
  [Creating resources](DESIGN.md#creating-resources) gives the rules in operational form.
- **`Accept` is checked before any work.** A request whose `Accept` admits neither
  `application/json` nor `application/problem+json` is a 406, decided when the request is mapped,
  because the controllers declare both types as `produces`. Before, Spring ran the handler and
  failed only when it wrote the response, after a `POST` had committed. `application/problem+json`
  is included because a client that reads problem details may accept only that type, as Spring's
  `RestTemplate` does. Such a client then receives a success body under that content type, as
  before.

### Query and path parameters

Lists are paged, every number has one accepted spelling, and every parameter but `sort` takes one
value. Spring and Tomcat used to read hexadecimal, strip whitespace, normalise bad values and drop
what they could not decode; each of those is now a 400.

#### Paging

- **Lists are paged**: 20 elements by default, 100 at most, sorted by id. This breaks the original
  "return everything" behaviour, and is the right trade for a table of 10,000 rows that only grows
  ([ADR 0003](adr/0003-pagination-and-fetch-strategy.md)).
- **`page` below 0 and `size` below 1 are 400s** naming the parameter. Spring Data's resolver used
  to normalise them silently, with no hook to reject them: `page=-1` became page 0, and `size=0` or
  a non-numeric `page` fell back to the defaults. So the list endpoints also declare `page` and
  `size` as validated `Integer` parameters, which exist only to be validated. A `size` above 100 is
  capped, not rejected ([ADR 0003](adr/0003-pagination-and-fetch-strategy.md)).
- **`page` and `size` are bound strictly**, because Spring's conversion reads `0x` and `#` as
  hexadecimal and strips whitespace:
  - `page=0x2`, `page=#3` and an unencoded `page=+2` (a space once decoded) passed validation as 2
    or 3, while the resolver served page 0;
  - `size=0x10` passed as 16 and was served as 20.

  Now a value outside the 32-bit range (`format: int32`), or not in canonical form, is a
  type-mismatch 400 without `errors`, and an empty or blank value counts as absent. Every value the
  binding accepts is one the resolver reads as the same number.
- **`page` is at most 21474836** (`maximum` in the specification); above it is a 400 naming `page`.
  - Spring Data passes the offset (`page` times `size`) to JPA's `setFirstResult` as an `int`,
    which throws past 2,147,483,647, as does the product filter's native query. So
    `/order?page=107374183` at size 20 was a 500 with a stack trace.
  - 21474836 is the largest page whose offset fits at size 100, so the bound does not depend on
    `size`. At a smaller size it refuses some pages that would fit, but each of them skips at least
    21,474,837 elements.
  - Deep positions use `after`, which has no such bound.
- **`after` gives keyset pagination**, which pages by the last id seen instead of by page number.
  It returns the elements with an id greater than `after`, ascending, with `nextCursor` instead of
  totals. The cursor is a plain id rather than an opaque token, valid only with the default order,
  `id` ascending. So `after` with a `page` other than 0, or with a `sort` that does not start with
  `id` ascending, is a 400 (`after cannot be combined with page or sort`;
  [ADR 0003](adr/0003-pagination-and-fetch-strategy.md)).

#### Numbers in canonical form

Every numeric parameter is read in canonical decimal form: ASCII digits, no plus sign, no leading
zeros, and a minus only before a non-zero digit. So each number has one spelling. The path `id` of
the by-id reads and the query parameters `after`, `customerId`, `productId`, `page` and `size`
share one editor, `DecimalParameterBinding`. It checks the untrimmed text against
`0|-?[1-9][0-9]*` before `Long.valueOf` or `Integer.valueOf`, and an empty or blank query value
counts as absent. The table shows what changed.

| Input | Before | Now |
|---|---|---|
| `/order/0x1`, `/order/%231`, `/order/%201` | Order 1: Spring read hexadecimal and stripped whitespace | 400 `invalid_parameter`, as `abc` already was |
| `/order/1%200` | Order 10, an id the client never sent | 400 |
| `after=0x10`; `customerId=0x1` | Continued after id 16; filtered by customer 1 | 400 |
| `/order/%2B1`, `/order/01`, `/order/%D9%A1` (U+0661), `/order/%EF%BC%91` (U+FF11); `customerId=%2B1` | Order 1, customer 1: `Long.valueOf` read the number the digits spell | 400 |
| `1` followed by a tab, `1 0`, `+1`, `01`, `-0` | Read as a number | 400 |
| `/order/%31`, `/order/1;x=1`, `customerId=%31` | Order 1, customer 1 | Unchanged |

- A negative value still reaches its parameter's range check: `after=-1` is a 400 naming `after`.
- The editor sees the text after percent-decoding, and after the `;` parameters of each path
  segment are removed, which is why the last row is unchanged. A gateway has to decode a request
  the same way before it compares ids ([ADR 0002](adr/0002-authentication-deferred.md)).

#### Repeated and unparsable parameters

- **Every query parameter except `sort` is single-valued.** A repeat is a 400
  (`'customerId' must not be repeated`, with the parameter in `errors`). An interceptor checks it
  before binding, so it is reported even when a value is also invalid. Spring would otherwise:
  - keep the first repeated number: `?customerId=2&customerId=1` filtered by 2;
  - join repeated strings with commas: `?name=onne&name=onne` searched for `onne,onne` and found
    nothing, while `?name=onne` finds Muriel Donnelly.

  Only bound parameters are checked, so an unknown one is still ignored. `sort` stays repeatable,
  each occurrence adding a property. `name` is one string, so `?name=a,b` searches for `a,b`.
- **A query string Tomcat cannot parse in full is a 400** on the list endpoints
  (`invalid_parameter`, `The query string could not be parsed`, no `errors`).
  - Tomcat drops a parameter whose percent escape it cannot decode, that is a `%` not followed by
    two hexadecimal digits (`customerId=1%`, `name=50%`). It also drops a value without a name
    (`=1`). So `/order?customerId=1%` listed every order, and `/customer?name=50%` every customer.
  - The check runs before the repetition check, on every endpoint that binds a query parameter. It
    rejects the request whichever parameter failed, since Tomcat does not say which one it dropped.
  - The by-id reads and the `POST`s bind no query parameter, so their query string stays ignored.
  - A `%` in a search word is sent as `%25`.

  [Strict input](DESIGN.md#strict-input) has more examples and the details of the check.

#### Sorting and filters

- **`sort` accepts only the properties listed in `OpenAPI.yaml`**: `id` and `description` for
  orders and products, `id` and `name` for customers. Anything else, nested paths such as
  `customer.name` included, is a 400 `Unknown sort property: <property>`
  ([ADR 0006](adr/0006-list-projections-and-sort-whitelist.md)).
- **`GET /order` filters by `customerId` or `productId`**, each at least 1 (else a 400 naming the
  field).
  - They are filters, not lookups: no match, including an unknown customer or product, is a 200
    with an empty page.
  - Both work in either paging mode, under the same `sort` whitelist, at the statement counts of
    the unfiltered list.
  - An index that leads with the filtered id and ends with the order id serves them. So a page in
    id order reads only its offset and its own entries, and a cursor page only its own entries.
  - Two cases still read and sort all of a customer's orders: a customer with few orders, and a
    deep page of a customer with many. [Filtered pages](PERFORMANCE.md#filtered-pages) measures
    both on 1,000,000 orders.
  - The two cannot be combined (`customerId and productId cannot be combined`, 400). A client
    filters the smaller list itself, which keeps the query shapes to the two indexed ones.
  - They are the uncapped lists behind the nested `orders` of the next section.

### Responses and errors

Responses are bounded, a successful `GET` carries a weak ETag, and every error the service handles
is a problem detail identified by its `code`.

- **Nested `orders` are bounded.** A customer carries its newest orders, and a product the ids of
  its newest orders: `NESTED_ORDERS_LIMIT` of them (20 by default, 1 to 100), the highest ids,
  returned ascending, next to `orderCount`, the total.
  - The [README](../README.md)'s "0 or more orders" and "list of the order IDs" are delivered in
    this bounded form, for performance.
  - The complete lists are `GET /order?customerId={id}` and `GET /order?productId={id}`.
  - The DTOs originally carried every order
    ([ADR 0003](adr/0003-pagination-and-fetch-strategy.md), "Nested collections").
- **`GET /customer/{id}` exists**, although the [README](../README.md) does not ask for it. Once
  `POST /customer` carries a `Location`, a customer would otherwise be the only resource whose
  `Location` could not be followed. It returns the same `Customer` object as the list and carries
  the same weak ETag. It costs 2 statements, like `GET /products/{id}`, or 1 for an unknown id. It
  is not cached, because `orderCount` changes on every `POST /order` and nothing keys customers for
  eviction.
- **Every successful `GET` carries a weak `ETag` and `Cache-Control: no-cache`.** An ETag is a
  version tag of the response, which a client sends back in `If-None-Match`. A matching one gets a
  304 with no body, and caches may store a response but must revalidate it. `POST` responses carry
  no ETag ([ADR 0007](adr/0007-caching.md)).
- **Every error handled by Spring MVC is a problem detail**, on the management connector too.
  - The exception is a request Tomcat rejects before the servlet filters, such as one whose request
    line and headers exceed 8 KB, or whose path holds `%00`, `%2F` or non-UTF-8 bytes. It gets
    Tomcat's HTML 400 page, without `X-Request-Id`
    ([Requests Tomcat rejects itself](DESIGN.md#requests-tomcat-rejects-itself)).
  - `errors` names the offending fields of a body or of the query parameters (`name`, `after`,
    `page`, `size`, `customerId`, `productId`).
  - For a body value that does not bind (an unknown property, a wrong JSON type, an integer outside
    the 64-bit range), `errors` carries the path of the value, such as `productIds[0]`.
  - A body Jackson cannot parse is `Failed to read request` without `errors`, and nothing more is
    exposed. That covers malformed JSON, a repeated property, content after the object and a
    number of more than 1,000 digits.
  - A whitespace-only `name` or `description` fails validation, and the specification's `pattern`
    says so.
- **A problem detail is identified by its `code`** extension member: `validation_failed`,
  `invalid_sort`, `not_found` and the others in [Errors](DESIGN.md#errors). `type` stays
  `about:blank`, and `title` is the status phrase.
  - Without `code`, a client could tell the 400s apart only by parsing the English `detail` or
    `errors`.
  - RFC 7807's own mechanism, a `type` URI per problem, was not chosen. The service publishes no
    documentation pages, and a URI is a clumsier key than a short code.
  - The cost: a client that knows only RFC 7807 reads `about:blank` problems by status and ignores
    `code`.
  - The codes are an enum in `OpenAPI.yaml`, and a client must handle an unknown code by its
    status. Adding a code is a minor version; removing or renaming one is a major version.

### Paths and documentation

The paths keep the names the README gives them, and the documentation is the checked-in contract.

- **Paths.** `/customer` and `/order` stay singular, to match the original [README](../README.md);
  `/products` is plural because the task names it so. The inconsistency is acknowledged, not
  silently changed.
- **Documentation.** Swagger UI at `/swagger-ui.html` renders the checked-in `OpenAPI.yaml`, which
  the service serves at `/openapi.yaml`, rather than a specification generated from the code. So
  the documentation is the contract the tests enforce
  ([ADR 0011](adr/0011-swagger-ui-from-the-contract.md)).

## Contract version and client migration

`OpenAPI.yaml` versions the contract in `info.version`, independently of the build version
(`1.0.0-SNAPSHOT` in `build.gradle`). A breaking change raises the major version; an addition,
such as a new `code`, raises the minor. The delivered specification said `1.0` while the API
changed under it, so `2.0.0` recorded those changes, and `2.1.0`, `3.0.0` and `3.1.0` followed.

| Version | Kind | What changed | Client impact |
|---|---|---|---|
| 2.0.0 | Major | Paged lists, bounded nested orders, strict bodies and parameters, problem details | Every 1.0 client changes, as [listed below](#version-200) |
| 2.1.0 | Minor | The `database_timeout` code; a lost connection is a 503 instead of a 500, with one exception | None for a client that handles unknown codes by status |
| 3.0.0 | Major | `page` at most 21474836; later within 3.0.0, a 415 for a `POST` that is not `application/json` | None for a client that stops at `totalPages` or walks with `after`, and sends `application/json` |
| 3.1.0 | Minor | The optional `Idempotency-Key` header and its three codes | None for a client that sends no key |

### Version 2.0.0

A client written against 1.0, or against the service while its specification still said 1.0, has
to change in these ways:

- **Lists.** List endpoints answer with a page envelope (`content`, `page`, `size`,
  `totalElements`, `totalPages`), or a cursor envelope (`content`, `size`, `nextCursor`) when
  `after` is sent, not a bare array. A page holds 20 elements by default and 100 at most.
  `GET /products` is such a list, with an `int64` id, not a single object.
- **Nested orders.** The `orders` of a customer and of a product are the newest
  `NESTED_ORDERS_LIMIT` (20 by default), next to `orderCount`. `GET /order?customerId=` and
  `GET /order?productId=` list them all.
- **Field names.** `Order.customer` carries `name` where 1.0 declared `description`.
- **Request bodies.** A request body never carries an `id`: an unknown property is a 400.
  `POST /order` takes `description`, `customerId` and a required `productIds` of 1 to 100 distinct
  ids, where 1.0 declared `id` and `descriptioon` and the code bound a nested `customer`.
- **JSON types** are bound exactly. `"5"`, `5.7` or `5.0` for an id, a number or a boolean for a
  string, and a control character in a string are 400s naming the field, instead of being coerced
  or stored.
- **Parameters.** The rules under [Query and path parameters](#query-and-path-parameters) apply.
  Each replaces a silent reading:
  - `page` below 0 and `size` below 1 are 400s; before, they became page 0 and size 20. A `size`
    above 100 is still capped.
  - `0x1`, `1 0`, `+1`, `01` and non-ASCII digits are 400s. Before, they were read as hexadecimal,
    with their whitespace removed, or as the number their digits spell.
  - A repeated parameter other than `sort` is a 400. Before, its first value was used, and a
    repeated `name` was joined with commas.
  - A C0 control character (U+0000 to U+001F) or DEL in `name` is a 400. Before, a NUL was a 409
    and the other controls were searched for.
  - A query string with a `%` not followed by two hexadecimal digits is a 400 on the list
    endpoints. Before, the parameter it held was dropped and the list served without it.
- **Errors.** Every error Spring MVC handles on the API port is a problem detail with a `code`,
  where 1.0 declared no error response. A client must accept an unknown code.
- **`Accept`.** An `Accept` admitting neither `application/json` nor `application/problem+json` is
  a 406, decided before processing.

### Version 2.1.0

2.1.0 adds the code `database_timeout`: a 503 with `Retry-After` for a cancelled statement, which
was a 500, or for a query issued after the transaction's deadline. That deadline, `TX_TIMEOUT`, is
new with it. A connection lost during a request is now the `service_unavailable` 503 that 2.0.0
already declared, where it was also a 500. One loss stays a 500: a session that the database
terminates while a `POST`'s insert is committed, as a restart does
([Database failures](DESIGN.md#database-failures)). A 2.0.0 client that handles unknown codes by
status needs no change.

### Version 3.0.0

3.0.0 bounds `page` at 21474836 (`maximum`); above it is a 400 naming `page`
(`validation_failed`). The bound narrows what 2.1.0 accepted, hence the major version. Three facts
limit its reach:

- a client that stops at `totalPages`, or walks deep lists with `after`, needs no change;
- a refused page skips at least 21,474,837 elements even at size 1, so on any shorter list it was
  already empty;
- once `page` times `size` passed 2,147,483,647 (page 107374183 at size 20, page 21474837 at size
  100), the service answered a 500 anyway.

3.0.0 also states, in the five numeric query parameter descriptions, that an empty value counts as
absent, as the service already served it. A whitespace-only value is served the same way.

Within 3.0.0 the service also enforces the request media type. It needed no new version, because
every specification since 1.0 declares `application/json` as the only request body type, and the
service already answered 415 to some other bodies. What changed:

- a `POST` whose `Content-Type` is missing or is not `application/json` is a 415, decided before
  the body is read;
- that covers URL-encoded and multipart forms, text, and JSON-based types such as
  `application/vnd.api+json`, which the service used to read and store;
- before, a body without a `Content-Type` was treated as `application/octet-stream`: a 415, a 413
  when its declared length was over the bound, or a 400 `malformed_body` when it was empty;
- before, a multipart body was parsed first, then answered 415, or 413 when it was longer than the
  bound;
- a client that sends `application/json`, with or without a `charset`, needs no change.

### Version 3.1.0

3.1.0 adds:

- the optional `Idempotency-Key` request header on the three `POST`s;
- the `Idempotent-Replayed` response header on their 201s;
- the codes `invalid_idempotency_key` (400), `idempotency_request_in_progress` (409, with
  `Retry-After`) and `idempotency_key_reused` (422).

A 3.0.0 client that sends no key sees no change, and one that handles unknown codes by status needs
no change either.

## Order by id (README task 1)

`GET /order/{id}` returns the order with its customer and its products, ascending by id, in one
statement. It reads them through an entity graph on `findById`, a fetch plan that joins the related
rows. The result is cached for `CACHE_TTL`, since an order never changes
([ADR 0007](adr/0007-caching.md)).

- An unknown id is a 404 problem detail (`Order with id 999999 was not found`, the `unknownOrder`
  example of the contract).
- A non-canonical id (`abc`, `0x1`, `1 0`, `+1`, `01`, a non-ASCII digit) is a 400
  ([Numbers in canonical form](#numbers-in-canonical-form)).

## Customer name search (README task 2)

The query is one word, matched case-insensitively anywhere in the name, and LIKE wildcards in it
match literally. The search shares the list endpoint and its cost, and a trigram index makes it
indexable.

- **The [README](../README.md) wording is read literally.** "A substring of one of the words in
  their name" means a single word, so a query with whitespace or a control character is a 400.
  With no whitespace in the query, any match lies within one word. The empty query (`?name=`) is a
  400, not an unfiltered list.
- **A "word"** is a run of characters that are neither ASCII whitespace nor C0 controls or DEL:
  `[^\s\p{Cntrl}]+` in Java, where both classes are ASCII-only.
  - `Jean-Pierre` is one word, so `n-P` matches it.
  - U+00A0, the no-break space, is not a separator and is matched literally.
  - C1 controls (U+0080 to U+009F) such as U+0085, and the line separator U+2028, are part of a
    word.
  - C0 controls (U+0000 to U+001F) and DEL (U+007F) get the same 400 as whitespace. A NUL used to
    reach PostgreSQL and come back as a 409.
  - The 400 says `must be a single word without whitespace or control characters` rather than
    quoting the regular expression.
- **The specification spells the class out** as `^[^ \t\r\n\f\u000B\u0000-\u001F\u007F]+$`
  rather than `^\S+$`. The ECMA-262 dialect of OpenAPI patterns counts a no-break space and U+2028
  as whitespace, so a client built from `^\S+$` would reject a query the server accepts. Contract
  cases send `a\u00a0b` and a word with U+0085 and U+2028, which both sides accept, and a word with
  a NUL, which both reject.
- **Wildcards are literal.** `%` and `_` are escaped. A `%` is sent as `%25`, since a bare one
  leaves a query string Tomcat cannot parse (a 400, see
  [Repeated and unparsable parameters](#repeated-and-unparsable-parameters)). `CustomerApiIT`
  checks this against PostgreSQL, not a mock:
  - `n-P` and `O'N` find `Jean-Pierre O'Neil`;
  - `%` finds `Hundred%Percent`;
  - `r_S` finds `Under_Score` but not the decoy `Under Score`.
- **Same endpoint, same cost.** `GET /customer?name=` has the list's paging, sorting and order
  fetching, at the statement counts under [Performance](#performance-readme-task-3): 3 for a full
  page, 2 for a short first page, 2 in cursor mode (`QueryCountIT`).
- **The index.** The predicate is `lower(name) LIKE lower('%...%')`. A trigram GIN index on
  `lower(name)` makes it indexable: `pg_trgm` indexes every three-character fragment of each name.
  - On the 100-row seed the planner prefers a sequential scan.
  - On 200,000 rows the index serves the count, the page of a rare word and a deep page of a
    common word. A shallow page of a common word walks the primary key instead, and stops after
    `size` matches.
  - A pattern shorter than three characters yields no trigram. Its count then reads every row, and
    so does its page when the word is rare.
  - [Substring search](PERFORMANCE.md#substring-search) has the plans, and `MigrationIT` pins that
    the index serves the predicate shape.
  - Creating the extension needs a privileged role on the first migration, which the Compose
    `migrate` job has (`admin`). In production a DBA installs it once, and the changeset's
    `CREATE EXTENSION IF NOT EXISTS` then does nothing
    ([ADR 0008](adr/0008-operational-posture.md)).

## Performance (README task 3)

The database is remote, with high latency, so the metric that matters is round trips per request,
not rows. A list page is at most 3 statements and a by-id read 1 or 2, cached for orders and
products. Nothing in a response grows with the tables. [Performance](PERFORMANCE.md) has the
measurements and query plans; this section records the choices.

A request costs its statements plus the commit of its read-only transaction (PgJDBC sends `BEGIN`
with the first statement). It costs one round trip more when HikariCP validates a connection that
was idle for over 500 ms, and none on a cache hit. `QueryCountIT` pins every row of this table:

| Request | Statements |
|---|---|
| Full list page (orders, customers, products, a name search or a filter alike) | 3: the page, its count, the children of exactly that page |
| Short first page | 2: Spring Data skips the count |
| Empty first page | 1: the child query is skipped too |
| Empty page beyond the end | 2: the page and the count, whose total Spring Data cannot derive |
| Cursor page (`after`), at any depth | 2: no count, and the page seeks to `id > after` instead of discarding `OFFSET` rows |
| Empty cursor page | 1 |
| `GET /order/{id}` | 1, then cached |
| `GET /products/{id}` | 2: the product row and the bounded order ids, no entities |
| `GET /customer/{id}` | 2, or 1 for an unknown id; never cached |

[When fewer statements run](PERFORMANCE.md#results) names the test that pins each case.

- **No entities on the list path.** The list endpoints select columns into records with JPQL
  constructor expressions, and build DTOs from the rows. So a page loads no entities
  (`getEntityLoadCount()` is 0, pinned). `GET /order/{id}` and the create paths keep the
  entity-and-mapper route ([ADR 0006](adr/0006-list-projections-and-sort-whitelist.md),
  [Hydration](PERFORMANCE.md#hydration)).
- **Bounded nested collections.** One native window query returns the newest
  `NESTED_ORDERS_LIMIT` children and the total of every parent on the page, in one statement, so
  the counts above hold. It ranks with `row_number() over (partition by parent order by id desc)`
  and totals with `count(*) over (partition by parent)`. A response is therefore bounded exactly:
  - rows per page: `size`, at most 100;
  - orders per customer and order ids per product: `NESTED_ORDERS_LIMIT`, 1 to 100, default 20;
  - products per order: at most 100.

  Before the bound, a customer carried about 100 orders and a product about 200 ids on the seed
  ([ADR 0003](adr/0003-pagination-and-fetch-strategy.md), "Nested collections"; the payloads are
  in [Nested collections](PERFORMANCE.md#nested-collections)).
- **Latency per route.** `http_server_requests_seconds` has buckets from 5 ms to 10 s, so the p95
  or p99 of `GET /order` and `GET /customer` comes from the scrape, not from an average
  ([Ports, probes and metrics](DESIGN.md#ports-probes-and-metrics)).
- **Indexes.** `order.customer_id` is indexed, alone and together with `id` for the customer
  filter; PostgreSQL does not index a foreign key by itself.
  - Every whitelisted sort property is indexed together with `id` (changeset 7).
  - The service appends `id` as a tie-break, in the direction of the last sort property, so a
    sorted offset page has a deterministic boundary. A shallow sorted page is therefore an index
    scan rather than a sort of the whole table.
  - Deep sorted offset pages still pay the offset, and cursor mode stays in `id` order
    ([ADR 0006](adr/0006-list-projections-and-sort-whitelist.md),
    [Sorted pages](PERFORMANCE.md#sorted-pages)).
- **Open-in-view is disabled.** Spring's default keeps the JPA session open for the whole request;
  with it off, no query runs outside a service transaction. `QueryCountIT` pins that no
  `OpenEntityManagerInViewInterceptor` exists. Nothing lazy-loads any more, so without that pin the
  suite would stay green if the property were dropped.
- **Writes are batched.** Ids come from the sequences in blocks of 50, through Hibernate's
  `pooled-lo` optimiser, which takes the value a sequence returns as the first id of a block
  ([ADR 0005](adr/0005-sequence-id-generation.md)). So inserts are deferred to the flush and
  batched (`jdbc.batch_size: 50`, with a multi-row rewrite in the driver).
  - `POST /order` with three products went from 6 to 4 prepared statements, plus one `nextval`
    per 50 orders.
  - Entities of one type flushed together become one batched insert per table.
  - Ids are no longer gapless: a restart can skip up to 49.
- **A keyed `POST` costs two statements more**: the claim of the key before the create, and the
  completion that stores the response in the create's transaction. `QueryCountIT` pins:
  - a keyed `POST /customer` or `POST /products` at 3, and a keyed `POST /order` with three
    products at 6, each plus the block allocation;
  - a replay at 2, with no entity loaded, and a 422 at 2;
  - an unkeyed `POST` unchanged ([ADR 0010](adr/0010-idempotency-keys.md),
    [Write path](PERFORMANCE.md#write-path)).
- **Cache.** `GET /order/{id}` and `GET /products/{id}` are cached in memory for 60 s
  (`CACHE_TTL`; `CACHE_TYPE=none` disables it), so a repeated read runs no statement and opens no
  session (pinned).
  - `orders` entries are never evicted, since an order never changes.
  - `products` entries are evicted after a `POST /order` commits, on the node that served it,
    because the order ids and `orderCount` change.
  - A product can still be stale for up to the TTL: on another replica, after a write that
    bypasses the service, and on the same node when a read that missed before the commit stores
    the old value after the eviction. That bound is why the default is short
    ([ADR 0007](adr/0007-caching.md), [Caching](PERFORMANCE.md#caching)).

## Products (README task 4)

A product has an id and a description, and orders and products are many-to-many. An order needs 1
to 100 distinct products that exist. The request body is bounded in size and media type before it
is parsed, so an oversized or mistyped body cannot occupy memory or disk.

### Model and endpoints

- **Join table.** `order_product` has a composite primary key on `(order_id, product_id)` and an
  index on `(product_id, order_id)`, so both directions are index scans.
- **"1 or more products" is enforced at creation.** `POST /order` requires a non-empty
  `productIds` without repetition; a repeat is a 400. Every id must exist, or the answer is a 404
  naming the lowest missing id, whatever the request order. The seed changeset links each seeded
  order to one or two sample products.
- **At most 100 products per order** (`@Size(max = 100)`, `maxItems: 100`). The bound keeps one
  request to a single `IN` clause of at most 100 ids, and to two insert batches of at most 50 join
  rows (`jdbc.batch_size: 50`) on one prepared statement. So an order of 100 products costs the
  same 4 prepared statements as an order of three, or 5 when the next block of ids is allocated
  (`QueryCountIT`; [Write path](PERFORMANCE.md#write-path)).
- **Historical data.** The invariant holds for orders created through the API from this release
  on. In a production database, an order that pre-dates changeset 5 has no `order_product` rows
  and is returned with `products: []`. A one-off backfill changeset or operator script is needed
  before the invariant can be asserted on historical data. The `Order.products` description in
  `OpenAPI.yaml` says so, which is why the schema declares no `minItems`.
- **`GET /products` and `GET /products/{id}`** return each product with `orderCount` and the
  ascending ids of its newest `NESTED_ORDERS_LIMIT` orders (20 by default). The complete list is
  `GET /order?productId=`.
  - The list is paged like the others. Its order ids come from one window query over
    `order_product`, which ranks each product's orders in a single statement: 3 statements per
    page, 2 for a single product.
  - "All products" comes through the same paging. `size=100` returns the whole seeded catalogue of
    100 in one page, and the cursor walk with `after` covers a catalogue of any size.
  - `ProductApiIT.cursorWalkReturnsTheWholeCatalogue` walks `/products?after=` to the null cursor
    and checks the ids against a snapshot of the table.
- **`GET /order` and `GET /order/{id}`** return the products in each order (`id`, `description`):
  one extra statement per page, none for a single order.
- **The path is `/products`** (plural) because the task names it so; see
  [Paths and documentation](#paths-and-documentation).
- **The delivered `OpenAPI.yaml` was treated as a draft here.** It described `GET /products` as a
  single object, typed `Product.id` as `string` while every other id is `int64`, let
  `POST /products` accept an `id`, and had no `/products/{id}`. Now `/products` is a paged list of
  `Product` with an `int64` id, the body carries no id, and `/products/{id}` exists.
  `OpenApiContractIT` enforces it.

### Request body bounds

Neither Spring Boot nor Tomcat bounds a JSON body by default. Tomcat's 2 MB
`server.tomcat.max-http-form-post-size` covers form-parameter parsing only, so an oversized
`description` was parsed into memory before `@Size` rejected it. Four paths let a body in before it
was rejected, and each is now closed:

| Body | Before | Now |
|---|---|---|
| JSON over the bound | Parsed into memory before validation rejected it | A 413 before parsing ([JSON bodies](#json-bodies)) |
| URL-encoded form on a `POST` | Parsed by Tomcat, up to 2 MB, before the 415 | A 415 at mapping; the body is never read ([Form bodies on `POST`](#form-bodies-on-post)) |
| Multipart, with any method | Parsed and spooled to files before mapping | Multipart off: a `POST` is a 415 and nothing parses the body ([Multipart bodies](#multipart-bodies)) |
| URL-encoded form on a `PUT`, `PATCH` or `DELETE` | Read before the 405; a 500 over the bound | Filter off: the 405, or a 404 for an unknown path, decided at mapping ([Form bodies on `PUT`, `PATCH` and `DELETE`](#form-bodies-on-put-patch-and-delete)) |

Measurements are from 2026-10-20, on JDK 17.0.14 and a throwaway PostgreSQL 16.2, on the jar of the
commit named. The form, multipart and `PUT` measurements ran on an arm64 Mac with `-Xmx154m`, which
that JVM rounds up to a 160 MiB heap.

#### JSON bodies

`RequestBodyLimitFilter` rejects a JSON body over `REQUEST_MAX_BODY_BYTES` (64 KiB by default, at
least 1 KiB) with a 413 (`Request body exceeds 65536 bytes`) before parsing. It judges by the
declared `Content-Length`, or else by counting a chunked body's bytes as they are read. The largest
valid body, a 255-character description and 100 product ids, is about 2 KB, so the bound is thirty
times the largest request accepted.

**What a client sees.** Tomcat sends the 413 at once with `Connection: close`. It then reads and
discards the remainder, up to its 2 MB swallow limit (`server.tomcat.max-swallow-size`), before
closing. What happens next depends on the client:

- if it reads the answer first, it gets the problem detail;
- if it keeps sending, it gets a broken pipe once Tomcat has closed (in one run, 2.8 MB past the
  answer), and may miss the answer;
- if it sends nothing more, it holds the connection and its request thread until the connection
  timeout.

**The connection timeout.** On the jar of 5996b3c, with Tomcat's default 60 s timeout, a `POST`
declaring 1,900,000 or 100,000,000 bytes as JSON (413), or 100,000,000 as a form (415), had its
answer within 16 ms and its connection closed after 60.0 s. `server.tomcat.connection-timeout` is
now 10 s, and the same requests were closed after 10.0 s.
[Unread bodies and silent clients](DESIGN.md#unread-bodies-and-silent-clients) has the effect on
the request threads.

**Pinned by** `RequestBodyLimitFilterSliceTests` and `RequestBodyLimitIT`. The latter covers
declared and chunked bodies, the disconnect within 20 s of a client that sends nothing after its
413, and both timeouts read back from the two connectors. `FailClosedConfigurationTest`, the
contract case, the Cucumber scenario and the container smoke test pin it too. There is no 411
(Length Required): a chunked body is legal HTTP/1.1, and is what the test client sends.

#### Form bodies on `POST`

The filter bounds only what is read through the request's stream, and a form body was not read that
way. For a form `POST` without a query string, Spring's `ServletServerHttpRequest` builds the body
from the request parameters. So Tomcat parsed the form itself, up to its 2 MB form limit, into an
array sized from the declared `Content-Length` before the first byte arrived. Spring answered 415
only afterwards.

**Before.** On the jar of 7db5f20, a `POST /customer` declaring 1,900,000 form bytes and sending
8 KiB had no answer within 5 s.

- Its 415 came 72 ms after the rest was sent; the same declaration as JSON had a 413 after 2 ms.
- A thread dump showed the request in Tomcat's `readPostBodyFully`, called from
  `ServletServerHttpRequest.getBody()` while the `@RequestBody` argument was resolved.
- 20 such requests that had sent 1 KiB each raised the live byte arrays
  (`jcmd GC.class_histogram`) from 10.4 MB to 49.6 MB, about 1.96 MB a request. A few dozen slow
  clients could fill the image's 154 MiB heap.

**Now.** The three `POST`s consume `application/json` only, so a form body is a 415 decided at
mapping and never read.

- The same 20 requests were answered at once. Held open, they grew the live byte arrays by 0.26 MB
  in all, on a JVM whose Tomcat had already handled as many concurrent requests.
- A fresh JVM pays more for the first requests, because Tomcat allocates each concurrent request's
  buffers once and keeps them. Measured with the form-content filter off and the 10 s timeout, 20
  held form `POST`s raised a fresh JVM's live byte arrays by 1.36 MB, about 68 KB a connection
  whatever the declared size. 1.0 MB of that stayed allocated after they closed, and the next 20
  added only 0.33 MB.
- Tomcat's form limit also follows `REQUEST_MAX_BODY_BYTES`. So a component that asks for a
  `POST`'s parameters gets no form body over the bound parsed. Spring's hidden-method filter and
  request-detail logging are two such components: both made Tomcat read the 1,900,000 bytes again,
  despite `consumes`. Zero is not an option: Spring Boot ignores 0, and Tomcat keeps 2 MB.

**Pinned by** `RequestBodyLimitIT`: 8 KiB of a declared 1,900,000-byte form to each `POST`, the 415
within 5 s, and the limit read back from both connectors. Also by the controller slices (the 415
and its `Accept: application/json` for a form, a JSON-based type and a missing `Content-Type`),
`FailClosedConfigurationTest`, and a Cucumber scenario (the 415 of an order sent as a form).

#### Multipart bodies

`consumes` did not stop multipart either. Spring Boot enables multipart by default, and
`DispatcherServlet` then parses any request whose `Content-Type` starts with `multipart/`, whatever
its method, before mapping it. That parse goes through Tomcat rather than the bounded stream. It
allows 1 MB a part and 10 MB a request, and spools each part to a file under `java.io.tmpdir` (the
Compose stack's memory-backed `/tmp`).

**Before.** On the jar of 7db5f20, a `POST /order` declaring 9,000,000 `multipart/form-data` bytes
and sending 8 KiB had no answer within 5 s. Nor had the same request to `/products`, one to
`/customer` as `multipart/mixed`, or a `GET /order` carrying such a body. Five such `POST`s that had
each sent 900 KiB of a file part held five `upload_*.tmp` files, 4,607,910 bytes in all, until they
disconnected. A complete body was parsed before it was answered:

| Body | Answer |
|---|---|
| 1,000-byte field | 415 |
| 100,000-byte field or 900,000-byte file | 413 `Request body exceeds 65536 bytes` (the filter's declared-length check, once Spring read the body) |
| 1,500,000-byte field or file | 413 `Maximum upload size exceeded` (the 1 MB limit applies to every part, not only files) |

On the management port, whose servlet has no multipart configuration, the parse failed: a 500
`internal_error` logged at ERROR.

**Now.** Multipart is off (`spring.servlet.multipart.enabled: false`), since the API takes no
multipart body. No resolver is registered and no servlet gets a multipart configuration, so Tomcat
also leaves such a body unparsed when something reads the parameters.

- With multipart off, the same `POST`s were 415s within 15 ms, and the held ones wrote no file.
- Each complete body above was a 415, the `GET` had its 200, and the management port answered 405.
- A multipart `POST` stayed a 415 within 14 ms, even with the hidden-method filter and
  request-detail logging on.

**Pinned by** `RequestBodyLimitIT` (8 KiB of a declared 9,000,000-byte multipart body to each
`POST`, the 415 within 5 s), `FailClosedConfigurationTest`, `ActuatorIT` (the management port's 405)
and a Cucumber scenario: an order sent as multipart with a 1,100,000-byte file, which the part limit
used to make a 413.

#### Form bodies on `PUT`, `PATCH` and `DELETE`

Spring's `FormContentFilter`, registered by default to turn such a body into request parameters,
read it before mapping, through the bounded stream, although the API has no endpoint for those
methods.

**Before.** On the jar of 5996b3c, a `PUT /customer`, `PATCH /order/1`, `DELETE /order/1` or
`PUT /no-such-path` declaring 1,900,000 form bytes was a 500 within 3 ms. So was a `PUT /customer`
sending 70,000 of them chunked.

- The 500 carried Spring Boot's default error body
  (`{"timestamp":"...","status":500,"error":"Internal Server Error","path":"/customer"}`), with no
  problem detail and no `code`.
- It logged an ERROR line carrying the filter's `RequestBodyTooLargeException`:
  `Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception`.
- The same requests declaring 60,000 bytes and sending 8 KiB had no answer within 10 s, because the
  filter waited for the rest of the body.
- 100 `PUT /customer` connections that had each sent 64,999 bytes of a declared 65,000 got no
  answer either, and raised the live heap from 40.7 MB to 60.6 MB.

**Now.** The filter is off (`spring.mvc.formcontent.filter.enabled: false`), since nothing needs
it. Each of those requests became the 405 problem detail (`method_not_allowed`), or the 404
(`not_found`) for the unknown path. They answered in 1.4 to 4.3 ms, after a first cold one in
14.4 ms, with no ERROR line. The 100 held connections each had their 405 while their bodies were
still incomplete.

**Pinned by** `RequestBodyLimitIT` (8 KiB of a declared 60,000- and 1,900,000-byte form to each of
the four requests, the 405 or 404 within 5 s, and 70,000 chunked bytes to `PUT /customer`),
`FailClosedConfigurationTest`, and a Cucumber scenario (the 405 of a 70,000-byte form
`PUT /customer`).

## Testing strategy

Every production change starts from a test that was seen to fail. One PostgreSQL container and one
Spring context serve a whole run. `OpenAPI.yaml` is enforced by a validator, and each later
correction to it was driven by a contract case that failed first.

- **Tests first.** Each test was observed failing before the code that makes it pass. A test that
  pins behaviour that already existed, such as `FailClosedStartupIT`, is labelled so in its pull
  request, together with the temporary change that made it fail.
- **One container, one context.** All integration tests and Cucumber scenarios share one Spring
  context and one PostgreSQL container per run. So tests never assert on absolute seed counts after
  writes, and never truncate tables.
  - A test that writes outside the API rolls back: the sequence checks in `MigrationIT` and the
    batching check in `InsertBatchingIT`. A `nextval` is never rolled back, so the id it returned
    still shows the sequence position.
  - The rows the suite commits are those its API requests create.
- **One cache.** No test assumes a cold or a warm cache for an id it did not populate.
  `QueryCountIT` clears every cache before each test, so its counts are the cold case, and the
  cache tests create the product or order they read.
- **The contract is enforced, not just published.** `OpenApiContractIT` validates captured request
  and response pairs of every endpoint against `OpenAPI.yaml`
  ([The contract check](DESIGN.md#the-contract-check)). Undocumented response fields are rejected,
  and request parameters are checked. Where the two disagreed, the specification was corrected:
  - `size` above 100 is capped ([ADR 0003](adr/0003-pagination-and-fetch-strategy.md)), so the
    schema no longer declares a `maximum` that a generated client would enforce before sending;
  - `name` and `description` declare `minLength: 1`, because the server rejects blank values.

  The `GET /order` filters are checked with a real 200 each: a customer-filtered page and a
  product-filtered cursor page. They are also checked with the 400 of `customerId` 0
  (`minimum: 1`) and the 400 of the two combined, whose body equals the `combinedFilters` example.

### What the specification declares

Most declarations below were added after a contract case failed without them. That failure is the
evidence that the validator enforces them.

- **Statuses.**
  - The by-id paths declare the 400 of a non-numeric id.
  - Every `POST` declares 409, and every operation 500, as reusable problem-detail responses.
    There is no `default` response, which would hide an undeclared status.
  - The 415 is declared on the three `POST`s.
  - The 406 is declared on every operation as the reusable `NotAcceptable` response, with one case
    per operation sending `Accept: application/xml`. The service answered 406 before too, but only
    after running the handler.
  - The 503 of a lost database is declared on every operation, with `Retry-After` required. A
    second case pins that a 503 without it is rejected.
- **Schemas.**
  - `Order`, `Customer`, `Product` and the nested `customer`, `products` and `orders` objects
    declare their required properties, `orderCount` included, so a dropped one fails.
  - Request strings carry a `pattern` requiring a non-whitespace character, next to
    `minLength: 1`; the pattern now also excludes control characters.
  - `productIds` declares `maxItems: 100` and `uniqueItems`.
  - `code` is a required string enum on every problem detail, carried by every captured and
    synthetic error body and by the examples. Two synthetic cases pin that a missing or an unlisted
    code is rejected.
  - The three request schemas declare `additionalProperties: false`. The contract validator injects
    that rule into every schema, so it could not show the gap. A second validator that injects
    nothing accepted a `POST` body carrying an `id` on all three paths, until the schemas declared
    it.
  - `page` declares `maximum: 21474836`. A captured 400 of `page=21474837&size=100` and a captured
    200 of `page=21474836&size=100` pin the bound from both sides; a `maximum` of 21474835 fails
    the 200.
- **Headers.** `X-Request-Id` is required on every response, and `Location` on every 201. Cases
  without them failed, so the validator does check declared headers. `ETag` and `Cache-Control`
  are required on every 200 and 304 of a `GET`; while they were optional, synthetic responses
  without them passed.
- **Real 503s.** The 503 is also captured from real requests, through `TableLock`:
  - it takes an `ACCESS EXCLUSIVE` lock on `order` from a second connection;
  - it polls `pg_stat_activity` and `pg_locks` until the backend of a `GET /order?size=5` waits on
    the lock (`wait_event_type = 'Lock'`);
  - it then cancels or terminates that backend.

  This lets `DatabaseFailureIT`, two contract cases and two Cucumber scenarios meet a cancelled
  statement and a lost connection in the shared context, without a second context.
  `ServiceUnavailable` carries the two named examples.
- **What cannot be declared.**
  - A 405 for a method a path does not list, since the method does not exist in the contract.
    [Errors](DESIGN.md#errors) lists it, and [the smoke test](DESIGN.md#the-smoke-test) checks
    `DELETE /order/1`.
  - An empty or blank numeric query value, which the service serves as absent. The validator
    reports `customerId=` as `Parameter 'customerId' is required but is missing.`
    (`validation.request.parameter.missing`), even with `allowEmptyValue: true`, which
    swagger-request-validator 2.46.1 applies to string and array parameters only. So the five
    parameter descriptions say that an empty value counts as absent. A contract case records the
    validator's four reports for `customerId=`, `after=%20`, `page=` and `size=%09`, next to the
    server's conforming 200.
- **Servers.** The only `servers` entry is the local `http://localhost:8080`. Its description says
  that in production the gateway sets the public URL
  ([ADR 0002](adr/0002-authentication-deferred.md)), rather than listing a placeholder.

## Delivery

Every change goes through a pull request gated by two green CI jobs and an approval. The image is
distroless (the Java runtime and the application, with no shell or package manager), runs as a
non-root user, and is scanned and smoke-tested on every build. The actions, base images and Gradle
distribution the pipeline downloads are pinned.

### Pull requests and history

- **The gate.** The work assumes a team on a production system. A change is merged once both CI
  jobs are green and a second GitHub account, acting as the reviewer account, has approved the
  pull request.
  - Since the repository went public on 2026-10-22, a ruleset on `main` enforces this: a pull
    request, the `build` and `docker` checks, one approval and rebase merges only, with force
    pushes and deletion blocked.
  - While it was private, the free plan offered no rulesets, so the same gate was a procedure.
- **When the gate began.** The procedure has held from #27 on. Before it, a merge waited for
  `build` only:
  - #7 to #10 merged while their `docker` job was still running;
  - #11 merged after that job failed at set-up, so its image was neither built nor scanned;
  - no push run on `main` finished green between #5 and #30.
- **History.** `main`'s history was rewritten once, on 2026-10-21, to record every commit under the
  project's own identity. That gave each commit a new id, and the docs quote the new ids; it is not
  rewritten otherwise.
  - Squash commits up to #64 keep their pull-request text: imperative subjects from #27, and
    bodies wrapped at 72 columns from #51 (one 73-column line in #60 excepted).
  - From #65 pull requests are rebase-merged, so each commit keeps its author, and GitHub records
    the merging account as committer ([Development workflow](DESIGN.md#development-workflow)).
- **Dependency updates** arrive as weekly pull requests through the same gate
  ([Dependency updates](DESIGN.md#dependency-updates)).
  - Major versions in the Gradle build, and the major of the Java base image, are excluded from
    automation and planned explicitly.
  - GitHub Actions majors, except those of `gradle/actions/setup-gradle`, and the other image
    updates arrive automatically in grouped pull requests, because the pipeline validates them. An
    action's major moves its SHA pin and rewrites the version comment.

### Image and runtime

- **The image** runs as a non-root user on a distroless Java 17 runtime with no shell, package
  manager or `curl`, from a layered jar extraction. Its JRE is the Debian package
  `temurin-17-jre`, which the scan and the SBOM list but no advisory data the scan uses covers. So
  JRE fixes arrive only with a new base-image digest, which Dependabot proposes
  ([ADR 0004](adr/0004-spring-framework-6-cve-exceptions.md), [The image](DESIGN.md#the-image)).
- **Probes, without a `HEALTHCHECK`.** Liveness and readiness are on the management port (8081),
  which the ingress does not publish, and readiness includes the database indicator
  ([ADR 0008](adr/0008-operational-posture.md)). There is no Docker `HEALTHCHECK`:
  - Kubernetes ignores one, and probes `/actuator/health/liveness` and `/actuator/health/readiness`
    itself;
  - nothing in `compose.yaml` waits on the store's health;
  - the smoke script polls readiness itself;
  - without a shell or `curl`, the only probe command would be a second JVM inside the same
    512 MiB limit, where the service alone peaked at 477.4 MiB under load.
- **Migrations are a job, and the runtime role cannot alter the schema.**
  - Compose runs the image once as `migrate`: no web server, the schema owner `admin`, and the
    `seed` context. It starts `store` only after `migrate` exits 0.
  - `store` connects as `store_app`, with `SPRING_LIQUIBASE_ENABLED=false`, so no Liquibase bean
    exists at runtime.
  - `scripts/postgres-init/01-roles.sql` creates `store_app` with connect, `SELECT`, `INSERT` and
    sequence usage on everything `admin` creates, and nothing else: a `create table` and an
    `update` as `store_app` are denied.
  - The one exception is `UPDATE` and `DELETE` on `idempotency_key`, which changeset 9 grants to
    the role named by the Liquibase parameter `runtimeRole`
    ([ADR 0010](adr/0010-idempotency-keys.md)).
  - The two passwords stay inline, as README-path development values. A deployment supplies
    credentials as variables or as secret files in `/run/secrets`
    ([Credentials as files](DESIGN.md#credentials-as-files)), and an `env_file` split is left
    open.
  - `./gradlew bootRun` keeps in-process Liquibase as `admin`
    ([ADR 0008](adr/0008-operational-posture.md),
    [Start-up and migrations](DESIGN.md#start-up-and-migrations)).
- **Hardened containers.** The Compose `store` and `migrate` services run with a read-only root, a
  tmpfs `/tmp`, no capabilities, `no-new-privileges`, and a 512 MiB memory limit without swap.
  - Inside that limit the entrypoint gives the heap 30% under SerialGC, a share measured under
    concurrent load ([Container hardening and memory](DESIGN.md#container-hardening-and-memory)).
  - Their `json-file` log rotates at 3 files of 10 MB, since one access line per request would
    otherwise grow it without bound.
  - `postgres` stays unhardened, because `initdb` needs root capabilities on first start
    ([ADR 0008](adr/0008-operational-posture.md)).
  - The stack publishes the API, the actuator and the database on 127.0.0.1 only, because the API
    has no authentication and the database role is the `admin` superuser
    ([ADR 0002](adr/0002-authentication-deferred.md)). `STORE_BIND_ADDRESS` names another
    address; the GitLab smoke job sets `0.0.0.0` to reach its Docker-in-Docker daemon over the
    network.
  - The smoke test asserts all of this
    ([Things the pipeline should verify](#things-the-pipeline-should-verify-before-promotion-readme-note)).
- **Request ids and logs.** Every response carries an `X-Request-Id`, echoed when well-formed and
  generated otherwise, and the contract requires it. The id is also in the MDC (the per-request log
  context) of every log line of that request, so a client-quoted id finds the logs
  ([Request ids and filters](DESIGN.md#request-ids-and-filters)).
  - The exception is the 400 Tomcat sends itself before the servlet filters, which has no id and
    no access line.
  - Every other API-port request writes one access line: method, route template, status and
    duration, never the query string or the body.
  - An ingress usually logs too, but the service cannot tell, so the line stays on by default.
    `LOGGING_LEVEL_COM_EXAMPLE_STORE_ACCESS=OFF` removes it ([Logs](DESIGN.md#logs)).
  - A created order, customer or product is logged once, by id, after its transaction commits. So
    a write is in the log beside its access line, and a write that was rolled back never is.
  - Distributed tracing is deferred like authentication: nothing in scope consumes a trace, and the
    header is the hook a tracer attaches to ([ADR 0008](adr/0008-operational-posture.md)).

### Pipelines

- **GitHub Actions is the live pipeline.** `.gitlab-ci.yml` runs the same stages and is provided
  for portability. It has not been executed here, but it passes GitLab's CI JSON schema, and
  [The GitLab pipeline](DESIGN.md#the-gitlab-pipeline) lists the differences. Among them:
  - GitLab builds a local `store:ci` tag and retags it for the registry only in `publish`;
  - it runs `smoke` and `scan` in parallel after `package`, where GitHub runs them in one job, and
    the gate before a publish is the same;
  - GitHub publishes by retagging and pushing the loaded `store:ci` image, GitLab by loading and
    pushing the saved `image.tar`.
- **Time limits.** Every job in both pipelines has a timeout of 20 or 30 minutes, several times a
  measured run, so a hung pull or daemon cannot hold a runner for hours
  ([Build cache and time limits](DESIGN.md#build-cache-and-time-limits)).
- **Cancelling.** A new commit cancels a running pull-request or merge-request run, but never a run
  on `main`.
- **A pinned supply chain.**
  - Every GitHub Action is pinned to the commit SHA of its release, with the version in a comment.
  - The `Dockerfile` base images and the Compose `postgres:16.2` image are pinned by digest.
  - The Gradle distribution is pinned by `distributionSha256Sum`.
  - Dependabot moves each pin weekly (the `github-actions`, `docker` and `docker-compose`
    ecosystems). That weekly pull request is the review path, because Dependabot's security alerts
    do not cover SHA-pinned actions.

### Static analysis

`build` runs SpotBugs 4.10.5 (Gradle plugin 6.5.13) over the main classes, and a finding fails the
build:

| Setting | Value |
|---|---|
| `ignoreFailures` | `false`: a non-zero exit fails the build (1: findings, 4: analysis error) |
| Effort and confidence | Default effort, medium confidence |
| Exclusion filter | None. Lombok marks its methods `@lombok.Generated`, which SpotBugs skips, and the MapStruct mappers are clean |
| Reports | `build/reports/spotbugs/`, kept by both pipelines |
| Cost | `spotbugsMain` took 3.4 to 3.6 s in a full build of about 30 s on an arm64 Mac |

- **The first run** reported 16 findings: an `EI_EXPOSE_REP` and `EI_EXPOSE_REP2` pair (a mutable
  reference returned, and one stored) on each of eight records holding the caller's list or set.
  - Each record now keeps an unmodifiable copy, so a cached DTO shared by every hit cannot change.
  - `CreateOrderRequest` copies without `List.copyOf`, which rejects nulls, so validation still
    names a missing `productIds` or a null id.
  - The entities pass because none has a setter. SpotBugs treats a class with setter-like methods
    as mutable. A setter on `Customer` would make it report the `Order` constructor, which keeps
    the customer it is given, as `EI_EXPOSE_REP2`, and an entity reference cannot be copied.
- **`spotbugsTest` is disabled.** When the gate was added, all 35 findings there were
  `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`, on values Spring declares nullable, where a null fails
  the test anyway: 25 on `ResponseEntity.getBody()`, 8 on `HttpHeaders.getETag()`, and one each on
  `JdbcTemplate.queryForObject` and `CacheManager.getCache`.
- **The `spotbugs` configuration resolves the versions SpotBugs requests.** Spring Boot's
  dependency management would otherwise downgrade commons-lang3 there from 3.21.0 to 3.17.0. BCEL
  then throws `NoClassDefFoundError: org/apache/commons/lang3/Strings`, and SpotBugs exits 4
  reporting no bugs. The application's classpaths are unchanged.

### Vulnerability scan, SBOM and dependency line

- **The scan.** The image is scanned on every pull request, and a fixable finding of severity
  `CRITICAL` or `HIGH` blocks the merge.
  - Findings without a fix are excluded (`--ignore-unfixed`) and not listed, since no code change
    can address them. The SBOM (software bill of materials) records what the image contains.
  - A finding that cannot be fixed on the supported line becomes a documented, time-boxed exception
    ([ADR 0004](adr/0004-spring-framework-6-cve-exceptions.md)), never a lowered gate.
- **The dependency line.** The image runs on Spring Boot 3.5.16, the last BOM (the managed set of
  dependency versions) of a line that left open-source support on 2026-06-30. That is an
  unsupported line, stated plainly.
  - Until the upgrade, every fixable finding in a Boot-managed library is closed by a per-library
    override in `build.gradle`. Each is one `ext` line with a floor in `DependencyOverridesTest`;
    there are three today (`tomcat.version`, `jackson-bom.version`, `postgresql.version`).
  - The overrides are reviewed weekly through the Dependabot pull requests, and removed at the
    upgrade.
  - The two Framework 6 advisories without a 6.x fix are time-boxed exceptions
    ([ADR 0004](adr/0004-spring-framework-6-cve-exceptions.md)).
  - The upgrade to Spring Boot 4.1.x is
    [issue #57](https://github.com/NeeleshRambally/store/issues/57), due before the 2027-01-31
    expiry of those exceptions.
- **The SBOM.** One is produced for every build, so the deployed dependency set can be checked
  later within a bounded window.
  - GitHub keeps the `sbom` artifact for 90 days (`retention-days: 90`). The repository's retention
    is also 90 days; should it ever drop below, the action uses the repository's setting and logs a
    warning.
  - GitLab keeps `sbom.cdx.json` as long (`expire_in: 90 days`).
  - The SBOM is not a signed attestation bound to the image digest. GitHub offers artifact
    attestations on the Free, Pro and Team plans for public repositories only, and the repository
    was private until 2026-10-22, so none is produced yet.
  - For an older image, the SBOM is made again from the pushed digest. No login is needed, because
    the package is public:
    - `docker buildx imagetools inspect ghcr.io/neeleshrambally/store:sha-<short sha>` prints the
      digest;
    - `syft ghcr.io/neeleshrambally/store@sha256:<digest> -o spdx-json` describes that image.
- **The data generator.** Its one dependency carries an accepted npm advisory, GHSA-qxc2-j82w-r537
  on `@faker-js/faker` 7.6.0, through a code path the script never calls.
  [The data generator's README](../utils/README.md) ("Known advisory") records the reasoning and
  the review date.

## Things the pipeline should verify before promotion (README note)

The [README](../README.md) asks what a pipeline should verify before code is promoted. This one
runs every check below, and nothing is promoted unless all of them pass:

| Job | Checks |
|---|---|
| `build` | The data generator's own test; formatting; compilation; unit and slice tests; integration tests against a real PostgreSQL; the Cucumber acceptance scenarios; the OpenAPI contract check; line coverage of at least 80%; static analysis of the main classes (SpotBugs) |
| `docker` | A successful image build; an image vulnerability scan; an SBOM; a smoke test of the started stack |

`.gitlab-ci.yml` runs the same checks in its `build`, `generator-test`, `package`, `smoke` and
`scan` jobs.

**The smoke test runs on every build.** The image just built migrates a fresh PostgreSQL as the
`migrate` job, then starts under Compose as the restricted runtime role. It must become ready
within 120 seconds: `/actuator/health/readiness` reports `UP` with the `db` component. It must then
answer the API on 8080, with the actuator on 8081 only:

- `GET /order/1`, `POST /customer`, and a `POST /customer` replayed by its `Idempotency-Key`;
- `GET /products/2` and a cursor page;
- a 413 for an oversized body;
- Swagger UI, its bundle and `/openapi.yaml`.

In its `compose` mode the smoke test also:

- reads, with `docker inspect`, the `migrate` job's exit code, the hardening of both containers
  and the address of every published port;
- checks the runtime role's grants in the database container;
- requires a request's access line to appear in the container's ECS (Elastic Common Schema) JSON
  log output.

[The smoke test](DESIGN.md#the-smoke-test) lists every check.

**What is published.** The image published from the default branch is that scanned and
smoke-tested image, retagged, never a rebuild. A failing smoke test fails the `docker` job
(`smoke` on GitLab), and the ruleset on `main` requires both jobs green and an approval before a
merge.

## Defects found in the original code and what was done

The [README](../README.md) says bad choices were made deliberately. This is the consolidated list:
where each defect was in the imported code, what was done, and where the reasoning lives. Line
numbers are those of the import commit; `git show d0532d8:<path>` shows any file as it was. The
[notes for the assessor](../NOTES.md) summarise the list.

- **Controllers wired to repositories and entities** (`CustomerController.java:20-31`,
  `OrderController.java:20-31`). Replaced by controller → service interface → repository, with
  request and response records ([ADR 0001](adr/0001-layered-architecture-and-dtos.md)).
- **Client-supplied ids bound from the body, and `CascadeType.ALL` from customer to orders.**
  `@RequestBody Customer` and `@RequestBody Order` (`CustomerController.java:30`,
  `OrderController.java:30`) bound the entities themselves, so `POST /customer` with `{"id": 5}`
  overwrote row 5. The cascade was at `Customer.java:19`. Now the request records have no id,
  unknown properties are 400s, the cascade is removed, and only the order service writes orders
  ([ADR 0001](adr/0001-layered-architecture-and-dtos.md)).
- **`@Data` on both bidirectional entities** (`Customer.java:11`, `Order.java:8`), which generated
  `equals`, `hashCode` and `toString` over the lazy collection. Replaced by `@Getter`, a
  constructor for the required fields, a protected no-argument constructor for JPA, no setters,
  id-based equality, a constant hash code and an id-only `toString`
  ([ADR 0001](adr/0001-layered-architecture-and-dtos.md)).
- **Entity columns nullable while the schema is `NOT NULL`** (`Customer.java:17` and
  `Order.java:15-18`, against `schema.sql:4,10-11`). Now `@Column(nullable = false)`,
  `optional = false` and `@JoinColumn(nullable = false)`, so the mapping states what the schema
  enforces. A null never reaches the entity, because the request records reject it first.
  `ddl-auto: validate` checks tables, columns and types at start-up, not nullability.
- **`GenerationType.IDENTITY`** (`Customer.java:14`, `Order.java:12`) **and serial sequences left
  at 1 after the explicit-id seed** (`data.sql`, `db.changelog-2.yaml`), so the first insert
  failed. Changeset 3 moves each sequence past the highest seeded id, and the entities use
  `SEQUENCE` with block allocation ([ADR 0005](adr/0005-sequence-id-generation.md),
  [Write path](PERFORMANCE.md#write-path)).
- **No index on `order.customer_id`** (`schema.sql:8-13`). Changeset 3 adds
  `idx_order_customer_id` ([Indexes](PERFORMANCE.md#indexes),
  [ADR 0003](adr/0003-pagination-and-fetch-strategy.md)).
- **Open-in-view at its default, and no transactions**: the controllers called the repositories
  directly. Now `spring.jpa.open-in-view: false`, and transactional services return DTOs
  ([ADR 0003](adr/0003-pagination-and-fetch-strategy.md)).
- **`default_batch_fetch_size: 10`** (`application.yaml:13`), which made each list request 11
  statements, so at least 11 round trips. The list endpoints run a fixed number of statements per
  page, and the property is raised to 100 as a safety net only
  ([Measurements on the original code](PERFORMANCE.md#measurements-on-the-original-code),
  [ADR 0003](adr/0003-pagination-and-fetch-strategy.md)).
- **No time limit on any database call** (`application.yaml:2-5` sets only the URL and the
  credentials). PgJDBC waits forever by default, so a stalled or silently dropped connection held
  its pool slot and its request thread without limit. Now every connection has a server-side
  statement timeout, a socket timeout and TCP keepalive, and every transaction a default timeout.
  Their failures are 503 problem details with `Retry-After` ([Timeouts](DESIGN.md#timeouts),
  [Database failures](DESIGN.md#database-failures), [ADR 0008](adr/0008-operational-posture.md)).
- **Unbounded `findAll()`** (`CustomerController.java:25`, `OrderController.java:25`). Now paged
  and keyset lists, with bounded nested collections
  ([ADR 0003](adr/0003-pagination-and-fetch-strategy.md)).
- **No validation and no error handling**, so a constraint failure was a 500 with a stack trace.
  Now Bean Validation on the request records, and `GlobalExceptionHandler` maps everything to
  RFC 7807 problem details ([ADR 0001](adr/0001-layered-architecture-and-dtos.md),
  [API contract](#api-contract)).
- **Hard-coded `admin:admin`** (`application.yaml:4-5`) in the base profile, beside `format_sql`
  and `use_sql_comments` (`application.yaml:11-12`). Those two shape the SQL but log none of it,
  because nothing set `org.hibernate.SQL` to DEBUG. Now the base profile has no credential defaults
  and neither option. The `local` profile keeps the options, sets `org.hibernate.SQL` to DEBUG so
  `bootRun` logs every statement, and is left out of the bootJar
  ([Configuration fails closed](#configuration-fails-closed),
  [Running locally](DESIGN.md#running-locally), [Configuration](DESIGN.md#configuration),
  [ADR 0008](adr/0008-operational-posture.md)).
- **Seed data loaded in every environment** (`db.changelog-2.yaml` without a context). The seed
  changesets carry `context: seed`, and `LIQUIBASE_CONTEXTS` defaults to `prod`
  ([Configuration fails closed](#configuration-fails-closed)).
- **Spring Boot 3.4.2** (`build.gradle:3`), **dependency-management 1.1.3** (`build.gradle:4`)
  **and Gradle 8.11.1** (`gradle-wrapper.properties`), each behind its line. Now 3.5.16, 1.1.7 and
  8.14.6, kept current by Dependabot through the pipeline ([Delivery](#delivery);
  [ADR 0004](adr/0004-spring-framework-6-cve-exceptions.md) for the two advisories the current line
  cannot fix).
- **Build file noise.** Found:
  - an unused Liquibase Gradle plugin (`build.gradle:5`);
  - `lombok-mapstruct-binding` as `implementation` instead of `annotationProcessor`
    (`build.gradle:43`);
  - a duplicate `spring-boot-starter-data-jpa` test dependency (`build.gradle:49`);
  - JaCoCo with an HTML report only, no gate and an `afterEvaluate` hack (`build.gradle:62-74`).

  Done: each removed or moved, plus an XML report and an 80% line-coverage gate on `check`
  (PR #1; [Testing](DESIGN.md#testing)).
- **`OrderMapper.orderToOrderCustomerDTO(Customer)` misnamed** (`OrderMapper.java:18`). Renamed
  `customerToOrderCustomerDTO` (PR #3).
- **Wildcard imports** (`CustomerController.java:11`, `OrderController.java:11`, `Customer.java:3`,
  `Order.java:3`, both tests). Replaced by explicit imports under Spotless's import order (PR #3).
- **Test hygiene.** Found:
  - the misspelled `OrderContollerTests.java`;
  - `@RequiredArgsConstructor` on a test class (`OrderContollerTests.java:30`);
  - repositories mocked inside a controller slice (`OrderContollerTests.java:39-43`,
    `CustomerControllerTests.java:33-34`);
  - `@ComponentScan` of the mappers into the slice (`OrderContollerTests.java:29`,
    `CustomerControllerTests.java:24`);
  - a stray `;` (`CustomerControllerTests.java:63`);
  - a `findById` stub for a call never made (`OrderContollerTests.java:62`).

  Done: the class is renamed; the `@RequiredArgsConstructor`, the `@ComponentScan`, the stray
  statement and the dead stub are gone; and the slices mock the service layer instead of the
  repositories (PRs #1 and #3; [Testing](DESIGN.md#testing)).
- **Changesets without a rollback** (`db.changelog-1.yaml`, `db.changelog-2.yaml`). Every changeset
  since declares one. The two imported `sqlFile` changesets still have none: both are already
  applied in any existing database, and a `DROP TABLE` rollback for the schema changeset would be
  more dangerous than its absence.
- **`OpenAPI.yaml`.** Found:
  - `descriptioon` (`:52`);
  - `Order.customer` declaring `description` instead of `name` (`:153`);
  - bare arrays with no 4xx response (`:29`, `:65`);
  - no `required` anywhere, and request bodies not marked `required` and carrying an `id`
    (`:43-53`, `:79-89`, `:123-135`);
  - `x-stoplight` noise (`:134`, `:174`);
  - a license URL ending in `.htm` (`:13`).

  Done: all corrected, and enforced by `OpenApiContractIT` ([Testing strategy](#testing-strategy),
  [The contract check](DESIGN.md#the-contract-check)). The products section (`:90-135`) is covered
  under [Model and endpoints](#model-and-endpoints).
- **`utils/generateData.js`.** Found:
  - unescaped quotes in the generated SQL (`:8`, `:14`): Faker's English surnames include
    `O'Keefe`, so a regenerated file could fail to load;
  - explicit ids (`:8`, `:14`);
  - an unseeded `Math.random()` for each order's customer (`:13`);
  - the `faker.name` module (`:8`).

  Done: quotes are escaped, and the generator is seeded end to end and tested for it in the
  pipeline. The explicit ids stay, because changeset 3 aligns the sequences after the load.
  `faker.name` stays, because the locked Faker 7.6.0 has no `faker.person`; the rename came in
  Faker 8, whose upgrade would change every generated name
  ([the data generator's README](../utils/README.md)).
- **No image, no pipeline, no probes and no metrics.** The import had no `Dockerfile`, no CI and no
  actuator, and the [README](../README.md) says that no pipeline exists. Now a distroless image, a
  Compose stack, the CI pipelines, actuator probes and Prometheus metrics ([Delivery](#delivery),
  [Ports, probes and metrics](DESIGN.md#ports-probes-and-metrics)).
- **The reserved table name `order`** (`Order.java:9`, `schema.sql:8`) was kept. Renaming it is a
  migration of the table, its sequence, its foreign key and the join table, for no functional
  gain. The quoted identifier is confined to the entity mapping, the native queries, the
  changesets and the SQL the integration tests run.
