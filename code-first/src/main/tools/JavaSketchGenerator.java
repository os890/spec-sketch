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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.jar.JarFile;

/**
 * Java (code-first) -> SpecSketch -> OpenAPI 3 (YAML). The mirror image of SpecSketchGenerator:
 * that one turns a sketch into a spec, this one turns compiled Java into the sketch, then hands it
 * to SpecSketchGenerator so both artifacts can never disagree and the yaml inherits every
 * validation rule that lives there.
 *
 * Entry point is a JAX-RS resource class: its endpoints already state which types go in and out.
 *
 * Endpoints:  one .sketch + .yaml per endpoint method (the DSL describes one operation per file).
 *             HTTP method from @GET/@POST/@PUT/@PATCH/@DELETE, request body from the parameter
 *             that carries no JAX-RS parameter annotation.
 * Responses:  the return type, with List/Set/Collection/array unwrapped into an array occurrence.
 *             A method returning the generic jakarta.ws.rs.core.Response hides its payload, so the
 *             type is taken from the standardized OpenAPI annotations (MicroProfile OpenAPI)
 *             @APIResponse(content = @Content(schema = @Schema(implementation = X.class))) and,
 *             failing that, from an explicit mapping passed to the generator
 *             (--response <method>=<class>, optionally with a [] suffix for a list). If none of
 *             them resolves, the endpoint is an error rather than a silently empty response.
 * Headers:    @HeaderParam parameters (and @Parameter(in = HEADER)) become request headers,
 *             @APIResponse(headers = @Header(...)) become response headers - the '@' lines of the
 *             DSL, required when @NotNull/@Parameter(required)/@Header(required) says so. Path and
 *             query parameters have no DSL equivalent yet and are reported.
 * Properties: non-static, non-transient declared fields, honouring @JsonIgnore and @JsonProperty.
 *             A field is required when it is a primitive, @NotNull/@NotEmpty/@NotBlank, or marked
 *             required via @Schema/@JsonProperty; everything else may be absent, which is what a
 *             nullable Java reference plus @JsonInclude(NON_NULL) actually means on the wire.
 *             @Size/@Pattern/@Min/@Max/@DecimalMin/@DecimalMax and the matching @Schema attributes
 *             become the DSL's {...} attribute block - but only where the target really is a Java
 *             String or a number, since @Size on a LocalDate cannot be validated at runtime.
 * Layout:     the request/response part carries the structural tree: every type is defined at its
 *             first usage, so the tree mirrors the DECLARED types of the properties. A base type
 *             shows its own properties and subtree there; what a subtype adds - including a subtree
 *             of its own - appears at that subtype's 'extended by' block. Declaring the concrete
 *             type instead of the base therefore shows the subtype's additions in the tree as well.
 *             Only a definition that cannot hang off its first usage follows as a top-level block:
 *             'extended by' has to sit inside its base, so a property declared as a SUBTYPE cannot
 *             host the hierarchy and it is emitted after the parts instead.
 * Hierarchies: a type with subtypes, or one extending another model type, is emitted as a whole
 *             hierarchy at the top level: base properties, then one 'extended by' block per
 *             subtype (recursively, so a subtype with subtypes yields a third level). Reflection
 *             cannot enumerate the subclasses of a class, so they come from a declaration where
 *             there is one - @JsonSubTypes, the 'permits' clause of a sealed type, or --subtypes -
 *             and otherwise from scanning the packages that are known to be relevant: the base's
 *             own, plus those of the request and response types - a base often lives in a shared
 *             library while the concrete subtypes sit next to the DTO that uses them. That is a
 *             guess, so it is reported as one. A type that is meant to be polymorphic
 *             (@JsonTypeInfo, sealed, or abstract) but for which nothing is found at all is an
 *             error instead of a document containing only the parent. The
 *             property named by @JsonTypeInfo becomes the DSL's 'discriminator' - which is also
 *             what makes the DTO generator emit real Java inheritance. Every level contributes
 *             only its own declared fields, matching 'extended by' semantics.
 * Cycles:     ClassA -> ClassB -> ClassA terminates by referencing the type by name instead of
 *             nesting it a second time, and the closing reference is forced to be optional: at
 *             runtime that second instance is built differently, its back reference stays null and
 *             @JsonInclude drops it, so the payload has no such member at all. Marking it required
 *             would describe a document that is never sent.
 *
 * Usage: java -cp <classes-and-deps> JavaSketchGenerator <resource-class> <output-dir>
 *            [--response <method>=<class>[]] ... [--responses <file.properties>]
 *            [--subtypes <base>=<class>[,<class>]] ...
 *        Sketch and yaml are written side by side into the output directory, one pair per
 *        endpoint, named after the endpoint method.
 */
public final class JavaSketchGenerator {

    private static final String UNBOUNDED = "*";

    /** The built-ins that accept min/max - the rest only accept the string attributes, or none. */
    private static final Set<String> NUMERIC_BUILT_INS = Set.of("int", "long", "short", "byte",
            "float", "double", "BigDecimal", "BigInteger");

    /** Java type -> SpecSketch built-in. Everything else is an enum, a model type or unsupported. */
    private static final Map<Class<?>, String> BUILT_INS = builtIns();

    private static Map<Class<?>, String> builtIns() {
        Map<Class<?>, String> m = new LinkedHashMap<>();
        m.put(String.class, "string");
        m.put(CharSequence.class, "string");
        m.put(boolean.class, "boolean");
        m.put(Boolean.class, "boolean");
        m.put(char.class, "char");
        m.put(Character.class, "char");
        m.put(byte.class, "byte");
        m.put(Byte.class, "byte");
        m.put(short.class, "short");
        m.put(Short.class, "short");
        m.put(int.class, "int");
        m.put(Integer.class, "int");
        m.put(long.class, "long");
        m.put(Long.class, "long");
        m.put(float.class, "float");
        m.put(Float.class, "float");
        m.put(double.class, "double");
        m.put(Double.class, "double");
        m.put(BigDecimal.class, "BigDecimal");
        m.put(BigInteger.class, "BigInteger");
        m.put(UUID.class, "uuid");
        m.put(LocalDate.class, "LocalDate");
        m.put(LocalDateTime.class, "LocalDateTime");
        m.put(OffsetDateTime.class, "OffsetDateTime");
        m.put(ZonedDateTime.class, "ZonedDateTime");
        m.put(Instant.class, "Instant");
        m.put(LocalTime.class, "LocalTime");
        m.put(OffsetTime.class, "OffsetTime");
        return m;
    }

    static final class GeneratorException extends RuntimeException {
        GeneratorException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ model

    /** One property line to emit: name, occurrence, type and the optional attribute block. */
    private record Property(String name, Class<?> type, boolean collection, String min, String max,
                            Map<String, String> attributes) {

        String occurrence() {
            if (!collection && max.equals("1")) {
                return min.equals("1") ? "(1)" : "(0 - 1)"; // '(0)' would mean 'at most zero'
            }
            return "(" + min + " - " + max + ")";
        }
    }

    /** An endpoint: everything the DSL needs for one operation. */
    record Endpoint(String methodName, String httpMethod, String path,
                    Class<?> requestType, boolean requestIsCollection,
                    Class<?> responseType, boolean responseIsCollection,
                    Map<String, Header> requestHeaders, Map<String, Header> responseHeaders) {
    }

    /** A header line: the type behind it and whether the header has to be present. */
    record Header(Class<?> type, boolean required) {
    }

    /** Emission state: which types already carry their definition, and the current walk path. */
    private static final class Context {
        final Set<String> defined = new LinkedHashSet<>();
        final Deque<String> path = new ArrayDeque<>();
        final Deque<Class<?>> pendingHierarchies = new ArrayDeque<>();
        final Set<Class<?>> queuedHierarchies = new LinkedHashSet<>();
        final List<String> messages;
        private final Map<String, List<Class<?>>> declaredSubtypes;
        /** Resolved once per type: the lookup scans a package and reports what it did. */
        final Map<Class<?>, List<Class<?>>> subtypes = new LinkedHashMap<>();
        /** One directory/jar listing per package, however many types live in it. */
        final Map<String, Set<String>> packageContents = new LinkedHashMap<>();
        /**
         * Packages to scan besides the base's own: those of the request and response types. A base
         * often lives in a shared library while the concrete subtypes sit next to the DTO that
         * uses them. Fixed up front, so the result never depends on the order of the walk.
         */
        final Set<String> scanPackages = new LinkedHashSet<>();
        final Set<ClassLoader> loaders = new LinkedHashSet<>();
        /**
         * Types that appear in a payload slot: the request/response types and every property's
         * declared type. A base that shows up here can arrive as any of its subtypes, which is what
         * makes it polymorphic; one that never does is a shared-fields base and gets flattened.
         */
        final Set<Class<?>> declaredTypes = new LinkedHashSet<>();
        /** Bases whose subtypes were guessed, reported only if the hierarchy is really emitted. */
        final Set<Class<?>> scannedSubtypes = new LinkedHashSet<>();

        Context(Map<String, List<Class<?>>> declaredSubtypes, List<String> messages) {
            this.declaredSubtypes = declaredSubtypes;
            this.messages = messages;
        }

        /** --subtypes accepts either the qualified or the simple name of the base. */
        List<Class<?>> declaredSubtypesOf(Class<?> type) {
            return declaredSubtypes.getOrDefault(type.getName(),
                    declaredSubtypes.getOrDefault(type.getSimpleName(), List.of()));
        }
    }

    // ------------------------------------------------------------------- main

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: java -cp <classpath> JavaSketchGenerator <resource-class>"
                    + " <output-dir> [--response <method>=<class>[]] ... [--responses <file>]"
                    + " [--subtypes <base>=<class>[,<class>]] ...");
            System.exit(2);
        }
        List<String> messages = new ArrayList<>();
        try {
            Class<?> resource = Class.forName(args[0]);
            Path outputDir = Path.of(args[1]);
            Map<String, String> responseTypes = parseOptions(args, "--response", "--responses");
            Map<String, List<Class<?>>> declaredSubtypes =
                    resolveSubtypes(parseOptions(args, "--subtypes", null), resource.getClassLoader());
            for (Endpoint endpoint : readEndpoints(resource, responseTypes, messages)) {
                List<String> sketch = toSketch(endpoint, declaredSubtypes, messages);
                Path sketchFile = outputDir.resolve(endpoint.methodName() + ".sketch");
                Files.createDirectories(outputDir);
                writeSketch(sketchFile, sketch, messages);
                Path yamlFile = outputDir.resolve(endpoint.methodName() + ".yaml");
                SpecSketchGenerator.translate(sketchFile, yamlFile, messages);
                System.out.println("JavaSketchGenerator: " + endpoint.httpMethod().toUpperCase()
                        + " " + endpoint.path() + " -> " + sketchFile + " + " + yamlFile);
            }
        } catch (ClassNotFoundException e) {
            System.err.println("JavaSketchGenerator: class not found: " + e.getMessage());
            System.exit(1);
        } catch (GeneratorException | SpecSketchGenerator.SpecException e) {
            System.err.println("JavaSketchGenerator: " + e.getMessage());
            System.exit(1);
        } finally {
            messages.forEach(message -> System.err.println("JavaSketchGenerator: " + message));
        }
    }

    /** Only rewrites the sketch when it changed, so a build does not churn tracked files. */
    private static void writeSketch(Path sketchFile, List<String> lines, List<String> messages)
            throws IOException {
        String content = String.join(System.lineSeparator(), lines) + System.lineSeparator();
        if (Files.exists(sketchFile) && Files.readString(sketchFile).equals(content)) {
            return;
        }
        if (Files.exists(sketchFile)) {
            messages.add("warning: " + sketchFile + " was regenerated from the Java model"
                    + " - review the change before committing it");
        }
        Files.writeString(sketchFile, content);
    }

    /** Collects the '<key>=<value>' pairs of one option, plus a properties file variant. */
    static Map<String, String> parseOptions(String[] args, String option, String fileOption)
            throws IOException {
        Set<String> known = new LinkedHashSet<>(List.of("--response", "--responses", "--subtypes"));
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            if (!known.contains(args[i])) {
                throw new GeneratorException("unknown argument: " + args[i]);
            }
            String argument = args[i];
            String value = argumentValue(args, ++i, argument);
            if (argument.equals(option)) {
                int eq = value.indexOf('=');
                if (eq < 1) {
                    throw new GeneratorException(option + " expects <key>=<value>, got: " + value);
                }
                values.put(value.substring(0, eq).trim(), value.substring(eq + 1).trim());
            } else if (argument.equals(fileOption)) {
                Properties properties = new Properties();
                try (var in = Files.newInputStream(Path.of(value))) {
                    properties.load(in);
                }
                properties.forEach((key, entry) -> values.put(key.toString(), entry.toString()));
            }
        }
        return values;
    }

    /** '--subtypes Base=a.Sub,b.Sub' - for hierarchies that declare their subtypes nowhere. */
    static Map<String, List<Class<?>>> resolveSubtypes(Map<String, String> declared, ClassLoader loader) {
        Map<String, List<Class<?>>> subtypes = new LinkedHashMap<>();
        declared.forEach((base, names) -> {
            List<Class<?>> resolved = new ArrayList<>();
            for (String name : names.split(",")) {
                try {
                    resolved.add(Class.forName(name.trim(), false, loader));
                } catch (ClassNotFoundException e) {
                    throw new GeneratorException("--subtypes " + base + "=" + names
                            + ": class not found: " + name.trim());
                }
            }
            subtypes.put(base, resolved);
        });
        return subtypes;
    }

    private static String argumentValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new GeneratorException(option + " expects a value");
        }
        return args[index];
    }

    // -------------------------------------------------------- reading the resource

    static List<Endpoint> readEndpoints(Class<?> resource, Map<String, String> responseTypes,
                                        List<String> messages) {
        String basePath = pathOf(resource);
        List<Endpoint> endpoints = new ArrayList<>();
        for (Method method : resource.getDeclaredMethods()) {
            String httpMethod = httpMethodOf(method);
            if (httpMethod == null) {
                continue;
            }
            endpoints.add(readEndpoint(method, httpMethod, basePath, responseTypes, messages));
        }
        if (endpoints.isEmpty()) {
            throw new GeneratorException(resource.getName() + " has no JAX-RS endpoint methods"
                    + " (expected @GET/@POST/@PUT/@PATCH/@DELETE)");
        }
        endpoints.sort((a, b) -> a.methodName().compareTo(b.methodName()));
        return endpoints;
    }

    private static Endpoint readEndpoint(Method method, String httpMethod, String basePath,
                                         Map<String, String> responseTypes, List<String> messages) {
        String path = basePath + pathOf(method);
        Map<String, Header> requestHeaders = new LinkedHashMap<>();
        Class<?> requestType = null;
        boolean requestIsCollection = false;
        for (Parameter parameter : method.getParameters()) {
            String headerName = headerNameOf(parameter);
            if (headerName != null) {
                requestHeaders.put(headerName, new Header(parameter.getType(),
                        isRequiredHeader(parameter)));
                continue;
            }
            String ignored = ignoredParameterKind(parameter);
            if (ignored != null) {
                messages.add("warning: " + method.getName() + ": " + ignored
                        + " is not expressible in SpecSketch yet and was dropped");
                continue;
            }
            if (requestType != null) {
                throw new GeneratorException(method.getName() + " has more than one body parameter");
            }
            requestType = elementTypeOf(parameter.getParameterizedType(), parameter.getType());
            requestIsCollection = isCollection(parameter.getType());
        }
        ResponsePayload response = resolveResponseType(method, responseTypes, messages);
        return new Endpoint(method.getName(), httpMethod, path, requestType, requestIsCollection,
                response.type(), response.collection(), requestHeaders, responseHeadersOf(method));
    }

    private record ResponsePayload(Class<?> type, boolean collection) {
    }

    /**
     * The signature first, then @APIResponse, then the explicit mapping - a method returning the
     * generic Response carries no payload type at all, and guessing would produce an empty schema.
     */
    private static ResponsePayload resolveResponseType(Method method, Map<String, String> responseTypes,
                                                       List<String> messages) {
        Class<?> declared = method.getReturnType();
        if (!isOpaqueReturnType(declared)) {
            return new ResponsePayload(elementTypeOf(method.getGenericReturnType(), declared),
                    isCollection(declared));
        }
        Class<?> annotated = annotatedResponseType(method);
        if (annotated != null) {
            messages.add(method.getName() + " returns " + declared.getSimpleName()
                    + "; payload type " + annotated.getSimpleName() + " taken from @APIResponse");
            return new ResponsePayload(annotated, false);
        }
        String override = responseTypes.get(method.getName());
        if (override != null) {
            boolean collection = override.endsWith("[]");
            String className = collection ? override.substring(0, override.length() - 2) : override;
            try {
                Class<?> type = Class.forName(className.trim(), false, method.getDeclaringClass().getClassLoader());
                messages.add(method.getName() + " returns " + declared.getSimpleName()
                        + "; payload type " + type.getSimpleName() + " taken from --response");
                return new ResponsePayload(type, collection);
            } catch (ClassNotFoundException e) {
                throw new GeneratorException("--response " + method.getName() + "=" + override
                        + ": class not found");
            }
        }
        throw new GeneratorException(method.getName() + " returns " + declared.getSimpleName()
                + ", which hides the payload type - declare it with @APIResponse(content = @Content("
                + "schema = @Schema(implementation = X.class))) or pass --response "
                + method.getName() + "=<class>");
    }

    private static boolean isOpaqueReturnType(Class<?> type) {
        return type == void.class || type == Void.class
                || type.getName().equals("jakarta.ws.rs.core.Response")
                || type.getName().equals("javax.ws.rs.core.Response");
    }

    // ------------------------------------------------------------ sketch emission

    /** One endpoint as SpecSketch lines - the exact input SpecSketchGenerator then translates. */
    static List<String> toSketch(Endpoint endpoint, List<String> messages) {
        return toSketch(endpoint, Map.of(), messages);
    }

    static List<String> toSketch(Endpoint endpoint, Map<String, List<Class<?>>> declaredSubtypes,
                                 List<String> messages) {
        Context context = new Context(declaredSubtypes, messages);
        registerScanRoot(context, endpoint.responseType());
        registerScanRoot(context, endpoint.requestType());
        Set<Class<?>> visited = new LinkedHashSet<>();
        for (Class<?> payload : new Class<?>[] {endpoint.responseType(), endpoint.requestType()}) {
            if (payload != null && isModelType(payload)) {
                context.declaredTypes.add(payload);
                collectDeclaredTypes(payload, context, visited);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Generated by JavaSketchGenerator from " + endpoint.httpMethod().toUpperCase()
                + " " + endpoint.path());
        if (!endpoint.path().equals("/" + endpoint.methodName())) {
            messages.add("warning: " + endpoint.methodName() + ": the yaml path is derived from the"
                    + " file name ('/" + endpoint.methodName() + "'), the real path '" + endpoint.path()
                    + "' is not expressible in SpecSketch yet");
        }
        if (endpoint.requestType() != null || !endpoint.requestHeaders().isEmpty()) {
            emitPart(lines, "request", endpoint.requestType(), endpoint.requestIsCollection(),
                    endpoint.requestHeaders(), context);
        }
        emitPart(lines, "response", endpoint.responseType(), endpoint.responseIsCollection(),
                endpoint.responseHeaders(), context);
        emitPendingHierarchies(lines, context);
        return lines;
    }

    /**
     * Records every type that appears in a payload slot, walking the fields of a type, of its
     * shared bases and of its subtypes. Deliberately blind to the polymorphism question, which is
     * decided from the result.
     */
    private static void collectDeclaredTypes(Class<?> type, Context context, Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return;
        }
        for (Class<?> level = type; level != null && level != Object.class;
             level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (isSkippedField(field)) {
                    continue;
                }
                Class<?> element = elementTypeOf(field.getGenericType(), field.getType());
                if (element != null && isModelType(element)) {
                    context.declaredTypes.add(element);
                    collectDeclaredTypes(element, context, visited);
                }
            }
        }
        for (Class<?> subtype : subtypesOf(type, context)) {
            collectDeclaredTypes(subtype, context, visited);
        }
    }

    /** The package and loader of a top-level DTO are worth scanning for subtypes of any base. */
    private static void registerScanRoot(Context context, Class<?> type) {
        if (type == null || !isModelType(type)) {
            return;
        }
        context.scanPackages.add(type.getPackageName());
        addLoader(context.loaders, type.getClassLoader());
    }

    private static void emitPart(List<String> lines, String part, Class<?> type, boolean collection,
                                 Map<String, Header> headers, Context context) {
        if (type == null) {
            // headers only: no body, which keeps the operation a GET
            lines.add(part + " (1) : " + part.substring(0, 1).toUpperCase() + part.substring(1) + "Headers");
            emitHeaders(lines, headers, context);
            return;
        }
        String typeName = sketchTypeName(type, context);
        lines.add(part + " " + (collection ? "(0 - *)" : "(1)") + " : " + typeName);
        emitHeaders(lines, headers, context);
        if (!inlineDefinition(lines, type, 1, context) && isHierarchyMember(type, context)) {
            queueHierarchy(type, context);
        }
    }

    private static void emitHeaders(List<String> lines, Map<String, Header> headers, Context context) {
        for (Map.Entry<String, Header> entry : headers.entrySet()) {
            Header header = entry.getValue();
            String typeName = header.type().isEnum()
                    ? enumDeclaration(header.type(), context)
                    : sketchTypeName(header.type(), context);
            lines.add(indent(1) + "@" + entry.getKey() + " " + (header.required() ? "(1)" : "(0 - 1)")
                    + " : " + typeName);
        }
    }

    private static void emitProperties(List<String> lines, Class<?> owner, int depth, Context context) {
        for (Property property : propertiesOf(owner, context)) {
            emitProperty(lines, property, depth, context);
        }
    }

    private static void emitProperty(List<String> lines, Property property, int depth, Context context) {
        Class<?> type = property.type();
        if (type.isEnum()) {
            lines.add(indent(depth) + property.name() + " " + property.occurrence() + " : "
                    + enumDeclaration(type, context));
            return;
        }
        if (!isModelType(type)) {
            lines.add(indent(depth) + property.name() + " " + property.occurrence() + " : "
                    + BUILT_INS.get(type) + attributeBlock(property));
            return;
        }
        String typeName = type.getSimpleName();
        // a member pointing back at a type we are inside closes a cycle: that nested instance is
        // built without it and @JsonInclude drops it, so it has to be allowed to be absent
        boolean closesCycle = context.path.contains(typeName);
        Property emitted = closesCycle ? optional(property) : property;
        if (closesCycle && !emitted.occurrence().equals(property.occurrence())) {
            context.messages.add(property.name() + " closes the cycle back to " + typeName
                    + " and was made optional: that nested instance carries no " + typeName
                    + " member at runtime");
        }
        lines.add(indent(depth) + emitted.name() + " " + emitted.occurrence() + " : " + typeName);
        if (!inlineDefinition(lines, type, depth + 1, context) && isHierarchyMember(type, context)) {
            queueHierarchy(type, context);
        }
    }

    /**
     * Defines the declared type right here when this is a place that can host its definition, so
     * the request/response part carries the structural tree: the properties and subtree of the
     * type as declared. A subtype's own additions live at its 'extended by' block, which is why
     * declaring the base shows only the base's structure and declaring the concrete type shows
     * that type's additions too.
     *
     * @return whether the definition was emitted here, or has to be referenced by name instead
     */
    private static boolean inlineDefinition(List<String> lines, Class<?> type, int depth,
                                            Context context) {
        if (!isModelType(type) || context.defined.contains(type.getSimpleName())) {
            return false;
        }
        if (isHierarchyMember(type, context) && type != polymorphicRootOf(type, context)) {
            // 'extended by' has to sit inside its base, and this line declares a subtype - the
            // hierarchy cannot hang off it, so it gets a definition of its own further down
            return false;
        }
        boolean hierarchy = isHierarchyMember(type, context);
        if (hierarchy) {
            requireUsableHierarchy(type, context);
        } else if (isDeclaredPolymorphic(type) && subtypesOf(type, context).isEmpty()) {
            // it says it has subtypes, so a document describing only this type is wrong
            requireUsableHierarchy(type, context);
        }
        context.defined.add(type.getSimpleName());
        context.path.push(type.getSimpleName());
        if (hierarchy) {
            emitHierarchyLevel(lines, type, depth, context);
        } else {
            emitProperties(lines, type, depth, context);
        }
        context.path.pop();
        return true;
    }

    /** A cycle-closing member is absent at runtime, so min occurrence drops to zero. */
    private static Property optional(Property property) {
        return new Property(property.name(), property.type(), property.collection(), "0",
                property.max(), property.attributes());
    }

    // --------------------------------------------------------------- hierarchies

    private static void queueHierarchy(Class<?> type, Context context) {
        Class<?> root = polymorphicRootOf(type, context);
        if (root == null || context.defined.contains(root.getSimpleName())) {
            return; // already defined inline at its first usage
        }
        if (context.queuedHierarchies.add(root)) {
            context.pendingHierarchies.add(root);
        }
    }

    /**
     * Hierarchies are emitted at the top level: 'extended by' has to sit inside its base, and the
     * first usage may well be a subtype, which cannot host the base's definition.
     */
    private static void emitPendingHierarchies(List<String> lines, Context context) {
        while (!context.pendingHierarchies.isEmpty()) {
            Class<?> root = context.pendingHierarchies.poll();
            String rootName = root.getSimpleName();
            if (!context.defined.add(rootName)) {
                continue;
            }
            requireUsableHierarchy(root, context);
            lines.add("");
            lines.add("# polymorphic hierarchy rooted at " + rootName);
            lines.add(rootName + " (1) : " + rootName);
            context.path.push(rootName);
            emitHierarchyLevel(lines, root, 1, context);
            context.path.pop();
        }
    }

    /** Names why the type is expected to have subtypes, when it says so itself. */
    private static String polymorphismReason(Class<?> type) {
        if (declaredAnnotation(type, "com.fasterxml.jackson.annotation.JsonTypeInfo") != null) {
            return " (it declares @JsonTypeInfo)";
        }
        if (type.isSealed()) {
            return " (it is sealed)";
        }
        return Modifier.isAbstract(type.getModifiers()) ? " (it is abstract)" : "";
    }

    /**
     * Checked wherever a hierarchy is emitted, inline or as a block of its own: a base with no
     * discoverable subtypes, or none of its own properties, cannot yield a correct document.
     */
    private static void requireUsableHierarchy(Class<?> root, Context context) {
        String rootName = root.getSimpleName();
        if (context.scannedSubtypes.contains(root)) {
            List<String> names = new ArrayList<>();
            subtypesOf(root, context).forEach(subtype -> names.add(subtype.getSimpleName()));
            context.messages.add("warning: nothing declares the subtypes of " + rootName
                    + ", so the scan covered " + describePackages(packagesToScan(root, context))
                    + " and found " + names + " - a subtype outside stays invisible; declare them"
                    + " with @JsonSubTypes, make " + rootName + " sealed, or pass --subtypes "
                    + rootName + "=<class>[,<class>]");
        }
        if (subtypesOf(root, context).isEmpty()) {
            // reflection cannot enumerate subclasses, so emitting the bare parent would describe a
            // payload that none of the real instances match - the very thing to avoid quietly
            throw new GeneratorException(rootName + " is used as a base type"
                    + polymorphismReason(root) + " but no subtypes were found, neither declared nor"
                    + " in " + describePackages(packagesToScan(root, context))
                    + " - declare them with @JsonSubTypes, make " + rootName + " sealed, or pass"
                    + " --subtypes " + rootName + "=<class>[,<class>]");
        }
        if (propertiesOf(root, context).isEmpty()) {
            throw new GeneratorException(rootName + " has subtypes but no properties of its own"
                    + " - a base type without properties is a free-form object for OpenAPI tooling,"
                    + " so its model is dropped and the subtypes lose their base class");
        }
    }

    private static void emitHierarchyLevel(List<String> lines, Class<?> type, int depth, Context context) {
        String discriminator = discriminatorPropertyOf(type);
        List<Property> properties = propertiesOf(type, context);
        if (discriminator != null && properties.stream().noneMatch(p -> p.name().equals(discriminator))) {
            lines.add(indent(depth) + discriminator + " (1) : discriminator");
        }
        for (Property property : properties) {
            if (property.name().equals(discriminator)) {
                lines.add(indent(depth) + property.name() + " (1) : discriminator");
                continue;
            }
            emitProperty(lines, property, depth, context);
        }
        for (Class<?> subtype : subtypesOf(type, context)) {
            context.defined.add(subtype.getSimpleName());
            lines.add(indent(depth) + "extended by " + subtype.getSimpleName());
            emitHierarchyLevel(lines, subtype, depth + 1, context);
        }
    }

    // --------------------------------------------------------------- properties

    /** Declared fields only: with 'extended by' every level contributes just its own members. */
    static List<Property> propertiesOf(Class<?> owner, Context context) {
        List<Property> properties = new ArrayList<>();
        for (Class<?> level : propertyLevelsOf(owner, context)) {
            collectProperties(level, owner, properties, context);
        }
        return properties;
    }

    /**
     * The classes contributing properties, base first. Inside a polymorphic hierarchy each level
     * contributes only its own fields - the base chain is what 'extended by' expresses. A
     * shared-fields base is represented nowhere else, so its fields are flattened in: without a
     * discriminator the DTO generator flattens such an allOf anyway, which makes this the same
     * generated code with a document that still shows the structural tree.
     */
    private static List<Class<?>> propertyLevelsOf(Class<?> owner, Context context) {
        List<Class<?>> levels = new ArrayList<>();
        levels.add(owner);
        if (polymorphicRootOf(owner, context) == owner || !isHierarchyMember(owner, context)) {
            for (Class<?> base = baseOf(owner); base != null; base = baseOf(base)) {
                levels.add(base);
            }
        }
        java.util.Collections.reverse(levels); // inherited members read first
        return levels;
    }

    private static void collectProperties(Class<?> level, Class<?> owner, List<Property> properties,
                                          Context context) {
        for (Field field : level.getDeclaredFields()) {
            if (isSkippedField(field)) {
                continue;
            }
            Class<?> raw = field.getType();
            // byte[] is base64 on the wire, not an array of numbers, and SpecSketch has no
            // binary built-in yet - emitting '(0 - *) : byte' would describe the wrong document
            boolean binary = raw == byte[].class || raw == Byte[].class;
            boolean collection = !binary && isCollection(raw);
            Class<?> type = binary ? null : elementTypeOf(field.getGenericType(), raw);
            if (type == null || (!isModelType(type) && !type.isEnum() && !BUILT_INS.containsKey(type))) {
                context.messages.add("warning: " + owner.getSimpleName() + "." + field.getName()
                        + " of type " + raw.getSimpleName()
                        + " is not expressible in SpecSketch yet and was dropped");
                continue;
            }
            properties.add(toProperty(field, type, collection, context));
        }
    }

    private static boolean isSkippedField(Field field) {
        return field.isSynthetic() || Modifier.isStatic(field.getModifiers())
                || Modifier.isTransient(field.getModifiers())
                || hasAnnotation(field, "com.fasterxml.jackson.annotation.JsonIgnore");
    }

    private static Property toProperty(Field field, Class<?> type, boolean collection, Context context) {
        String name = jsonNameOf(field);
        boolean required = isRequired(field);
        String min = required ? "1" : "0";
        String max = collection ? UNBOUNDED : "1";
        Map<String, String> attributes = new LinkedHashMap<>();
        Integer sizeMin = intMember(field, "jakarta.validation.constraints.Size", "min");
        Integer sizeMax = intMember(field, "jakarta.validation.constraints.Size", "max");
        if (collection) {
            // on a collection @Size describes the number of elements, i.e. the occurrence
            if (sizeMin != null && sizeMin > 0) {
                min = String.valueOf(sizeMin);
            }
            if (sizeMax != null && sizeMax != Integer.MAX_VALUE) {
                max = String.valueOf(sizeMax);
            }
        } else {
            collectAttributes(field, type, sizeMin, sizeMax, attributes, context);
        }
        return new Property(name, type, collection, min, max, attributes);
    }

    /**
     * Only a plain String carries minLength/maxLength/pattern and only numbers carry min/max:
     * SpecSketch rejects the rest, because @Size on a LocalDate or UUID cannot be validated.
     */
    private static void collectAttributes(Field field, Class<?> type, Integer sizeMin, Integer sizeMax,
                                          Map<String, String> attributes, Context context) {
        String builtIn = BUILT_INS.get(type);
        boolean plainString = "string".equals(builtIn);
        boolean numeric = builtIn != null && NUMERIC_BUILT_INS.contains(builtIn);
        String pattern = stringMember(field, "jakarta.validation.constraints.Pattern", "regexp");
        boolean hasStringConstraint = sizeMin != null || sizeMax != null || pattern != null;
        if (hasStringConstraint && !plainString) {
            if (builtIn != null) {
                context.messages.add("warning: " + field.getDeclaringClass().getSimpleName() + "."
                        + field.getName() + ": @Size/@Pattern on " + type.getSimpleName()
                        + " was dropped - it is not a Java String, so the constraint cannot be validated");
            }
        } else if (plainString) {
            if (sizeMin != null && sizeMin > 0) {
                attributes.put("minLength", String.valueOf(sizeMin));
            }
            if (sizeMax != null && sizeMax != Integer.MAX_VALUE) {
                attributes.put("maxLength", String.valueOf(sizeMax));
            }
            if (pattern != null) {
                attributes.put("pattern", '"' + pattern + '"');
            }
        }
        if (!numeric) {
            return;
        }
        Long min = longMember(field, "jakarta.validation.constraints.Min", "value");
        Long max = longMember(field, "jakarta.validation.constraints.Max", "value");
        String decimalMin = stringMember(field, "jakarta.validation.constraints.DecimalMin", "value");
        String decimalMax = stringMember(field, "jakarta.validation.constraints.DecimalMax", "value");
        if (min != null) {
            attributes.put("min", String.valueOf(min));
        } else if (decimalMin != null) {
            attributes.put("min", decimalMin);
        }
        if (max != null) {
            attributes.put("max", String.valueOf(max));
        } else if (decimalMax != null) {
            attributes.put("max", decimalMax);
        }
    }

    private static String attributeBlock(Property property) {
        if (property.attributes().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        property.attributes().forEach((key, value) -> parts.add(key + ": " + value));
        return " {" + String.join(", ", parts) + "}";
    }

    private static boolean isRequired(Field field) {
        if (field.getType().isPrimitive()) {
            return true; // a primitive can never be null, so it is always on the wire
        }
        if (hasAnnotation(field, "jakarta.validation.constraints.NotNull")
                || hasAnnotation(field, "jakarta.validation.constraints.NotEmpty")
                || hasAnnotation(field, "jakarta.validation.constraints.NotBlank")) {
            return true;
        }
        Boolean jsonRequired = booleanMember(field, "com.fasterxml.jackson.annotation.JsonProperty",
                "required");
        if (Boolean.TRUE.equals(jsonRequired)) {
            return true;
        }
        return Boolean.TRUE.equals(booleanMember(field, OPEN_API + ".media.Schema", "required"));
    }

    private static String jsonNameOf(Field field) {
        String name = stringMember(field, "com.fasterxml.jackson.annotation.JsonProperty", "value");
        return name == null || name.isEmpty() ? field.getName() : name;
    }

    // ------------------------------------------------------------------- enums

    /** First occurrence declares the reusable named enum, every later one references it. */
    private static String enumDeclaration(Class<?> type, Context context) {
        String name = type.getSimpleName();
        if (!context.defined.add(name)) {
            return name;
        }
        List<String> values = new ArrayList<>();
        for (Object constant : type.getEnumConstants()) {
            values.add(((Enum<?>) constant).name());
        }
        return "enum " + name + " [" + String.join(", ", values) + "]";
    }

    // ----------------------------------------------------------------- helpers

    private static String sketchTypeName(Class<?> type, Context context) {
        if (type.isEnum()) {
            return type.getSimpleName();
        }
        String builtIn = BUILT_INS.get(type);
        return builtIn != null ? builtIn : type.getSimpleName();
    }

    static boolean isModelType(Class<?> type) {
        return !type.isPrimitive() && !type.isEnum() && !BUILT_INS.containsKey(type)
                && !type.getName().startsWith("java.");
    }

    static boolean isHierarchyMember(Class<?> type, Context context) {
        return polymorphicRootOf(type, context) != null;
    }

    /**
     * A base is polymorphic when it has subtypes AND a payload can actually arrive as one of them:
     * either it says so itself (@JsonTypeInfo, @JsonSubTypes, sealed) or it is used in a payload
     * slot, so whatever fills that slot may be any subtype.
     *
     * A common base like 'BaseDTO' with validFrom/validTo is the other kind: it has subtypes, but
     * nothing is ever typed as BaseDTO - only the concrete DTOs are, and they are not
     * interchangeable. Treating it as a discriminated union would flatten the whole model into one
     * list of siblings under it; its properties belong in each subtype instead.
     */
    static boolean isPolymorphicBase(Class<?> type, Context context) {
        if (subtypesOf(type, context).isEmpty()) {
            return false;
        }
        return declaresPolymorphism(type) || context.declaredTypes.contains(type);
    }

    /** The topmost ancestor that is a polymorphic base, or null if the type is in no hierarchy. */
    static Class<?> polymorphicRootOf(Class<?> type, Context context) {
        Class<?> root = null;
        for (Class<?> current = type; current != null; current = baseOf(current)) {
            if (isPolymorphicBase(current, context)) {
                root = current;
            }
        }
        return root;
    }

    private static boolean declaresPolymorphism(Class<?> type) {
        return declaredAnnotation(type, "com.fasterxml.jackson.annotation.JsonTypeInfo") != null
                || declaredAnnotation(type, "com.fasterxml.jackson.annotation.JsonSubTypes") != null
                || type.isSealed();
    }

    /**
     * A type that is meant to have subtypes even when none were found: @JsonTypeInfo says so
     * outright, an abstract class can never be the payload itself, and 'sealed' lists them.
     * Without this a base whose subtypes are not discoverable would quietly emit just the parent.
     */
    static boolean isDeclaredPolymorphic(Class<?> type) {
        return declaredAnnotation(type, "com.fasterxml.jackson.annotation.JsonTypeInfo") != null
                || type.isSealed()
                || (Modifier.isAbstract(type.getModifiers()) && !type.isInterface());
    }

    /** The base of a type: a model superclass, or the sealed model interface that permits it. */
    private static Class<?> baseOf(Class<?> type) {
        Class<?> superclass = type.getSuperclass();
        if (superclass != null && superclass != Object.class && isModelType(superclass)) {
            return superclass;
        }
        for (Class<?> candidate : type.getInterfaces()) {
            if (isModelType(candidate) && candidate.isSealed()) {
                return candidate;
            }
        }
        return null;
    }



    /**
     * The direct subtypes of a type, resolved once and then cached.
     *
     * A declaration wins: @JsonSubTypes, the 'permits' clause of a sealed type (recorded in the
     * class file, so no annotation is needed), or an explicit --subtypes mapping. @JsonSubTypes in
     * particular is authoritative - it is the list Jackson itself deserializes into, so a subtype
     * missing from it would never appear on the wire either.
     *
     * With no declaration the type's own package is scanned, which covers the common layout where
     * a hierarchy lives together in one model package. That is a guess, so it is reported: a
     * subtype in another package stays invisible and needs --subtypes.
     */
    static List<Class<?>> subtypesOf(Class<?> type, Context context) {
        return context.subtypes.computeIfAbsent(type, candidate -> resolveSubtypes(candidate, context));
    }

    private static List<Class<?>> resolveSubtypes(Class<?> type, Context context) {
        Set<Class<?>> declared = new LinkedHashSet<>();
        Annotation annotation = declaredAnnotation(type, "com.fasterxml.jackson.annotation.JsonSubTypes");
        if (annotation != null && invoke(annotation, "value") instanceof Object[] entries) {
            for (Object entry : entries) {
                declared.add((Class<?>) invoke((Annotation) entry, "value"));
            }
        }
        if (type.getPermittedSubclasses() != null) {
            declared.addAll(List.of(type.getPermittedSubclasses()));
        }
        declared.addAll(context.declaredSubtypesOf(type));
        if (!declared.isEmpty()) {
            return new ArrayList<>(declared);
        }
        List<Class<?>> scanned = scanPackagesForSubtypes(type, context);
        if (!scanned.isEmpty()) {
            // reported from requireUsableHierarchy: a type whose subtypes were guessed may still
            // turn out to be a shared-fields base, and then the guess never mattered
            context.scannedSubtypes.add(type);
        }
        return scanned;
    }

    /** Direct subtypes among the classes of the scanned packages, in a stable order. */
    private static List<Class<?>> scanPackagesForSubtypes(Class<?> type, Context context) {
        Set<ClassLoader> loaders = classLoadersFor(type, context);
        Set<Class<?>> subtypes = new LinkedHashSet<>();
        for (String packageName : packagesToScan(type, context)) {
            for (String className : classNamesIn(packageName, loaders, context)) {
                for (ClassLoader loader : loaders) {
                    try {
                        // never initialize: loading a model class must not run any static block
                        Class<?> candidate = Class.forName(className, false, loader);
                        if (candidate != type && baseOf(candidate) == type) {
                            subtypes.add(candidate);
                        }
                        break; // the first loader that can see it decides
                    } catch (Throwable unusable) {
                        // a class this loader cannot see may still be visible to the next one
                    }
                }
            }
        }
        // the emitted sketch is a versioned file, so the order must not depend on the file system
        List<Class<?>> ordered = new ArrayList<>(subtypes);
        ordered.sort(Comparator.comparing(Class::getName));
        return ordered;
    }

    /** The base's own package plus the ones the request and response types live in. */
    private static Set<String> packagesToScan(Class<?> type, Context context) {
        Set<String> packages = new LinkedHashSet<>();
        packages.add(type.getPackageName());
        packages.addAll(context.scanPackages);
        return packages;
    }

    /**
     * The loaders to ask, most specific first. A package is regularly split across roots - the
     * base class in target/classes or a dependency jar, a subtype in target/test-classes - and
     * those roots are not always visible to the same loader: the base's own loader can be the
     * parent of the one that has the test classes. Asking only that parent would miss them.
     */
    private static Set<ClassLoader> classLoadersFor(Class<?> type, Context context) {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        addLoader(loaders, type.getClassLoader());
        loaders.addAll(context.loaders);
        addLoader(loaders, Thread.currentThread().getContextClassLoader());
        addLoader(loaders, JavaSketchGenerator.class.getClassLoader());
        addLoader(loaders, ClassLoader.getSystemClassLoader());
        return loaders;
    }

    private static void addLoader(Set<ClassLoader> loaders, ClassLoader loader) {
        if (loader != null) {
            loaders.add(loader);
        }
    }

    /**
     * Class names directly in one package. Every root that any of the loaders knows is walked,
     * whether it is an exploded class directory (target/classes, target/test-classes) or a jar.
     */
    private static Set<String> classNamesIn(String packageName, Set<ClassLoader> loaders,
                                            Context context) {
        return context.packageContents.computeIfAbsent(packageName, name -> {
            String path = name.replace('.', '/');
            Set<String> classNames = new TreeSet<>();
            for (ClassLoader loader : loaders) {
                try {
                    Enumeration<URL> roots = loader.getResources(path);
                    while (roots.hasMoreElements()) {
                        collectClassNames(roots.nextElement(), name, path, classNames);
                    }
                } catch (IOException unreadable) {
                    // an unreadable classpath entry simply contributes no candidates
                }
            }
            return classNames;
        });
    }

    private static void collectClassNames(URL root, String packageName, String path,
                                          Set<String> classNames) {
        try {
            if (root.getProtocol().equals("file")) {
                Path directory = Path.of(root.toURI());
                if (!Files.isDirectory(directory)) {
                    return;
                }
                try (var entries = Files.list(directory)) {
                    entries.map(entry -> entry.getFileName().toString())
                            .filter(fileName -> fileName.endsWith(".class"))
                            .forEach(fileName -> classNames.add(qualify(packageName,
                                    fileName.substring(0, fileName.length() - ".class".length()))));
                }
            } else if (root.getProtocol().equals("jar")) {
                String file = root.getPath();
                String jar = file.substring(file.indexOf(':') + 1, file.indexOf("!/"));
                try (JarFile jarFile = new JarFile(URLDecoder.decode(jar, StandardCharsets.UTF_8))) {
                    jarFile.stream()
                            .map(entry -> entry.getName())
                            .filter(entry -> entry.endsWith(".class") && entry.startsWith(path)
                                    && entry.lastIndexOf('/') == path.length()) // direct children
                            .forEach(entry -> classNames.add(
                                    entry.substring(0, entry.length() - ".class".length())
                                            .replace('/', '.')));
                }
            }
        } catch (Exception unusable) {
            // same as above: a classpath entry we cannot walk yields no candidates
        }
    }

    private static String qualify(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private static String describePackages(Set<String> packageNames) {
        List<String> described = new ArrayList<>();
        packageNames.forEach(name -> described.add(name.isEmpty() ? "the default package" : name));
        if (described.size() > 1) {
            return "packages " + described;
        }
        String only = described.get(0);
        return only.startsWith("the ") ? only : "package " + only;
    }

    /**
     * The discriminator of the level that DECLARES @JsonTypeInfo. Subtypes inherit the property,
     * so repeating it in their 'extended by' block would re-declare a member of the base.
     */
    static String discriminatorPropertyOf(Class<?> type) {
        Annotation annotation = declaredAnnotation(type, "com.fasterxml.jackson.annotation.JsonTypeInfo");
        if (annotation == null) {
            return null;
        }
        Object property = invoke(annotation, "property");
        String name = property == null ? "" : property.toString();
        return name.isEmpty() ? "type" : name;
    }

    private static boolean isCollection(Class<?> type) {
        return Collection.class.isAssignableFrom(type) || type.isArray();
    }

    /** The element type behind List/Set/Collection/array, or the type itself. */
    private static Class<?> elementTypeOf(Type generic, Class<?> raw) {
        if (raw.isArray()) {
            return raw.getComponentType();
        }
        if (!Collection.class.isAssignableFrom(raw)) {
            return raw;
        }
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return null; // a raw collection says nothing about its content
    }

    private static String indent(int depth) {
        return "    ".repeat(depth);
    }

    // ------------------------------------------------- annotation access by name

    /*
     * JAX-RS, Jackson and the OpenAPI annotations are read by name instead of by import: the
     * generator then also runs against a model that uses only some of them, and a missing
     * annotation library never turns into a NoClassDefFoundError.
     */

    /** The standardized OpenAPI annotations (MicroProfile OpenAPI), not the Swagger vendor set. */
    private static final String OPEN_API = "org.eclipse.microprofile.openapi.annotations";

    /** Annotation enums like ParameterIn override toString(), so comparisons use the constant. */
    private static String enumName(Object value) {
        return value instanceof Enum<?> constant ? constant.name() : String.valueOf(value);
    }

    private static String pathOf(Object element) {
        Annotation annotation = element instanceof Class<?> type
                ? declaredAnnotation(type, "jakarta.ws.rs.Path")
                : declaredAnnotation((Method) element, "jakarta.ws.rs.Path");
        if (annotation == null) {
            return "";
        }
        String value = String.valueOf(invoke(annotation, "value"));
        if (value.isEmpty() || value.equals("/")) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String httpMethodOf(Method method) {
        for (String name : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            if (declaredAnnotation(method, "jakarta.ws.rs." + name) != null) {
                return name.toLowerCase();
            }
        }
        return null;
    }

    private static String headerNameOf(Parameter parameter) {
        Annotation headerParam = declaredAnnotation(parameter, "jakarta.ws.rs.HeaderParam");
        if (headerParam != null) {
            return String.valueOf(invoke(headerParam, "value"));
        }
        Annotation openApi = declaredAnnotation(parameter, OPEN_API + ".parameters.Parameter");
        // ParameterIn overrides toString() ('header'), so the comparison has to use the constant
        if (openApi != null && "HEADER".equals(enumName(invoke(openApi, "in")))) {
            return String.valueOf(invoke(openApi, "name"));
        }
        return null;
    }

    /** A required request header: either the parameter is @NotNull or @Parameter says so. */
    private static boolean isRequiredHeader(Parameter parameter) {
        for (Annotation annotation : parameter.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals("jakarta.validation.constraints.NotNull")) {
                return true;
            }
        }
        Annotation openApi = declaredAnnotation(parameter, OPEN_API + ".parameters.Parameter");
        return openApi != null && Boolean.TRUE.equals(invoke(openApi, "required"));
    }

    /** Path/query/cookie/form parameters and injected context - none of them is a body. */
    private static String ignoredParameterKind(Parameter parameter) {
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put("jakarta.ws.rs.PathParam", "path parameter");
        kinds.put("jakarta.ws.rs.QueryParam", "query parameter");
        kinds.put("jakarta.ws.rs.CookieParam", "cookie parameter");
        kinds.put("jakarta.ws.rs.FormParam", "form parameter");
        kinds.put("jakarta.ws.rs.MatrixParam", "matrix parameter");
        kinds.put("jakarta.ws.rs.BeanParam", "bean parameter");
        kinds.put("jakarta.ws.rs.core.Context", "injected context");
        for (Map.Entry<String, String> kind : kinds.entrySet()) {
            Annotation annotation = declaredAnnotation(parameter, kind.getKey());
            if (annotation != null) {
                Object value = invoke(annotation, "value");
                String name = value == null ? parameter.getName() : String.valueOf(value);
                return kind.getValue() + " '" + name + "'";
            }
        }
        return null;
    }

    private static Class<?> annotatedResponseType(Method method) {
        for (Annotation response : apiResponsesOf(method)) {
            Object content = invoke(response, "content");
            if (!(content instanceof Object[] contents)) {
                continue;
            }
            for (Object entry : contents) {
                Object schema = invoke((Annotation) entry, "schema");
                if (schema == null) {
                    continue;
                }
                Object implementation = invoke((Annotation) schema, "implementation");
                if (implementation instanceof Class<?> type && type != Void.class) {
                    return type;
                }
            }
        }
        return null;
    }

    private static Map<String, Header> responseHeadersOf(Method method) {
        Map<String, Header> headers = new LinkedHashMap<>();
        for (Annotation response : apiResponsesOf(method)) {
            Object declared = invoke(response, "headers");
            if (!(declared instanceof Object[] entries)) {
                continue;
            }
            for (Object entry : entries) {
                Annotation header = (Annotation) entry;
                String name = String.valueOf(invoke(header, "name"));
                Object schema = invoke(header, "schema");
                Class<?> type = String.class;
                if (schema != null && invoke((Annotation) schema, "implementation") instanceof Class<?> impl
                        && impl != Void.class) {
                    type = impl;
                }
                headers.put(name, new Header(type, Boolean.TRUE.equals(invoke(header, "required"))));
            }
        }
        return headers;
    }

    private static List<Annotation> apiResponsesOf(Method method) {
        List<Annotation> responses = new ArrayList<>();
        Annotation single = declaredAnnotation(method, OPEN_API + ".responses.APIResponse");
        if (single != null) {
            responses.add(single);
        }
        Annotation container = declaredAnnotation(method, OPEN_API + ".responses.APIResponses");
        if (container != null && invoke(container, "value") instanceof Object[] entries) {
            for (Object entry : entries) {
                responses.add((Annotation) entry);
            }
        }
        return responses;
    }

    private static boolean hasAnnotation(Field field, String className) {
        return declaredAnnotation(field, className) != null;
    }

    private static Annotation declaredAnnotation(Class<?> type, String className) {
        for (Annotation annotation : type.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(className)) {
                return annotation;
            }
        }
        return null;
    }

    private static Annotation declaredAnnotation(Method method, String className) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(className)) {
                return annotation;
            }
        }
        return null;
    }

    private static Annotation declaredAnnotation(Field field, String className) {
        for (Annotation annotation : field.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(className)) {
                return annotation;
            }
        }
        return null;
    }

    private static Annotation declaredAnnotation(Parameter parameter, String className) {
        for (Annotation annotation : parameter.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(className)) {
                return annotation;
            }
        }
        return null;
    }

    private static Object member(Field field, String annotationClass, String member) {
        Annotation annotation = declaredAnnotation(field, annotationClass);
        return annotation == null ? null : invoke(annotation, member);
    }

    private static String stringMember(Field field, String annotationClass, String member) {
        Object value = member(field, annotationClass, member);
        return value == null || value.toString().isEmpty() ? null : value.toString();
    }

    private static Integer intMember(Field field, String annotationClass, String member) {
        Object value = member(field, annotationClass, member);
        return value instanceof Integer number ? number : null;
    }

    private static Long longMember(Field field, String annotationClass, String member) {
        Object value = member(field, annotationClass, member);
        return value instanceof Long number ? number : null;
    }

    private static Boolean booleanMember(Field field, String annotationClass, String member) {
        Object value = member(field, annotationClass, member);
        return value instanceof Boolean flag ? flag : null;
    }

    private static Object invoke(Annotation annotation, String member) {
        try {
            return annotation.annotationType().getMethod(member).invoke(annotation);
        } catch (ReflectiveOperationException e) {
            return null; // the annotation version at hand does not have this member
        }
    }
}
