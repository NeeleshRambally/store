# ADR 0007: HTTP revalidation and a bounded service-level cache for by-id reads

## Status
Accepted

## Context
Every `GET` response was sent in full on every request, and every `GET /order/{id}` and
`GET /products/{id}` opened a Hibernate session and ran its single statement even when the same
id had just been read. The database is remote, so a by-id read costs a network round trip for
each of its statements and one more for the commit of its read-only transaction, however small
the row, and the by-id endpoints are the ones a client polls or re-reads.
`docs/PERFORMANCE.md` ("Deliberately not done") had rejected caching while the read patterns
were paged scans, and still rejects it for the list pages; the by-id reads are different: the
key is the id, the value is small, and the API has no `PUT` or `DELETE`.

## Decision
- **HTTP revalidation.** A `ShallowEtagHeaderFilter` registered through a
  `FilterRegistrationBean` for `/order`, `/order/*`, `/customer`, `/customer/*`, `/products` and
  `/products/*` adds a weak `ETag` (`W/"..."`) to every successful `GET` and answers a request
  whose `If-None-Match` matches with `304 Not Modified` and no body. The ETag is weak because
  Tomcat skips compression for strong ETags. A `WebContentInterceptor` sets
  `Cache-Control: no-cache` on the same paths, so a browser or intermediary may store the
  response but must revalidate it with `If-None-Match` before reuse; it never serves a stale
  body on its own. The filter is shallow: the controller still runs and the body is still
  produced and buffered in memory to be hashed, so a 304 saves transfer, not server work; the
  buffered copy is bounded because every page is (`docs/PERFORMANCE.md`, "Caching").
- **Service-level cache.** `spring-boot-starter-cache` with Caffeine; `@EnableCaching(order =
  Ordered.HIGHEST_PRECEDENCE)` puts the cache advice outside the transaction advice, so a hit
  opens neither a transaction nor a session. `OrderServiceImpl.findById` is `@Cacheable("orders")`
  and `ProductServiceImpl.findById` is `@Cacheable("products")`; the cached value is the DTO. The
  caches are declared (`spring.cache.cache-names: orders,products`) with
  `maximumSize=10000,expireAfterWrite=60s,recordStats`; the TTL is `CACHE_TTL` and
  `CACHE_TYPE=none` switches caching off without a code change. A shared store (Redis) is a
  configuration swap, not a redesign, because nothing but the Spring cache abstraction is used.
- **Invalidation.** The API has no `PUT` or `DELETE`, so an `OrderDTO` never changes after
  creation and the `orders` cache needs no eviction. A `ProductDTO` carries the ids of the orders
  containing it, which changes when `POST /order` succeeds: `OrderServiceImpl.create` publishes an
  `OrderCreatedEvent` with the product ids and `ProductCacheEvictor` evicts those `products`
  entries in a `@TransactionalEventListener(phase = AFTER_COMMIT)`. Evicting after commit, not
  inside the transaction, leaves no window between an eviction and the commit in which a read
  would cache the pre-commit state; a read that missed the cache before the commit can still
  store the old value after the eviction (see Consequences).
- **Tests.** `HttpCacheIT` and `CompressionIT` pin the ETag, `no-cache`, the bodiless 304 and
  that a gzipped response still carries the ETag; `QueryCountIT` clears every cache before each
  test, keeps the cold-cache statement counts and pins zero statements and zero sessions for a
  repeated by-id read; `ProductApiIT` and a Cucumber scenario prove that a product read after
  `POST /order` lists the new order; `ActuatorIT` pins `cache_gets_total` in the Prometheus
  output.

## Consequences
- A repeated by-id read within the TTL costs no database round trip and no Hibernate session;
  a conditional `GET` costs no response bytes. Measured numbers are in `docs/PERFORMANCE.md`.
- `ProductDTO.orders` can be stale for up to `CACHE_TTL`: on other replicas, after any write that
  bypasses the service (a migration, a manual `INSERT`), and on the node that committed the order
  when a read that missed the cache before the commit stores the old value after the eviction,
  because the eviction and that read race. On that node, without the race, the first read after
  the eviction lists the new order. The 60 s default is deliberately short for that reason.
- Raise the TTL only on evidence: `cache_gets_total{cache="orders",result="hit"}` and
  `cache_gets_total{cache="products",result="hit"}` against `result="miss"` show whether the
  cache earns its staleness.
- `OpenAPI.yaml` documents the reusable `If-None-Match` parameter and the `304` response, and
  declares the `ETag` and `Cache-Control` headers as required on every 200 and 304 of a `GET`,
  so the contract check fails such a response without them.
- Tests share one context and one cache, so a test may never assume a cold or a warm cache for
  an id it did not populate itself (`QueryCountIT` resets the caches; the others create what
  they read).
- Any future endpoint that mutates an order or a product must evict (or update) the affected
  entries in the same after-commit fashion; `CACHE_TYPE=none` is the escape hatch if that is
  ever in doubt.
