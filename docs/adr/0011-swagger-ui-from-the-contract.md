# ADR 0011: Swagger UI from the checked-in contract

## Status
Accepted

## Context
`OpenAPI.yaml` is written by hand and enforced by `OpenApiContractIT`: every request and response
the check captures must conform to it. Clients and reviewers need a way to read it and to try the
API. springdoc-openapi, the usual way to add Swagger UI to Spring Boot, offers its UI only together
with a specification generated from the controllers: its `SwaggerConfig` requires
`SpringDocConfiguration`, which `springdoc.api-docs.enabled=false` removes. A generated
specification would lack what the checked-in one states, such as the problem codes, the named
examples and the parameter rules, and it would be a second contract that nothing enforces.

## Decision
- The build copies `OpenAPI.yaml` into the jar as `swagger-ui/openapi.yaml`, so the service serves
  the checked-in file, byte for byte, at `/openapi.yaml`. The build stops if the file is missing,
  and the `Dockerfile` copies it into the image build.
- Swagger UI comes from the `org.webjars:swagger-ui` WebJar, served by the service itself under
  `/webjars/swagger-ui/` without a version in the path (`webjars-locator-lite`). A static page,
  `/swagger-ui.html`, and `swagger-initializer.js` load `/openapi.yaml` in Swagger UI's base layout,
  which has no box for loading another specification, with `validatorUrl: null`, so the browser
  makes no call to the public validator.
- Nothing is generated from the code. `SwaggerUiIT` fails if `/v3/api-docs` ever answers.
- The page is on the API port, so "Try it out" reaches the API on the same origin, and it is on by
  default. `SwaggerUiConfig` registers the page, its initializer and the contract only while
  `store.swagger-ui.enabled` (`SWAGGER_UI_ENABLED`) is true. Turned off, the three are 404 problem
  details like any unknown path. The WebJar's own files under `/webjars/swagger-ui/` are still
  served, because Spring Boot serves every WebJar on the classpath, but without the page and the
  contract they show nothing about this API.

## Consequences
- The page cannot drift from the contract: it renders the file the contract check enforces.
- The documentation is public wherever the API port is, which suits a contract that is published to
  clients anyway. A deployment that does not want it sets `SWAGGER_UI_ENABLED=false`; nothing else
  about request handling changes.
- The management port serves the same files, as it already serves Spring Boot's static resources,
  because the management context shares the service's web configuration. That port is never
  published, and from there "Try it out" would be a cross-origin request to port 8080, which the
  API does not allow.
- Dependabot keeps the WebJar current through `swaggerUiVersion` in `build.gradle`, and the image
  scan covers it like any other jar.
- The JavaScript bundle is about 1.7 MB and is sent uncompressed, because response compression
  covers JSON and plain text only.
