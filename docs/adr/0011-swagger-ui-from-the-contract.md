# ADR 0011: Swagger UI from the checked-in contract

[All decision records](README.md) · [Design](../DESIGN.md) · [Assumptions](../ASSUMPTIONS.md) · [Performance](../PERFORMANCE.md)

This record covers how the service serves Swagger UI from the hand-written `OpenAPI.yaml`. Swagger
UI is a browser page that renders an OpenAPI contract and can send the requests it describes. How
to open it is in [API documentation](../DESIGN.md#api-documentation), and the setting that turns it
off is in [Configuration](../DESIGN.md#configuration).

## Status
Accepted

## Context
`OpenAPI.yaml` is written by hand and enforced by `OpenApiContractIT`: every request and response
the check captures must conform to it ([The contract check](../DESIGN.md#the-contract-check)).
Clients and reviewers need a way to read it and to try the API.

springdoc-openapi, the usual way to add Swagger UI to Spring Boot, offers its UI only together
with a specification generated from the controllers. Its `SwaggerConfig` requires
`SpringDocConfiguration`, which `springdoc.api-docs.enabled=false` removes. A generated
specification would lack what the checked-in one states, such as the problem codes, the named
examples and the parameter rules. It would also be a second contract that nothing enforces.

## Decision
- **The checked-in file is served.** The build copies `OpenAPI.yaml` into the jar as
  `swagger-ui/openapi.yaml`, so the service serves the checked-in file, byte for byte, at
  `/openapi.yaml`. The build stops if the file is missing, and the `Dockerfile` copies it into the
  image build.
- **Swagger UI comes from a WebJar.** The `org.webjars:swagger-ui` WebJar (a jar that packages a
  front-end library) is served by the service itself under `/webjars/swagger-ui/`, with no version
  in the path (`webjars-locator-lite`).
- **A static page loads the contract.** `/swagger-ui.html` and `swagger-initializer.js` load
  `/openapi.yaml` in Swagger UI's base layout, which has no box for loading another specification.
  They set `validatorUrl: null`, so the browser makes no call to the public validator.
- **Nothing is generated from the code.** `SwaggerUiIT` fails if `/v3/api-docs` ever answers.
- **On the API port, on by default.** The page is on the API port, so "Try it out" reaches the API
  on the same origin. `SwaggerUiConfig` registers the page, its initializer and the contract only
  while `store.swagger-ui.enabled` (`SWAGGER_UI_ENABLED`) is true; turned off, the three are 404
  problem details like any unknown path. The WebJar's own files under `/webjars/swagger-ui/` are
  still served, because Spring Boot serves every WebJar on the classpath. Without the page and the
  contract they show nothing about this API.

## Consequences
- The page cannot drift from the contract: it renders the file the contract check enforces.
- The documentation is public wherever the API port is, which suits a contract that is published
  to clients anyway. A deployment that does not want it sets `SWAGGER_UI_ENABLED=false`; nothing
  else about request handling changes.
- The management port serves the same files, because the management context shares the service's
  web configuration, as it does for Spring Boot's static resources. The ingress never exposes that
  port, and Compose publishes it on 127.0.0.1 only. From there "Try it out" would be a
  cross-origin request to port 8080, which the API does not allow.
- Dependabot's weekly Gradle update keeps the WebJar current through `swaggerUiVersion` in
  `build.gradle`, major versions excluded as for every Gradle dependency
  ([Dependency updates](../DESIGN.md#dependency-updates)). The image scan covers it like any other
  jar.
- The JavaScript bundle is about 1.7 MB and is sent uncompressed, because response compression
  covers JSON and plain text only.
