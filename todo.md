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

- [x] **Path, query and cookie parameters** — done, including `@BeanParam` trees
  (fields, setters, constructor parameters, nested beans) and
  `@Parameter(in = DEFAULT)` mapped onto the DSL's undecided `$name`.
- [ ] **Attributes on parameters** — `@Size`/`@Pattern`/`@Min`/`@Max`/`@DecimalMin`/
  `@DecimalMax` on a parameter are not carried over, although the DSL accepts the `{…}`
  block on a parameter line and covers it with a test (`?page (0 - 1) : int {min: 1}` →
  `minimum: 1`). Nothing is missing in `sketch-first`; the gap is in `JavaSketchGenerator`:
  - `ParameterLine` has no `attributes` member and `emitParameters` appends no
    `attributeBlock(...)`, so there is nowhere to put them (properties have both).
  - `toProperty` and `collectAttributes` take a `Field`, as do the `member`/`stringMember`/
    `intMember`/`longMember`/`booleanMember` helpers. Widening them to `AnnotatedElement`
    is mechanical now that `declaredAnnotation` already accepts one — that makes them work
    for a method parameter, a setter and a constructor parameter alike; the message in
    `collectAttributes` would take the walk's `Member.description()` instead of building
    `declaringClass.fieldName` itself.
  - **The one real structural change:** `ParameterLine` holds `collection` + `required` and
    derives only `(1)`, `(0 - 1)`, `(0 - *)`, `(1 - *)`. On a *collection*, `@Size` describes
    the number of elements, i.e. the occurrence (the rule properties already follow), so
    `@Size(min = 1, max = 5) List<String> tags` has to become `?tag (1 - 5) : string` — a
    bounded range the record cannot represent. It needs `min`/`max` like `Property`, with
    `required` derived rather than stored.
  - The existing type rules carry over unchanged and need the same reporting:
    `minLength`/`maxLength`/`pattern` only on the plain `string` built-in (so `@Size` on a
    `UUID` path parameter is dropped and reported), `min`/`max` only on numerics, and no
    attributes at all on an enum parameter — the DSL rejects those outright.
- [ ] **`@DefaultValue` loses its value** — it is read for required-ness only (a parameter
  with a default may be left out), while the default itself is dropped without a word. The
  DSL has no `default` attribute yet, so this needs the "Documentation attributes" item
  above first. Worth listing separately because `@DefaultValue` is common on query
  parameters, so this is real information loss, not just an unmapped annotation.
- [ ] **The regex inside a `@Path` template** — `@Path("/{id: \\d+}")` constrains the path
  parameter, and `TEMPLATE_PARAMETER` parses only the name, dropping the `: \\d+` silently.
  It maps onto `{id} (1) : string {pattern: "\\d+"}`, but only for a `string` parameter: on
  the `long` such a template usually guards, the DSL rightly rejects `pattern`, so it would
  have to be dropped and reported.
- [ ] **Parameters declared on resource class fields** — JAX-RS also allows
  `@QueryParam`/`@HeaderParam` on a *field of the resource class*, injected per request and
  therefore applying to every endpoint of it. Only method parameters and `@BeanParam` trees
  are read today.
- [ ] **`@FormParam` bodies** — a form parameter is an error for now (it belongs in the
  request body as `application/x-www-form-urlencoded`, which the DSL cannot express while
  json is its only content type). Emitting it needs the content-type item under
  "Metadata control", and the binary item for multipart file parts.
- [ ] **`@MatrixParam`** — reported and dropped: OpenAPI has no matrix location at all.
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
