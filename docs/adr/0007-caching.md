# ADR 0007: HTTP revalidation and a bounded service-level cache for by-id reads

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record covers the two caching layers: HTTP revalidation on every `GET`, and a cache inside the
service for the by-id reads of orders and products. The measurements are under
[Caching](../PERFORMANCE.md#caching) in the performance analysis, and what a client sees is under
[Caching](../DESIGN.md#caching) in the design guide.

## Status
Accepted

## Context
Every `GET` response was sent in full on every request. Every `GET /order/{id}` and
`GET /products/{id}` also opened a Hibernate session and ran its statements, even when the same id
had just been read.

The database is remote, so a by-id read costs a network round trip per statement, plus one for the
commit of its read-only transaction, however small the row. The by-id endpoints are the ones a
client polls or re-reads.

[Deliberately not done](../PERFORMANCE.md#deliberately-not-done) had rejected caching while the
read patterns were paged scans, and still rejects it for the list pages. The by-id reads are
different: the key is the id, the value is small, and the API has no `PUT` or `DELETE`.

## Decision
HTTP revalidation saves response bytes on every `GET`. The service cache saves the database round
trips of `GET /order/{id}` and `GET /products/{id}`.

### HTTP revalidation
- A `ShallowEtagHeaderFilter`, registered through a `FilterRegistrationBean` for `/order`,
  `/order/*`, `/customer`, `/customer/*`, `/products` and `/products/*`, adds an `ETag` to every
  successful `GET`. An ETag is a hash of the body that a client sends back in `If-None-Match`.
- A request whose `If-None-Match` matches gets `304 Not Modified` with no body.
- The ETag is weak (`W/"..."`, which marks bodies as equivalent rather than byte-identical),
  because Tomcat does not compress a response that carries a strong ETag.
- A `WebContentInterceptor` sets `Cache-Control: no-cache` on the same paths. A browser or an
  intermediary may store the response but must revalidate it with `If-None-Match` before reuse, so
  it never serves a stale body on its own.
- The filter is shallow: the controller still runs, and the body is still produced and buffered in
  memory to be hashed. A 304 saves transfer, not server work. The buffered copy is bounded because
  every page is.

### Service-level cache
- `spring-boot-starter-cache` with Caffeine, an in-memory cache library.
- `OrderServiceImpl.findById` is `@Cacheable("orders")` and `ProductServiceImpl.findById` is
  `@Cacheable("products")`. The cached value is the response DTO.
- `@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)` in `CacheConfig` puts the cache advice
  outside the transaction advice, so a hit opens neither a transaction nor a session.
- `spring.cache.cache-names: orders,products` declares the two caches, with the Caffeine spec
  `maximumSize=10000,expireAfterWrite=60s,recordStats`. `CACHE_TTL` sets the 60 s, and
  `CACHE_TYPE=none` switches caching off without a code change.
- Nothing but the Spring cache abstraction is used, so a shared store such as Redis is a
  configuration swap, not a redesign.
- `GET /customer/{id}` is not cached: its `orderCount` changes with every `POST /order`, and
  nothing keys customers for eviction.

### Invalidation
- The API has no `PUT` or `DELETE`, so an `OrderDTO` never changes after creation and the `orders`
  cache needs no eviction.
- A `ProductDTO` carries the ids of the newest orders containing it and `orderCount`, which change
  when a `POST /order` succeeds. `OrderServiceImpl.create` publishes an `OrderCreatedEvent` with
  the product ids, and `ProductCacheEvictor` evicts those `products` entries in a
  `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- Evicting after the commit, not inside the transaction, leaves no window between an eviction and
  the commit in which a read would cache the state before the commit. A read that missed the cache
  before the commit can still store the old value after the eviction (see Consequences).

### Tests
- `HttpCacheIT` and `CompressionIT` pin the ETag, `no-cache`, the bodiless 304, and that a gzipped
  response still carries the ETag.
- `QueryCountIT` clears every cache before each test and keeps the cold-cache statement counts. It
  pins zero statements and zero sessions for a repeated by-id read of an order and of a product.
- `ProductApiIT` and a Cucumber scenario prove that a product read after a `POST /order` lists the
  new order.
- `ActuatorIT` pins `cache_gets_total` in the Prometheus output.

## Consequences
- A repeated by-id read within the TTL (time to live) costs no database round trip and no
  Hibernate session. A conditional `GET` whose ETag matches costs no response body. The measured
  counts are under [Caching](../PERFORMANCE.md#caching) in the performance analysis.
- A cached product (its `orders` and `orderCount`) can be stale for up to `CACHE_TTL`, which is why
  the 60 s default is deliberately short. It happens in three cases:
  - on another replica, whose cache the eviction does not reach;
  - after a write that bypasses the service, such as a migration or a manual `INSERT`;
  - on the replica that committed the order, when a read that missed the cache before the commit
    stores the old value after the eviction. Without that race, the first read after the
    eviction lists the new order.
- Raise the TTL only on evidence. `cache_gets_total{cache="orders",result="hit"}` and
  `cache_gets_total{cache="products",result="hit"}`, against `result="miss"`, show whether the
  cache earns its staleness.
- `OpenAPI.yaml` declares the reusable `If-None-Match` parameter and the `304` response. It
  declares the `ETag` and `Cache-Control` headers as required on every 200 and 304 of a `GET`, so
  [the contract check](../DESIGN.md#the-contract-check) fails such a response without them.
- Tests share one context and one cache. A test may never assume a cold or a warm cache for an id
  it did not populate itself: `QueryCountIT` resets the caches, and the other tests create what
  they read.
- Any future endpoint that changes an order or a product must evict or update the affected entries
  in the same after-commit way. `CACHE_TYPE=none` is the escape hatch if that is ever in doubt.
