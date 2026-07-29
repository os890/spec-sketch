# OpenAPI → Jakarta EE 10 DTO Demo

Maven multi-module demo showing how to generate REST-API **DTO classes** from an OpenAPI spec
via `org.openapitools:openapi-generator-maven-plugin`, targeting **JAX-RS 3.1 / Jakarta EE 10**
(`jakarta.*` namespace instead of `javax.*`) — with two ways of authoring the spec:

| Module | Spec authored as | Toolchain |
|---|---|---|
| [`yaml-first/`](yaml-first/) | hand-written OpenAPI YAML (`src/main/openapi/petstore.yaml`) | JDK + Maven only |
| [`typespec-first/`](typespec-first/) | [TypeSpec](https://typespec.io) (`src/main/typespec/main.tsp`), compiled to OpenAPI YAML during the build | additionally Node.js + pnpm |
| [`sketch-first/`](sketch-first/) | custom **SpecSketch** DSL (`src/main/sketch/petstore.sketch`), translated to OpenAPI YAML by a single-file Java generator | JDK + Maven only |

`yaml-first` and `typespec-first` describe the **same API** and end up with functionally
identical generated DTOs (`Pet`, `NewPet`, `Category`, `PetStatus`, `ApiError`).
`sketch-first` demonstrates a response-only API defined in a minimal custom format.

## Requirements

- JDK 17+ (built and tested with JDK 25; code targets `--release 17`, the Jakarta EE 10 baseline)
- Maven 3.9+
- For `typespec-first` only: Node.js + pnpm on the `PATH`
  (the module installs the TypeSpec compiler itself via `pnpm install --ignore-scripts`)

## Build

```bash
mvn clean verify            # both modules
mvn clean verify -pl yaml-first   # only the Node-free module
```

Generated DTOs land in `<module>/target/generated-sources/openapi/src/gen/java/org/os890/sketch/petstore/dto/`
and are registered as a compile source root automatically — generated code never lives in `src/`.

## How it works

### Shared setup (parent `pom.xml`)

The `openapi-generator-maven-plugin` configuration lives in the parent's `<pluginManagement>`;
each module only contributes its `<inputSpec>`. The key generator settings:

| Setting | Value | Effect |
|---|---|---|
| `generatorName` | `jaxrs-spec` | Plain JAX-RS target (server-framework agnostic) |
| `useJakartaEe` | `true` | **The Jakarta EE 10 switch** — emits `jakarta.validation` / `jakarta.ws.rs` imports instead of `javax.*` |
| `generateApis` | `false` | DTOs (models) only, no API interfaces |
| `useSwaggerAnnotations` | `false` | Keeps DTOs dependency-light (no swagger-annotations needed) |
| `dateLibrary` | `java8` | `OffsetDateTime` for `format: date-time` |

### `yaml-first`

Classic spec-first: the OpenAPI YAML is the source of truth, the plugin generates the DTOs
in the `generate-sources` phase. No extra tooling.

### `typespec-first`

The spec is written in TypeSpec — a concise, type-checked DSL that compiles to OpenAPI.
Compare `typespec-first/src/main/typespec/main.tsp` (~70 lines incl. operations) with the
equivalent `yaml-first/src/main/openapi/petstore.yaml`: no indentation pitfalls, real reuse
(`NewPet` is derived from `Pet` via `OmitProperties` instead of copy/paste).

The Maven build chains three steps in the `generate-sources` phase (declaration order matters):

1. `exec-maven-plugin` → `pnpm install --ignore-scripts` (fetches the TypeSpec compiler, pinned via `pnpm-lock.yaml`)
2. `exec-maven-plugin` → `pnpm exec tsp compile src/main/typespec/main.tsp`
   → emits `target/generated-openapi/petstore.yaml` (configured in `tspconfig.yaml`)
3. `openapi-generator-maven-plugin` → generates the DTOs from that emitted YAML

So the TypeSpec file is the single source of truth; both the OpenAPI document and the Java DTOs
are build artifacts.

### `sketch-first`

The spec is written in **SpecSketch**, a deliberately minimal line-based format, and translated
to OpenAPI YAML by [`src/main/tools/SpecSketchGenerator.java`](sketch-first/src/main/tools/SpecSketchGenerator.java) —
a single, dependency-free Java file executed via the JDK's source-code launcher
(`java SpecSketchGenerator.java …`, no compilation step). It generates one operation:
a mandatory top-level `response` defines the response payload, an optional top-level
`request` defines the request payload — with a request the operation becomes a **POST
with a request body**, without one a **GET with an empty request**.

One definition per line:

```
<name> (<occurrence>) : <type>
```

```
request (1) : CreatePetRequest
    name (1) : string
    status (0 - 1) : enum [AVAILABLE, PENDING, SOLD]
    category (0 - 1) : Category

response (1) : PetPageResponse
    pets (0 - *) : Pet
        id (1) : long
        name (1) : string
        category (0 - 1) : Category
            id (1) : long
            name (1) : string
    totalCount (1) : int
    favorite (0 - 1) : Pet
```

Rules:

- **Occurrence:** a number (`1`, `3`) or a range (`0 - 1`, `1 - *`, `2 - 5`).
  `min >= 1` makes the property `required`; `max > 1` or `*` makes it an array
  (with `minItems`/`maxItems` derived from the range).
- **Nesting:** 4 spaces or 1 tab per level, unlimited depth. A line with fewer spaces
  implicitly ends all deeper structures and lands on the level matching its indentation
  (see `totalCount` above, which returns from `Category`-depth back to the response level).
- **Types:** a type with indented properties becomes an object schema — but its details may
  only be defined at its **first occurrence**; every further usage references it by name,
  and the output contains a single schema plus `$ref`s (recursive types work too). A type
  without properties is either a built-in or a reference to a type defined elsewhere in the
  file (see `favorite`, which reuses `Pet` — the request above references the `Category`
  defined later in the response part).
- **Built-in types** (case-insensitive — `string`, `String` and `STRING` are the same; custom
  type names on the other hand are case-sensitive, and two custom types whose names differ
  only in case are rejected as an error): OpenAPI-style `string`, `number`, `integer`,
  `boolean`, `date`, `datetime`, `uuid`; Java primitives and wrappers `int`, `long`, `short`,
  `byte`, `char`, `float`, `double`, `boolean`, `String`, `BigDecimal`, `BigInteger`; and the
  java.time classes `LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`,
  `Instant`, `LocalTime`, `OffsetTime`. With `dateLibrary=java8`, `date`/`LocalDate` become
  `LocalDate` fields and all date-time variants become `OffsetDateTime` fields in the
  generated DTOs; `char` maps to a string with `minLength`/`maxLength` 1.
- **Enums:** shorthand via the keyword `enum` followed by the values in brackets, e.g.
  `gender (1) : enum [M, F]` — emitted as an inline string enum, which the DTO generator
  turns into an inner Java enum of the surrounding class (e.g. `Pet.StatusEnum`). Works in
  arrays too (`workingDays (1 - 7) : enum [MON, …]`). The value type defaults to string and
  can be set explicitly to any string or numeric built-in via `enum:<type>`:
  `gender (1) : enum:String [M, F]` is identical to the default, while
  `stars (1) : enum:int [1, 2, 3, 4, 5]` produces a numeric enum.
- **Inheritance:** inside a type definition, `extended by <SubType>` starts a subtype block
  whose nested lines are the *additional* properties (the wording follows the reading
  direction: the base is extended by its subtypes). Subtypes become `allOf` compositions (the
  DTO generator turns them into `class Car extends Vehicle`), join the type registry
  (referable, define-once, case rules) and may be nested for deeper hierarchies. Marking one
  base property with the reserved type `discriminator` makes the hierarchy polymorphic: the
  property becomes a required string, the schema gets `discriminator` + a mapping of all
  transitive subtypes, and the generated Java carries `@JsonTypeInfo`/`@JsonSubTypes` — so
  JSON deserializes into the concrete subtype:
  ```
  vehicles (0 - *) : Vehicle
      vehicleType (1) : discriminator
      maxSpeed (1) : int
      extended by Car
          doors (1) : int
      extended by Bike
          electric (1) : boolean
  ```
- **Named enums:** a name between keyword and values makes the enum reusable —
  `status (1) : enum PetStatus [AVAILABLE, PENDING, SOLD]` defines it at its first
  occurrence (like object types); every other usage references it plainly by name
  (`status (1) : PetStatus`, forward references included). Composes with the value type
  (`stars (1) : enum:int Rating [1, 2, 3]`). Named enums become a single named schema,
  which the DTO generator turns into one shared top-level Java enum (`PetStatus.java`)
  instead of an inner enum per DTO; the define-once and case-collision rules apply to
  them like to any other named type.
- **Parts:** top-level `response` is mandatory; top-level `request` is optional and makes the
  operation a POST whose request body is required if the request occurrence minimum is >= 1
  (`request (0 - 1) : …` → optional body). Further top-level lines define reusable types.
- **Headers:** direct children of `request`/`response` whose name starts with `@` are HTTP
  headers instead of body properties, e.g. `@X-Client-Id (1) : uuid`. Occurrence works as
  usual (`(1)` → `required: true`, `(0 - 1)` → optional, `(0 - *)` → repeatable/array), and
  any type works — built-ins, inline or named enums. Request headers become `in: header`
  parameters, response headers land in the response's `headers` section. A request that
  contains only headers has no body and stays a GET, so header definitions work without
  forcing a POST.
- **Imports:** a top-level `import <TypeName>` declares a type whose details already live in a
  shared/common yaml file — no local schema is generated; every usage becomes an external
  `$ref` with a type-specific placeholder meant for manual post-editing:
  `$ref: 'TODO-IMPORT/Address.yaml#/components/schemas/Address'` (grep for `TODO-IMPORT`,
  replace the file part). If the file is already known,
  `import Money from "../common-types.yaml"` emits the real ref directly — the petstore spec
  does exactly that with [`common-types.yaml`](sketch-first/src/main/sketch/common-types.yaml),
  and the DTO generator resolves the external file and generates `Money.java` from it.
  Defining properties for an imported type is an error; imports join the type registry
  (define-once, case rules).
- **Attributes:** a property line may end with an optional `{key: value, …}` validation block,
  checked against the property's type: strings support `minLength`, `maxLength` and `pattern`,
  numeric types `min`/`max` (aliases `minimum`/`maximum`). Values may be double-quoted —
  required when they contain commas or braces (`{pattern: "^[a-z]{2,3}$"}`). On arrays the
  attributes apply to the *items* (the array bounds already come from the occurrence). The DTO
  generator turns them into Bean Validation annotations:
  `name (1) : string {minLength: 1, maxLength: 100}` → `@Size(min = 1, max = 100)`.
- **Comments:** every line may end with a comment via `//`, `#` or `/* … */` (the block form
  may also sit mid-line, closed on the same line). Comment-only lines are ignored at *any*
  indentation, so they never affect the nesting. Blank lines are ignored as well.
- Errors (broken indentation, undefined types, conflicting redefinitions) are reported with
  line numbers and fail the build.

The translation itself is covered by
[`SpecSketchGeneratorTest`](sketch-first/src/test/java/SpecSketchGeneratorTest.java)
(request/response variants, every occurrence form, Java primitive/wrapper and java.time
type mapping, deep nesting, multi-level implicit dedents, tab indentation, type reuse
including recursive types, and the error cases). The generator is compiled
into the module's *test* sources via the `build-helper-maven-plugin`, so it stays out
of the produced jar.

The Maven build chains two steps in the `generate-sources` phase:

1. `exec-maven-plugin` → `java src/main/tools/SpecSketchGenerator.java src/main/sketch/petstore.sketch target/generated-openapi/petstore.yaml`
2. `openapi-generator-maven-plugin` → generates the DTOs from that emitted YAML

Try it standalone — with only the input path the YAML is saved next to the input file
(`petstore.sketch` → `petstore.yaml`); a second argument sets an explicit output path,
`-` prints to stdout:

```bash
java sketch-first/src/main/tools/SpecSketchGenerator.java sketch-first/src/main/sketch/petstore.sketch
java sketch-first/src/main/tools/SpecSketchGenerator.java sketch-first/src/main/sketch/petstore.sketch -
```

No build step is needed (JDK source launcher), but since the generator has no dependencies
outside the JDK it can also be compiled classically: `javac SpecSketchGenerator.java`.

The jar built by Maven is runnable — the generator is compiled into the module and the
`Main-Class` manifest entry points at it, so after `mvn package` (or `verify`):

```bash
java -jar sketch-first/target/sketch-first-1.0.0-SNAPSHOT.jar my-api.sketch              # saves my-api.yaml next to the input
java -jar sketch-first/target/sketch-first-1.0.0-SNAPSHOT.jar my-api.sketch out/api.yaml # explicit output path
java -jar sketch-first/target/sketch-first-1.0.0-SNAPSHOT.jar my-api.sketch -            # print to stdout
```

The jar is fully self-contained (the generator has no dependencies outside the JDK), so it
can be copied anywhere and run with any JDK 17+. Note it also contains the module's
generated DTO classes; that doesn't affect running the generator.

A tour of **every** DSL feature in one definition lives in
[`showcase.sketch`](sketch-first/src/main/sketch/showcase.sketch) (not wired into the
build — run it through the generator as above to see the resulting OpenAPI document).

### Usage demo

- `PetResource` (`yaml-first`, `typespec-first`) — hand-written JAX-RS 3.1 resource (`jakarta.ws.rs.*`) using the generated DTOs
- `GeneratedDtoTest` (all modules) — proves the DTOs round-trip through Jackson (builder-style setters, enum mapping)

The generated DTOs carry `jakarta.validation` constraints (`@NotNull`, `@Size`, `@Valid`),
so a Jakarta EE 10 container (WildFly 27+, Payara 6, Open Liberty 23+, GlassFish 7, …)
validates request bodies automatically when a resource parameter is annotated with `@Valid`.

## Deploying for real

The Jakarta APIs are in `provided` scope, so the modules compile standalone. To actually run one,
deploy it as a WAR to any Jakarta EE 10 container, or add a runtime like RESTEasy/Jersey +
Hibernate Validator. If you also want the plugin to generate the resource interfaces
(so your resources implement the spec), set `generateApis=true` and add
`<configOptions><interfaceOnly>true</interfaceOnly></configOptions>`.

## License

[Apache License, Version 2.0](LICENSE)
