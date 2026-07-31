/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Java -> SpecSketch translation against small purpose-built resources: response type
 * resolution, cycles, class hierarchies, occurrence/required derivation, validation attributes and
 * everything the DSL cannot express (which has to be reported, never dropped silently).
 *
 * Every sketch produced here is additionally run through SpecSketchGenerator and the resulting
 * document through swagger-parser and SnakeYAML - so a test can only pass if the emitted sketch is
 * valid input for the sketch generator AND the yaml is a document the OpenAPI toolchain accepts.
 */
class JavaSketchGeneratorTest {

    // ------------------------------------------------------------------- model

    enum Level {
        LOW, HIGH
    }

    /** ClassA -> ClassB -> ClassA: the back reference is even declared @NotNull here. */
    static class CycleA {
        @NotNull
        private String name;
        @NotNull
        private CycleB b;
    }

    static class CycleB {
        @NotNull
        private String label;
        @NotNull
        private CycleA a;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class SelfCycle {
        @NotNull
        private Long id;
        private List<SelfCycle> children;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Mid.class, name = "Mid"),
            @JsonSubTypes.Type(value = Leaf.class, name = "Leaf")
    })
    abstract static class Base {
        @NotNull
        private String common;
    }

    @JsonSubTypes(@JsonSubTypes.Type(value = Deepest.class, name = "Deepest"))
    static class Mid extends Base {
        private int middle;
    }

    static class Deepest extends Mid {
        private Instant at;
    }

    static class Leaf extends Base {
        private long leafId;
    }

    /** The discriminator exists as a real field here, so it must not be emitted twice. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
            property = "kind", visible = true)
    @JsonSubTypes(@JsonSubTypes.Type(value = VisibleSub.class, name = "VisibleSub"))
    static class VisibleBase {
        @NotNull
        private String kind;
        @NotNull
        private String label;
    }

    static class VisibleSub extends VisibleBase {
        private int extra;
    }

    /** Plain inheritance: nothing declares the subtypes, so they have to be passed in. */
    static class Animal {
        @NotNull
        private String name;
    }

    static class Dog extends Animal {
        private boolean goodBoy;
    }

    static class Cat extends Animal {
        private int lives;
    }

    /** A sealed hierarchy records its subtypes in the class file - no annotation needed. */
    sealed static class Shape permits Circle, Square {
        @NotNull
        private String colour;
    }

    static final class Circle extends Shape {
        private double radius;
    }

    static final class Square extends Shape {
        private double side;
    }

    /** Declares itself polymorphic but names no subtypes - unresolvable, so an error. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    abstract static class Undeclared {
        @NotNull
        private String common;
    }

    /** A sealed interface has no fields, so it cannot carry the base of an allOf composition. */
    sealed interface Empty permits OnlySub {
    }

    static final class OnlySub implements Empty {
        private int value;
    }

    /** A subtype carrying a whole type tree - the case that used to nest it under 'extended by'. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = PayloadSignal.class, name = "PayloadSignal"))
    abstract static class Signal {
        @NotNull
        private Instant at;
    }

    static class PayloadSignal extends Signal {
        private Payload payload;
    }

    static class Payload {
        @NotNull
        private String data;
        private Detail detail;
    }

    static class Detail {
        @NotNull
        private String note;
    }

    /**
     * The other kind of base: nothing is ever typed as CommonDTO, only the concrete DTOs are, and
     * they are not interchangeable. Its members belong in each subtype, not in a union.
     */
    abstract static class CommonDTO {
        @NotNull
        private LocalDate validFrom;
        private LocalDate validTo;
    }

    static class Wrapper extends CommonDTO {
        @NotNull
        private Inner inner;
        @NotNull
        private Kind kind;
    }

    static class Inner extends CommonDTO {
        @NotNull
        private String label;
    }

    /** Genuinely polymorphic, and itself sharing the common members. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kindType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = KindA.class, name = "KindA"),
            @JsonSubTypes.Type(value = KindB.class, name = "KindB")
    })
    abstract static class Kind extends CommonDTO {
        @NotNull
        private String code;
    }

    static class KindA extends Kind {
        private int a;
    }

    static class KindB extends Kind {
        private long b;
    }

    static class Occurrences {
        private long primitive;              // primitives cannot be null
        @NotNull
        private String annotated;
        private String plain;                // a nullable reference
        private List<String> unbounded;
        @Size(min = 1, max = 3)
        private List<String> bounded;
        @NotNull
        private Set<Level> levels;
    }

    static class Attributes {
        @Size(min = 2, max = 20)
        @Pattern(regexp = "^[a-z,]+$")
        private String text;
        @Min(0)
        @Max(150)
        private int age;
        @Size(min = 4)
        private LocalDate day;               // @Size on a LocalDate cannot be validated
        @Pattern(regexp = "[0-9a-f-]+")
        private UUID id;                     // neither can @Pattern on a UUID
    }

    static class Members {
        @NotNull
        private String kept;
        @JsonProperty("renamed_on_the_wire")
        private String renamed;
        @JsonIgnore
        private String ignored;
        private transient String temporary;
        private static String shared;
        private Map<String, String> translations;   // no DSL equivalent yet
        private byte[] payload;                    // nor for binary content
    }

    // --------------------------------------------------------------- resources

    @Path("/samples")
    static class SampleResource {

        @GET
        @Path("/cycles")
        public CycleA cycle() {
            return null;
        }

        @GET
        @Path("/self")
        public List<SelfCycle> self() {
            return null;
        }

        @GET
        @Path("/hierarchy")
        public List<Base> hierarchy() {
            return null;
        }

        @GET
        @Path("/leaf")
        public Leaf leaf() {
            return null;
        }

        @GET
        @Path("/visible")
        public VisibleBase visible() {
            return null;
        }

        @GET
        @Path("/animals")
        public List<Animal> animals() {
            return null;
        }

        @GET
        @Path("/shapes")
        public List<Shape> shapes() {
            return null;
        }

        @GET
        @Path("/circle")
        public Circle circle() {
            return null;
        }

        @GET
        @Path("/signals")
        public List<Signal> signals() {
            return null;
        }

        @GET
        @Path("/common")
        public Wrapper common() {
            return null;
        }

        @GET
        @Path("/occurrences")
        public Occurrences occurrences() {
            return null;
        }

        @GET
        @Path("/attributes")
        public Attributes attributes() {
            return null;
        }

        @GET
        @Path("/members")
        public Members members() {
            return null;
        }

        @POST
        @Path("/body")
        public Occurrences withBody(Attributes body, @HeaderParam("X-Client-Id") UUID clientId) {
            return null;
        }

        @GET
        @Path("/params/{id}")
        public Occurrences withParams(@PathParam("id") long id, @QueryParam("q") String q) {
            return null;
        }

        @GET
        @Path("/annotated")
        @APIResponse(content = @Content(schema = @Schema(implementation = Occurrences.class)),
                headers = @Header(name = "X-Count", schema = @Schema(implementation = Integer.class)))
        public Response annotated() {
            return null;
        }

        /** Headers declared the OpenAPI way rather than with @HeaderParam, plus required ones. */
        @GET
        @Path("/openapi-headers")
        @APIResponse(content = @Content(schema = @Schema(implementation = Occurrences.class)),
                headers = {
                        @Header(name = "X-Required", required = true,
                                schema = @Schema(implementation = Integer.class)),
                        @Header(name = "X-Optional", schema = @Schema(implementation = Integer.class))
                })
        public Response openApiHeaders(
                @Parameter(name = "X-Declared", in = ParameterIn.HEADER) String declared,
                @HeaderParam("X-Mandatory") @NotNull String mandatory,
                @Parameter(name = "X-Needed", in = ParameterIn.HEADER, required = true) String needed) {
            return null;
        }

        @GET
        @Path("/headers-only")
        @APIResponse(content = @Content(schema = @Schema(implementation = Occurrences.class)))
        public Response headersOnly(@HeaderParam("X-Trace") String trace) {
            return null;
        }
    }

    /** Its own resource: an endpoint whose payload cannot be resolved fails the whole run. */
    @Path("/opaque")
    static class OpaqueResource {

        @GET
        public Response opaque() {
            return null;
        }
    }

    /** Its own resource: an unresolvable hierarchy fails the whole run, like an opaque Response. */
    @Path("/undeclared")
    static class UndeclaredResource {

        @GET
        public Undeclared undeclared() {
            return null;
        }

        @GET
        @Path("/empty")
        public Empty empty() {
            return null;
        }
    }

    @Path("/empty")
    static class NoEndpoints {
        public String notAnEndpoint() {
            return null;
        }
    }

    /** Its own resource: every parameter location, and every way a bean tree can carry them. */
    @Path("/params")
    static class ParameterResource {

        @GET
        @Path("/all/{id}")
        public Occurrences allLocations(@PathParam("id") long id,
                                        @QueryParam("q") String q,
                                        @CookieParam("session") UUID session,
                                        @HeaderParam("X-Trace") String trace) {
            return null;
        }

        @GET
        @Path("/bean/{id}")
        public Occurrences bean(@BeanParam Filter filter) {
            return null;
        }

        @GET
        @Path("/accessors")
        public Occurrences accessors(@BeanParam AccessorBean bean) {
            return null;
        }

        /** The signature lists them the other way round; the template decides the order. */
        @GET
        @Path("/{first}/reordered/{second}")
        public Occurrences reordered(@PathParam("second") long second, @PathParam("first") long first) {
            return null;
        }

        @GET
        @Path("/nowhere")
        public Occurrences missingTemplate(@PathParam("ghost") long ghost) {
            return null;
        }

        @GET
        @Path("/ignored")
        public Occurrences ignored(@MatrixParam("axis") String axis, @Context UriInfo uriInfo) {
            return null;
        }

        @GET
        @Path("/unsupported")
        public Occurrences unsupported(@QueryParam("filter") Filter filter) {
            return null;
        }

        @GET
        @Path("/undecided")
        public Occurrences undecided(@Parameter(name = "tenant") String tenant) {
            return null;
        }
    }

    /** A bean tree: every location, a repeatable parameter, a nested layer, and a plain member. */
    static class Filter {

        @QueryParam("status")
        private Level status;

        @QueryParam("tag")
        private List<String> tags;

        @HeaderParam("X-Bean-Header")
        private String beanHeader;

        @PathParam("id")
        private long id;

        @BeanParam
        private Paging paging;

        /** No JAX-RS annotation: not a parameter for JAX-RS either, so not one here. */
        private String internal;

        /** The same parameter as the field above - one line has to come out, not two. */
        @QueryParam("status")
        public void setStatus(Level status) {
            this.status = status;
        }
    }

    static class Paging {

        @QueryParam("page")
        @DefaultValue("0")
        private int page;

        @QueryParam("size")
        @NotNull
        private Integer size;
    }

    /** Annotations on a setter and on a constructor parameter, not on a field. */
    static class AccessorBean {

        private final long viaConstructor;
        private String viaSetter;

        AccessorBean(@QueryParam("ctor") long viaConstructor) {
            this.viaConstructor = viaConstructor;
        }

        @QueryParam("setter")
        public void setViaSetter(String viaSetter) {
            this.viaSetter = viaSetter;
        }
    }

    /** Its own resource: a form parameter has nowhere to go while json is the only content type. */
    @Path("/form")
    static class FormResource {

        @POST
        public Occurrences form(@FormParam("name") String name) {
            return null;
        }
    }

    /** Its own resource: a bean tree containing itself cannot be walked - or injected. */
    @Path("/cyclic")
    static class CyclicResource {

        @GET
        public Occurrences cyclic(@BeanParam CyclicBean bean) {
            return null;
        }
    }

    static class CyclicBean {

        @QueryParam("q")
        private String q;

        @BeanParam
        private CyclicBean self;
    }

    // ---------------------------------------------------------------- helpers

    private List<String> messages = new ArrayList<>();

    private List<String> sketch(String method, String... overrides) {
        return sketch(SampleResource.class, method, overrides);
    }

    private List<String> sketch(Class<?> resource, String method, String... overrides) {
        return sketch(resource, method, Map.of(), overrides);
    }

    private List<String> sketch(Class<?> resource, String method,
                                Map<String, List<Class<?>>> declaredSubtypes, String... overrides) {
        messages = new ArrayList<>();
        Map<String, String> responseTypes = new LinkedHashMap<>();
        for (String override : overrides) {
            int eq = override.indexOf('=');
            responseTypes.put(override.substring(0, eq), override.substring(eq + 1));
        }
        JavaSketchGenerator.Endpoint endpoint =
                JavaSketchGenerator.readEndpoints(resource, responseTypes, messages).stream()
                        .filter(candidate -> candidate.methodName().equals(method))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no endpoint named " + method));
        List<String> lines = JavaSketchGenerator.toSketch(endpoint, declaredSubtypes, messages);
        // the emitted sketch must be valid DSL input, and its document processable OpenAPI
        assertProcessableOpenApi(yamlOf(lines, method));
        return lines;
    }

    private static String text(List<String> lines) {
        return String.join("\n", lines);
    }

    /** The sketch translated to yaml; what the DSL reports about it belongs to its own tests. */
    private static String yamlOf(List<String> sketchLines, String baseName) {
        return SpecSketchGenerator.generateYaml(sketchLines, baseName, new ArrayList<>());
    }

    private void assertMessage(String fragment) {
        assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
                () -> "expected a message containing '" + fragment + "' but got: " + messages);
    }

    private static void assertProcessableOpenApi(String yaml) {
        Object document = new Yaml().load(yaml); // strict YAML 1.1: string keys only
        assertStringKeys(document, "$", yaml);
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(yaml, null, options);
        assertTrue(result.getMessages().isEmpty(),
                () -> "swagger-parser rejected the document: " + result.getMessages() + "\n" + yaml);
        assertNotNull(result.getOpenAPI(), () -> "no model produced for:\n" + yaml);
    }

    private static void assertStringKeys(Object node, String path, String yaml) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                assertTrue(entry.getKey() instanceof String,
                        () -> "non-string YAML key " + entry.getKey() + " (" + path + ") in:\n" + yaml);
                assertStringKeys(entry.getValue(), path + "." + entry.getKey(), yaml);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                assertStringKeys(list.get(i), path + "[" + i + "]", yaml);
            }
        }
    }

    // ------------------------------------------------------------------ cycles

    @Test
    void aCycleClosesWithAReferenceInsteadOfNestingTheTypeTwice() {
        List<String> lines = sketch("cycle");

        assertEquals(List.of(
                "# Generated by JavaSketchGenerator from GET /samples/cycles",
                "response (1) : CycleA",
                "    name (1) : string",
                "    b (1) : CycleB",
                "        label (1) : string",
                "        a (0 - 1) : CycleA"), lines);
    }

    @Test
    void theCycleClosingMemberIsOptionalEvenWhenTheFieldIsNotNull() {
        // CycleB.a is @NotNull, but that second CycleA is built without its CycleB and
        // @JsonInclude drops the member - requiring it would describe a document never sent
        List<String> lines = sketch("cycle");

        assertTrue(text(lines).contains("a (0 - 1) : CycleA"));
        assertFalse(text(lines).contains("a (1) : CycleA"));
        assertMessage("a closes the cycle back to CycleA and was made optional");
    }

    @Test
    void aSelfCycleReferencesItsOwnTypeByName() {
        List<String> lines = sketch("self");

        assertEquals(List.of(
                "# Generated by JavaSketchGenerator from GET /samples/self",
                "response (0 - *) : SelfCycle",
                "    id (1) : long",
                "    children (0 - *) : SelfCycle"), lines);
    }

    // ------------------------------------------------------------- hierarchies

    @Test
    void aHierarchyDeclaredAsTheBaseIsDefinedRightThere() {
        List<String> lines = sketch("hierarchy");

        assertEquals(List.of(
                "# Generated by JavaSketchGenerator from GET /samples/hierarchy",
                "response (0 - *) : Base",
                "    kind (1) : discriminator",
                "    common (1) : string",
                "    extended by Mid",
                "        middle (1) : int",
                "        extended by Deepest",
                "            at (0 - 1) : Instant",
                "    extended by Leaf",
                "        leafId (1) : long"), lines);
    }

    @Test
    void whatASubtypeAddsAppearsAtItsOwnExtendedByBlock() {
        // the tree follows the DECLARED types: Signal contributes its own members to the response
        // part, while PayloadSignal's extra subtree shows up only at its 'extended by' block
        List<String> lines = sketch("signals");

        assertEquals(List.of(
                "# Generated by JavaSketchGenerator from GET /samples/signals",
                "response (0 - *) : Signal",
                "    kind (1) : discriminator",
                "    at (1) : Instant",
                "    extended by PayloadSignal",
                "        payload (0 - 1) : Payload",
                "            data (1) : string",
                "            detail (0 - 1) : Detail",
                "                note (1) : string"), lines);
    }

    @Test
    void theWholeDocumentNeedsNoTopLevelTypeDefinition() {
        // every type is defined at its first usage, so the sketch reads like a hand-written one:
        // no 'Name (1) : Name' line, which is the DSL's form for a stand-alone reusable type
        for (String method : List.of("signals", "hierarchy", "shapes", "cycle", "self")) {
            String sketch = text(sketch(method));
            for (String line : sketch.split("\n")) {
                assertFalse(line.matches("[A-Za-z_][A-Za-z0-9_]* \\(1\\) : \\1"),
                        () -> method + " emitted a stand-alone definition: " + line + "\n" + sketch);
            }
        }
    }

    @Test
    void theDiscriminatorIsNotRepeatedInTheSubtypeBlocks() {
        // subtypes inherit the property; re-declaring it would collide with the base
        assertEquals(1, text(sketch("hierarchy")).split("discriminator", -1).length - 1);
    }

    @Test
    void anExistingDiscriminatorFieldIsEmittedAsTheDiscriminatorNotAsAString() {
        List<String> lines = sketch("visible");

        assertTrue(text(lines).contains("    kind (1) : discriminator"));
        assertFalse(text(lines).contains("kind (1) : string"));
        assertTrue(text(lines).contains("    label (1) : string"));
    }

    @Test
    void aSealedHierarchyIsDiscoveredFromItsPermitsClause() {
        // no annotation at all: the permitted subclasses are recorded in the class file
        List<String> lines = sketch("shapes");

        assertEquals(List.of(
                "# Generated by JavaSketchGenerator from GET /samples/shapes",
                "response (0 - *) : Shape",
                "    colour (1) : string",
                "    extended by Circle",
                "        radius (1) : double",
                "    extended by Square",
                "        side (1) : double"), lines);
    }

    @Test
    void aSealedSubtypeUsedAsThePayloadResolvesToItsRoot() {
        List<String> lines = sketch("circle");

        assertTrue(text(lines).contains("response (1) : Circle"));
        assertTrue(text(lines).contains("Shape (1) : Shape"));
        assertTrue(text(lines).contains("extended by Circle"));
        assertTrue(text(lines).contains("extended by Square"));
    }

    @Test
    void plainInheritanceIsFoundByScanningTheBasesOwnPackage() {
        // nothing declares Dog/Cat: no @JsonSubTypes, not sealed, no @JsonTypeInfo. Scanning the
        // package is what keeps this from emitting only the parent, which is what it used to do.
        List<String> lines = sketch("animals");

        String sketch = text(lines);
        assertTrue(sketch.contains("response (0 - *) : Animal"), sketch);
        assertTrue(sketch.contains("    name (1) : string"), sketch);
        assertTrue(sketch.contains("    extended by Cat"), sketch);
        assertTrue(sketch.contains("        lives (1) : int"), sketch);
        assertTrue(sketch.contains("    extended by Dog"), sketch);
        assertTrue(sketch.contains("        goodBoy (1) : boolean"), sketch);
    }

    @Test
    void aNamedPackageInAPlainClassDirectoryIsScannedToo() {
        // the classes of this hierarchy live in target/test-classes/org/os890/.../scan - a plain
        // directory, no jar in sight, which is the normal layout for anything under test
        List<String> lines = sketch(
                org.os890.sketch.petstore.scan.VehicleResource.class, "vehicles");

        assertEquals(List.of(
                "# Generated by JavaSketchGenerator from GET /vehicles",
                "response (0 - *) : Vehicle",
                "    plate (1) : string",
                "    extended by Car",
                "        doors (1) : int",
                "    extended by Truck",
                "        payloadTons (1) : double"), lines);
        assertMessage("the scan covered package org.os890.sketch.petstore.scan");
    }

    @Test
    void aSubtypeInAnotherPackageThanItsBaseIsFound() {
        // Event is the shared-library base; PetEvent and the response DTO live in the app package.
        // Scanning only Event's own package would find nothing and fail the run.
        List<String> lines = sketch(
                org.os890.sketch.petstore.scan.VehicleResource.class, "events");

        assertEquals(List.of(
                "# Generated by JavaSketchGenerator from GET /vehicles/events",
                "response (1) : EventFeed",
                "    events (0 - *) : Event",
                "        occurredAt (1) : Instant",
                "        extended by PetEvent",
                "            petId (1) : long"), lines);
    }

    @Test
    void theScanReportNamesEveryPackageItLookedAt() {
        sketch(org.os890.sketch.petstore.scan.VehicleResource.class, "events");

        assertMessage("the scan covered packages [org.os890.sketch.petstore.shared,"
                + " org.os890.sketch.petstore.scan] and found [PetEvent]");
    }

    @Test
    void aScannedHierarchyIsReportedAsTheGuessItIs() {
        sketch("animals");

        assertMessage("nothing declares the subtypes of Animal");
        assertMessage("the scan covered the default package and found [Cat, Dog]");
        assertMessage("a subtype outside stays invisible");
    }

    @Test
    void scannedSubtypesKeepAStableOrder() {
        // the sketch is a versioned file, so the order must not depend on the file system
        String sketch = text(sketch("animals"));

        assertTrue(sketch.indexOf("extended by Cat") < sketch.indexOf("extended by Dog"), sketch);
    }

    @Test
    void anExplicitMappingWinsOverTheScanAndIsNotReported() {
        List<String> lines = sketch(SampleResource.class, "animals",
                Map.of("Animal", List.of(Dog.class)));

        String sketch = text(lines);
        assertTrue(sketch.contains("    extended by Dog"), sketch);
        assertFalse(sketch.contains("extended by Cat"), sketch);
        assertFalse(messages.stream().anyMatch(message -> message.contains("was scanned")),
                () -> messages.toString());
    }

    @Test
    void theExplicitMappingAlsoAcceptsTheQualifiedBaseName() {
        List<String> lines = sketch(SampleResource.class, "animals",
                Map.of(Animal.class.getName(), List.of(Dog.class, Cat.class)));

        assertTrue(text(lines).contains("extended by Dog"));
    }

    @Test
    void aDeclaredHierarchyIsNotScannedAndNotReported() {
        // @JsonSubTypes is authoritative: it is the list Jackson itself deserializes into
        sketch("hierarchy");

        assertFalse(messages.stream().anyMatch(message -> message.contains("was scanned")),
                () -> messages.toString());
    }

    @Test
    void aTypeThatDeclaresItselfPolymorphicWithoutSubtypesIsAnError() {
        JavaSketchGenerator.GeneratorException e = assertThrows(JavaSketchGenerator.GeneratorException.class,
                () -> sketch(UndeclaredResource.class, "undeclared"));

        assertTrue(e.getMessage().contains("it declares @JsonTypeInfo"), e.getMessage());
    }

    @Test
    void aBaseWithoutPropertiesOfItsOwnIsAnError() {
        // a property-less schema is a free-form object, so the model is dropped downstream
        JavaSketchGenerator.GeneratorException e = assertThrows(JavaSketchGenerator.GeneratorException.class,
                () -> sketch(UndeclaredResource.class, "empty"));

        assertTrue(e.getMessage().contains("Empty has subtypes but no properties of its own"),
                e.getMessage());
    }

    @Test
    void aSubtypeUsedAsThePayloadStillEmitsTheWholeHierarchy() {
        List<String> lines = sketch("leaf");

        // the payload references the subtype, but the base has to exist for allOf to resolve
        assertTrue(text(lines).contains("response (1) : Leaf"));
        assertTrue(text(lines).contains("Base (1) : Base"));
        assertTrue(text(lines).contains("extended by Leaf"));
    }

    @Test
    void theHierarchyYamlCarriesDiscriminatorAndTransitiveMapping() {
        String yaml = yamlOf(sketch("hierarchy"), "hierarchy");

        assertTrue(yaml.contains("propertyName: kind"));
        assertTrue(yaml.contains("Mid: '#/components/schemas/Mid'"));
        assertTrue(yaml.contains("Deepest: '#/components/schemas/Deepest'"));
        assertTrue(yaml.contains("Leaf: '#/components/schemas/Leaf'"));
        assertTrue(yaml.contains("- '$ref': '#/components/schemas/Mid'")); // Deepest extends Mid
    }

    // ------------------------------------------------ shared-fields base types

    @Test
    void aSharedFieldsBaseIsFlattenedIntoEverySubtype() {
        // CommonDTO has subtypes but is never used in a payload slot, so a payload can never
        // 'be a CommonDTO' - it is a place to keep common members, not a discriminated union
        List<String> lines = sketch("common");

        assertEquals(List.of(
                "# Generated by JavaSketchGenerator from GET /samples/common",
                "response (1) : Wrapper",
                "    validFrom (1) : LocalDate",
                "    validTo (0 - 1) : LocalDate",
                "    inner (1) : Inner",
                "        validFrom (1) : LocalDate",
                "        validTo (0 - 1) : LocalDate",
                "        label (1) : string",
                "    kind (1) : Kind",
                "        kindType (1) : discriminator",
                "        validFrom (1) : LocalDate",
                "        validTo (0 - 1) : LocalDate",
                "        code (1) : string",
                "        extended by KindA",
                "            a (1) : int",
                "        extended by KindB",
                "            b (1) : long"), lines);
    }

    @Test
    void aSharedFieldsBaseIsNeitherAHierarchyNorReported() {
        String sketch = text(sketch("common"));

        // no union of unrelated DTOs, and no guess to report - the scan never mattered
        assertFalse(sketch.contains("CommonDTO"), sketch);
        assertFalse(sketch.contains("extended by Wrapper"), sketch);
        assertFalse(sketch.contains("extended by Inner"), sketch);
        assertFalse(messages.stream().anyMatch(message -> message.contains("CommonDTO")),
                () -> messages.toString());
    }

    @Test
    void aPolymorphicBaseBelowASharedBaseKeepsTheCommonMembersOnce() {
        // Kind carries the common members; its subtypes contribute only what they add
        String sketch = text(sketch("common"));

        assertEquals(3, sketch.split("validFrom", -1).length - 1, sketch);
        assertFalse(sketch.contains("        extended by KindA\n            validFrom"), sketch);
    }

    @Test
    void aPlainBaseThatIsUsedInAPayloadSlotStaysPolymorphic() {
        // the difference to CommonDTO is only this: something is typed as Animal
        String sketch = text(sketch("animals"));

        assertTrue(sketch.contains("response (0 - *) : Animal"), sketch);
        assertTrue(sketch.contains("    extended by Dog"), sketch);
    }

    // ------------------------------------------------- occurrence and required

    @Test
    void requiredIsDerivedFromPrimitivesAndConstraints() {
        List<String> lines = sketch("occurrences");

        String sketch = text(lines);
        assertTrue(sketch.contains("primitive (1) : long"));       // a primitive is never null
        assertTrue(sketch.contains("annotated (1) : string"));     // @NotNull
        assertTrue(sketch.contains("plain (0 - 1) : string"));     // nullable reference
        assertTrue(sketch.contains("unbounded (0 - *) : string"));
        assertTrue(sketch.contains("bounded (1 - 3) : string"));   // @Size on a collection
        assertTrue(sketch.contains("levels (1 - *) : enum Level [LOW, HIGH]"));
    }

    @Test
    void collectionsBecomeArraysInTheDocument() {
        String yaml = yamlOf(sketch("occurrences"), "occurrences");

        assertTrue(yaml.contains("minItems: 1"));
        assertTrue(yaml.contains("maxItems: 3"));
    }

    // ------------------------------------------------------------- attributes

    @Test
    void stringAndNumericConstraintsBecomeTheAttributeBlock() {
        List<String> lines = sketch("attributes");

        String sketch = text(lines);
        assertTrue(sketch.contains("text (0 - 1) : string {minLength: 2, maxLength: 20,"
                + " pattern: \"^[a-z,]+$\"}"), sketch);
        assertTrue(sketch.contains("age (1) : int {min: 0, max: 150}"), sketch);
    }

    @Test
    void constraintsThatCannotBeValidatedAtRuntimeAreDroppedAndReported() {
        // @Size/@Pattern only work on a CharSequence: on LocalDate/UUID the generated annotation
        // would throw UnexpectedTypeException, and SpecSketch rejects them for the same reason
        List<String> lines = sketch("attributes");

        assertTrue(text(lines).contains("day (0 - 1) : LocalDate\n"), text(lines));
        assertTrue(text(lines).contains("id (0 - 1) : uuid"), text(lines));
        assertMessage("@Size/@Pattern on LocalDate was dropped");
        assertMessage("@Size/@Pattern on UUID was dropped");
    }

    // ---------------------------------------------------------------- members

    @Test
    void jacksonMemberRulesAreHonoured() {
        List<String> lines = sketch("members");

        String sketch = text(lines);
        assertTrue(sketch.contains("kept (1) : string"));
        assertTrue(sketch.contains("renamed_on_the_wire (0 - 1) : string")); // @JsonProperty
        assertFalse(sketch.contains("ignored"));                             // @JsonIgnore
        assertFalse(sketch.contains("temporary"));                           // transient
        assertFalse(sketch.contains("shared"));                              // static
    }

    @Test
    void membersWithoutADslEquivalentAreReported() {
        sketch("members");

        assertMessage("Members.translations of type Map is not expressible");
        assertMessage("Members.payload of type byte[] is not expressible");
    }

    // -------------------------------------------------- request / response part

    @Test
    void aBodyParameterBecomesTheRequestPartAndHeaderParamsBecomeHeaderLines() {
        List<String> lines = sketch("withBody");

        String sketch = text(lines);
        assertTrue(sketch.contains("request (1) : Attributes"));
        assertTrue(sketch.contains("    @X-Client-Id (0 - 1) : uuid"));
        assertTrue(sketch.contains("response (1) : Occurrences"));
        // the header is not a body property of the request type
        assertTrue(yamlOf(lines, "withBody").contains("in: header"));
    }

    @Test
    void pathAndQueryParametersBecomeSigilLines() {
        List<String> lines = sketch("withParams");

        String sketch = text(lines);
        assertTrue(sketch.contains("request (1) : WithParamsParams"), sketch);
        assertTrue(sketch.contains("    {id} (1) : long"), sketch);
        assertTrue(sketch.contains("    ?q (0 - 1) : string"), sketch);
        // parameters are no body, so the operation stays a GET
        String yaml = yamlOf(lines, "withParams");
        assertTrue(yaml.contains("get:"), yaml);
        assertFalse(yaml.contains("requestBody"), yaml);
    }

    // ------------------------------------------------------------- parameters

    @Test
    void everyParameterLocationBecomesItsSigilLine() {
        List<String> lines = sketch(ParameterResource.class, "allLocations");

        String sketch = text(lines);
        assertTrue(sketch.contains("request (1) : AllLocationsParams"), sketch);
        assertTrue(sketch.contains("    {id} (1) : long"), sketch);
        assertTrue(sketch.contains("    ?q (0 - 1) : string"), sketch);
        assertTrue(sketch.contains("    $cookie:session (0 - 1) : uuid"), sketch);
        assertTrue(sketch.contains("    @X-Trace (0 - 1) : string"), sketch);

        String yaml = yamlOf(lines, "allLocations");
        assertTrue(yaml.contains("'/allLocations/{id}':"), yaml);
        for (String location : List.of("in: path", "in: query", "in: cookie", "in: header")) {
            assertTrue(yaml.contains(location), () -> location + " missing in:\n" + yaml);
        }
    }

    @Test
    void aBeanParamTreeIsFlattenedIntoParameterLines() {
        List<String> lines = sketch(ParameterResource.class, "bean");

        String sketch = text(lines);
        assertTrue(sketch.contains("    ?status (0 - 1) : enum Level [LOW, HIGH]"), sketch);
        assertTrue(sketch.contains("    ?tag (0 - *) : string"), sketch);            // a collection repeats
        assertTrue(sketch.contains("    @X-Bean-Header (0 - 1) : string"), sketch);  // was invisible before
        assertTrue(sketch.contains("    {id} (1) : long"), sketch);
        assertTrue(sketch.contains("    ?page (0 - 1) : int"), sketch);              // nested, @DefaultValue
        assertTrue(sketch.contains("    ?size (1) : int"), sketch);                  // nested, @NotNull
        assertFalse(sketch.contains("internal"), sketch);          // no annotation, so not a parameter
        // the field and its setter declare one parameter, not two
        assertEquals(1, lines.stream().filter(line -> line.contains("?status")).count(), sketch);
    }

    @Test
    void annotationsOnSettersAndConstructorParametersAreFound() {
        String sketch = text(sketch(ParameterResource.class, "accessors"));

        assertTrue(sketch.contains("    ?setter (0 - 1) : string"), sketch);
        assertTrue(sketch.contains("    ?ctor (0 - 1) : long"), sketch);
    }

    @Test
    void pathParametersFollowTheOrderOfThePathTemplate() {
        // the signature lists 'second' first, the template says '/{first}/reordered/{second}' - and
        // the DSL appends path parameters to the derived path in the order it reads them
        List<String> lines = sketch(ParameterResource.class, "reordered");

        String sketch = text(lines);
        assertTrue(sketch.indexOf("{first}") < sketch.indexOf("{second}"), sketch);
        assertTrue(yamlOf(lines, "reordered").contains("'/reordered/{first}/{second}':"),
                () -> yamlOf(lines, "reordered"));
    }

    @Test
    void aPathParameterOutsideEveryTemplateIsReported() {
        sketch(ParameterResource.class, "missingTemplate");

        assertMessage("path parameter '{ghost}' appears in no @Path template");
    }

    @Test
    void matrixParametersAndInjectedContextAreReported() {
        String sketch = text(sketch(ParameterResource.class, "ignored"));

        assertMessage("matrix parameter 'axis' is not expressible");
        assertMessage("injected context is not expressible");
        assertFalse(sketch.contains("request"), sketch); // nothing left for a request part
    }

    @Test
    void aParameterTypeWithoutADslEquivalentIsReported() {
        sketch(ParameterResource.class, "unsupported");

        assertMessage("?filter of type Filter is not expressible");
    }

    @Test
    void anOpenApiParameterWithoutALocationBecomesTheUndecidedForm() {
        // @Parameter(in = DEFAULT) states a parameter without stating where from - which is exactly
        // what the DSL's '$name' means, so the guess is left to it (and reported there as well)
        String sketch = text(sketch(ParameterResource.class, "undecided"));

        assertTrue(sketch.contains("    $tenant (0 - 1) : string"), sketch);
        assertMessage("@Parameter(in = DEFAULT) does not say where 'tenant' comes from");
    }

    @Test
    void aFormParameterIsAnErrorBecauseItsBodyCannotBeDescribed() {
        // dropping it leaves a POST whose body appears nowhere in the document
        JavaSketchGenerator.GeneratorException e = assertThrows(
                JavaSketchGenerator.GeneratorException.class,
                () -> sketch(FormResource.class, "form"));

        assertTrue(e.getMessage().contains("application/x-www-form-urlencoded"), e.getMessage());
        assertTrue(e.getMessage().contains("no request body at all"), e.getMessage());
    }

    @Test
    void aCyclicBeanParamTreeIsAnError() {
        JavaSketchGenerator.GeneratorException e = assertThrows(
                JavaSketchGenerator.GeneratorException.class,
                () -> sketch(CyclicResource.class, "cyclic"));

        assertTrue(e.getMessage().contains("contains itself"), e.getMessage());
    }

    @Test
    void theRealHttpPathIsReportedBecauseTheDslDerivesItFromTheFileName() {
        sketch("withParams");

        assertMessage("the real path '/samples/params/{id}' is not expressible");
    }

    // --------------------------------------------- resolving an opaque Response

    @Test
    void anOpaqueResponseIsResolvedFromApiResponseAnnotation() {
        List<String> lines = sketch("annotated");

        assertTrue(text(lines).contains("response (1) : Occurrences"));
        assertMessage("annotated returns Response; payload type Occurrences taken from @APIResponse");
    }

    @Test
    void responseHeadersComeFromTheApiResponseAnnotation() {
        List<String> lines = sketch("annotated");

        assertTrue(text(lines).contains("    @X-Count (0 - 1) : int"));
        assertTrue(yamlOf(lines, "annotated").contains("X-Count:"));
    }

    @Test
    void headersMayBeDeclaredWithTheOpenApiParameterAnnotation() {
        // ParameterIn.HEADER.toString() is 'header', so the lookup has to compare the constant
        List<String> lines = sketch("openApiHeaders");

        assertTrue(text(lines).contains("    @X-Declared (0 - 1) : string"), text(lines));
    }

    @Test
    void requiredHeadersBecomeRequiredOccurrences() {
        List<String> lines = sketch("openApiHeaders");

        String sketch = text(lines);
        assertTrue(sketch.contains("    @X-Mandatory (1) : string"), sketch);   // @NotNull
        assertTrue(sketch.contains("    @X-Needed (1) : string"), sketch);      // @Parameter(required)
        assertTrue(sketch.contains("    @X-Required (1) : int"), sketch);       // @Header(required)
        assertTrue(sketch.contains("    @X-Optional (0 - 1) : int"), sketch);

        String yaml = yamlOf(lines, "openApiHeaders");
        assertTrue(yaml.contains("- name: X-Mandatory"));
        assertTrue(yaml.contains("X-Required:"));
    }

    @Test
    void anOpaqueResponseIsResolvedFromTheExplicitMapping() {
        List<String> lines = sketch(OpaqueResource.class, "opaque", "opaque=" + Occurrences.class.getName());

        assertTrue(text(lines).contains("response (1) : Occurrences"));
        assertMessage("opaque returns Response; payload type Occurrences taken from --response");
    }

    @Test
    void theExplicitMappingSupportsListPayloads() {
        List<String> lines = sketch(OpaqueResource.class, "opaque", "opaque=" + Occurrences.class.getName() + "[]");

        assertTrue(text(lines).contains("response (0 - *) : Occurrences"));
    }

    @Test
    void anUnresolvableResponseIsAnErrorNamingTheMethod() {
        JavaSketchGenerator.GeneratorException e = assertThrows(JavaSketchGenerator.GeneratorException.class,
                () -> sketch(OpaqueResource.class, "opaque"));

        assertTrue(e.getMessage().contains("opaque returns Response"), e.getMessage());
        assertTrue(e.getMessage().contains("--response opaque=<class>"), e.getMessage());
    }

    @Test
    void anUnknownExplicitMappingIsAnError() {
        JavaSketchGenerator.GeneratorException e = assertThrows(JavaSketchGenerator.GeneratorException.class,
                () -> sketch(OpaqueResource.class, "opaque", "opaque=does.not.Exist"));

        assertTrue(e.getMessage().contains("class not found"), e.getMessage());
    }

    // ------------------------------------------------------- resource handling

    @Test
    void aResourceWithoutEndpointsIsAnError() {
        JavaSketchGenerator.GeneratorException e = assertThrows(JavaSketchGenerator.GeneratorException.class,
                () -> JavaSketchGenerator.readEndpoints(NoEndpoints.class, Map.of(), new ArrayList<>()));

        assertTrue(e.getMessage().contains("has no JAX-RS endpoint methods"), e.getMessage());
    }

    @Test
    void endpointsCarryTheirHttpMethodAndFullPath() {
        List<JavaSketchGenerator.Endpoint> endpoints =
                JavaSketchGenerator.readEndpoints(SampleResource.class,
                        Map.of("opaque", Occurrences.class.getName()), new ArrayList<>());

        Map<String, String> paths = new LinkedHashMap<>();
        endpoints.forEach(endpoint -> paths.put(endpoint.methodName(),
                endpoint.httpMethod() + " " + endpoint.path()));
        assertEquals("get /samples/cycles", paths.get("cycle"));
        assertEquals("post /samples/body", paths.get("withBody"));
        assertEquals("get /samples/params/{id}", paths.get("withParams"));
    }

    @Test
    void aHeaderOnlyRequestKeepsTheOperationAGet() {
        List<String> lines = sketch("headersOnly");

        String yaml = yamlOf(lines, "headersOnly");
        assertTrue(yaml.contains("get:"));
        assertFalse(yaml.contains("requestBody"));
        assertTrue(yaml.contains("- name: X-Trace"));
    }

    // --------------------------------------------------- the reusable entry point

    @Test
    void aConfigurationRunsTheGeneratorWithoutACommandLine(@TempDir java.nio.file.Path outputDir)
            throws java.io.IOException {
        JavaSketchGenerator.Configuration configuration = new JavaSketchGenerator.Configuration(
                org.os890.sketch.petstore.PetResource.class, outputDir,
                Map.of("createPet", org.os890.sketch.petstore.model.Pet.class.getName()), Map.of());

        List<JavaSketchGenerator.GeneratedEndpoint> written =
                JavaSketchGenerator.generate(configuration, messages);

        assertEquals(5, written.size());
        for (JavaSketchGenerator.GeneratedEndpoint endpoint : written) {
            assertEquals(outputDir.resolve(endpoint.methodName() + ".sketch"), endpoint.sketchFile());
            assertEquals(outputDir.resolve(endpoint.methodName() + ".yaml"), endpoint.yamlFile());
            assertTrue(java.nio.file.Files.exists(endpoint.sketchFile()));
            assertTrue(java.nio.file.Files.exists(endpoint.yamlFile()));
            assertNotNull(endpoint.httpMethod());
            assertNotNull(endpoint.path());
        }
        // and the command line is just one way to arrive at that configuration
        assertEquals(configuration, JavaSketchGenerator.parseArguments(new String[] {
                org.os890.sketch.petstore.PetResource.class.getName(), outputDir.toString(),
                "--response", "createPet=" + org.os890.sketch.petstore.model.Pet.class.getName()}));
    }

    @Test
    void aResourceWithoutEndpointsLeavesNoOutputDirectoryBehind(@TempDir java.nio.file.Path parent) {
        java.nio.file.Path outputDir = parent.resolve("out");

        assertThrows(JavaSketchGenerator.GeneratorException.class, () -> JavaSketchGenerator.generate(
                new JavaSketchGenerator.Configuration(NoEndpoints.class, outputDir), messages));
        assertFalse(java.nio.file.Files.exists(outputDir));
    }

    @Test
    void argumentsAreRejectedBeforeAnythingIsGenerated() {
        assertThrows(JavaSketchGenerator.GeneratorException.class,
                () -> JavaSketchGenerator.parseArguments(new String[] {"only-one-argument"}));
        JavaSketchGenerator.GeneratorException missing = assertThrows(
                JavaSketchGenerator.GeneratorException.class,
                () -> JavaSketchGenerator.parseArguments(new String[] {"no.such.Resource", "out"}));
        assertTrue(missing.getMessage().contains("class not found: no.such.Resource"),
                () -> "unexpected message: " + missing.getMessage());
    }

    // ------------------------------------------------------- the demo resource

    @Test
    void everyEndpointOfTheDemoResourceProducesAProcessableDocument() {
        List<String> collected = new ArrayList<>();
        List<JavaSketchGenerator.Endpoint> endpoints = JavaSketchGenerator.readEndpoints(
                org.os890.sketch.petstore.PetResource.class,
                Map.of("createPet", org.os890.sketch.petstore.model.Pet.class.getName()),
                collected);

        assertEquals(5, endpoints.size());
        for (JavaSketchGenerator.Endpoint endpoint : endpoints) {
            List<String> lines = JavaSketchGenerator.toSketch(endpoint, collected);
            assertProcessableOpenApi(yamlOf(lines, endpoint.methodName()));
        }
    }

    @Test
    void theDemoResourceBeanParamTreeIsFlattened() {
        List<String> collected = new ArrayList<>();
        JavaSketchGenerator.Endpoint searchPets = JavaSketchGenerator.readEndpoints(
                        org.os890.sketch.petstore.PetResource.class,
                        Map.of("createPet", org.os890.sketch.petstore.model.Pet.class.getName()),
                        collected).stream()
                .filter(endpoint -> endpoint.methodName().equals("searchPets"))
                .findFirst()
                .orElseThrow();

        String sketch = text(JavaSketchGenerator.toSketch(searchPets, collected));
        assertTrue(sketch.contains("request (1) : SearchPetsParams"), sketch);
        assertTrue(sketch.contains("    ?status (0 - 1) : enum PetStatus [AVAILABLE, PENDING, SOLD]"), sketch);
        assertTrue(sketch.contains("    ?tag (0 - *) : string"), sketch);
        assertTrue(sketch.contains("    @X-Request-Id (0 - 1) : uuid"), sketch);   // inside the tree
        assertTrue(sketch.contains("    ?page (0 - 1) : int"), sketch);            // nested layer
        assertTrue(sketch.contains("    ?size (0 - 1) : int"), sketch);
    }

    @Test
    void thePetOwnerCycleOfTheDemoResourceTerminates() {
        List<String> collected = new ArrayList<>();
        JavaSketchGenerator.Endpoint listPets = JavaSketchGenerator.readEndpoints(
                        org.os890.sketch.petstore.PetResource.class,
                        Map.of("createPet", org.os890.sketch.petstore.model.Pet.class.getName()),
                        collected).stream()
                .filter(endpoint -> endpoint.methodName().equals("listPets"))
                .findFirst()
                .orElseThrow();

        String sketch = text(JavaSketchGenerator.toSketch(listPets, collected));
        // Pet -> Owner -> Pet and Category -> Category both close by name
        assertEquals(1, sketch.split("owner \\(0 - 1\\) : Owner", -1).length - 1);
        assertTrue(sketch.contains("        pets (0 - *) : Pet"));
        assertTrue(sketch.contains("        children (0 - *) : Category"));
    }
}
