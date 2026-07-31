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
- [x] **Path and query parameters** — done: `{petId}`, `?status`, `$cookie:sid` and
  the location-agnostic draft form `$petId` (emitted as `in: query` and reported).
  What is still missing is *where* a path parameter sits: the template is the file
  name plus one appended segment per path parameter, so `/pets/{petId}/shipments`
  is out of reach until the two items below land.
- [ ] **Error responses / status codes** — only a `200` response today; the
  yaml-first spec defines `400`/`404` with an `ApiError` body, so the
  SpecSketch petstore is not yet expressively equal.
- [ ] **Metadata control** — `info.title`/`version` are hardcoded (`1.0.0`),
  the path is derived from the file name, `application/json` is the only
  content type; no `servers`, no security schemes.

## code-first (Java -> SpecSketch -> OpenAPI)

Everything below is a consequence of the DSL gaps above: the Java model states it,
`JavaSketchGenerator` reports it, but there is no sketch syntax to carry it.

- [ ] **Path and query parameters** — the DSL sigils exist now (`{petId}`, `?status`,
  `$cookie:sid`, `$petId`), but `JavaSketchGenerator` still reports and drops
  `@PathParam`/`@QueryParam`/`@CookieParam`; mapping them onto the sigils is the next step.
- [ ] **`@BeanParam` trees** — a `@BeanParam` POJO is dropped as one opaque unit, so nothing
  inside it is seen: no nested `@BeanParam` layer, and no `@QueryParam`/`@PathParam`/
  `@HeaderParam` member at any depth — a header carried inside such a tree is lost even
  though the DSL can express it. The members may also sit on accessors or constructor
  parameters rather than fields, and `@DefaultValue` means the server fills the value in.
- [ ] **`@FormParam` is a body, not a parameter** — on a `@POST` a `@BeanParam` tree may carry
  `@FormParam` members (or `@RestForm`/`@MultipartForm`), which belong in the request *body* as
  `application/x-www-form-urlencoded`/`multipart/form-data`. Because the body is detected as
  "the parameter carrying no JAX-RS annotation", such a POST currently comes out with no
  request body at all. Emitting it needs the content-type item under "Metadata control"
  (and the binary item for file parts).
- [ ] **Error responses** — only the 2xx payload is emitted; a resource's 404/400
  bodies have no place in a one-operation sketch.
- [ ] **The real HTTP path** — the yaml path comes from the sketch file name
  (`/getPet`), not from `@Path` (`/pets/{petId}`); needs the metadata control item.
- [ ] **One file per endpoint** — a resource with N endpoints yields N sketch/yaml
  pairs, and shared types are re-declared in each of them. Folds into the
  "multiple operations per file" item.
- [ ] **`Map` and `byte[]` members** are reported and dropped (see the map and
  binary items above).
- [ ] **Getter-based models** — properties are read from declared fields; a DTO that
  only exposes getters (no matching field) would come out empty.
- [ ] **Composition for a shared-fields base** — the common members of a base that
  is never used in a payload slot are flattened into every subtype, because
  `extended by` has to sit inside its base and expressing the composition would
  move the whole model under that base. A top-level `<Sub> extends <Base>` form in
  the DSL would allow real `allOf` composition without wrecking the tree; today it
  makes no difference to the generated DTOs, since openapi-generator flattens a
  non-discriminated `allOf` regardless.
- [ ] **Subtypes outside the scanned packages** — the scan covers the base's own
  package plus those of the request/response types (reported as the guess it is);
  a subtype in a third package needs `@JsonSubTypes`, `sealed` or `--subtypes`.
  Scanning the whole classpath would find it, at the cost of walking every jar on
  every run.

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
