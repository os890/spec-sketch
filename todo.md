# TODO — possible future topics

Open points collected while building the demo; none of them block the current
functionality. Grouped by the direction they would take the project.

## SpecSketch DSL: gaps that affect the generated DTOs

- [ ] **Maps / dictionaries** — no way to express `Map<String, X>` yet
  (OpenAPI `additionalProperties`), e.g. `translations (1) : map<string, string>`
  for metadata bags. The most important pure-DTO gap.
- [ ] **Binary / file content** — no `byte[]`-style built-in
  (`type: string` + `format: binary` or `format: byte`); needed for file
  uploads/downloads.
- [ ] **Nullable vs. optional** — `(0 - 1)` means "may be absent"; JSON `null`
  is a distinct concept (`nullable: true`). Matters for PATCH-like semantics
  ("field present but null = clear it").
- [ ] **Structural inheritance without a discriminator** — `extended by` only
  yields `class Sub extends Base` when the hierarchy carries a `discriminator`;
  otherwise openapi-generator flattens the `allOf` into a standalone class that
  repeats the base properties. Documented as-is for now; making it uniform would
  mean either always emitting a discriminator or requiring one for `extended by`.
- [ ] **Documentation attributes** — `description`, `example`, `default` in the
  `{...}` attribute block (deliberately deferred when the validation set was
  chosen); flows into Javadoc on the generated DTOs.

## SpecSketch DSL: gaps that affect the API contract

(less relevant while the demo runs with `generateApis=false`)

- [ ] **Multiple operations per file** — currently one `.sketch` file = one
  operation on one path derived from the file name. Real APIs need several
  endpoints (CRUD) with explicit paths and HTTP methods. The big-ticket
  structural item; foundation for the two below.
- [ ] **Path and query parameters** — headers exist (`@`), but there is no
  `/pets/{petId}` path parameter or `?status=` query parameter support yet
  (could follow the header pattern with sigils).
- [ ] **Error responses / status codes** — only a `200` response today; the
  yaml-first spec defines `400`/`404` with an `ApiError` body, so the
  SpecSketch petstore is not yet expressively equal.
- [ ] **Metadata control** — `info.title`/`version` are hardcoded (`1.0.0`),
  the path is derived from the file name, `application/json` is the only
  content type; no `servers`, no security schemes.

## Tooling / polish

- [ ] **`uniqueItems`** (Set semantics) as an occurrence or attribute option.
- [ ] **Non-ASCII property names** are rejected by the name pattern
  (`[A-Za-z_][A-Za-z0-9_-]*`); fine for the demo, but a JSON payload may carry
  them.
- [ ] **Inline-enum name collisions** — openapi-generator names the inner enum
  of a property `x` `XEnum`; a *named* type called `XEnum` in the same document
  is then shadowed by it inside that DTO. The define-once/name rules cannot see
  this, since the colliding name is synthesized downstream.
- [ ] **License headers on generated sources** — generated DTOs under
  `target/` are RAT-excluded; if headers are wanted there too, use the
  openapi-generator template/license options (not RAT).
- [ ] **Optional copyright line** in the source license headers (currently the
  bare ALv2 header without attribution).
- [ ] **Separate artifact for the generator** — the `sketch-first` module jar
  contains the generator *and* the demo DTOs; if the DTOs were ever published
  as a library (or the DSL used beyond the demo), split the generator into its
  own module/repo (e.g. `os890/spec-sketch` as the tool, demo separate) or a
  classifier jar.
- [ ] **GitHub presentation** — add repository topics (e.g. `openapi`,
  `jakarta-ee`, `dsl`, `code-generation`, `maven`).
