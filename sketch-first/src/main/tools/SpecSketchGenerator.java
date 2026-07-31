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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SpecSketch -> OpenAPI 3 (YAML) generator. Single file, dependency-free, JDK 17+.
 *
 * Generates the request/response part of an OpenAPI document (one operation)
 * from a simple indentation-based format:
 *
 *   <name> (<occurrence>) : <type>
 *
 * Occurrence: a number ("1"), or a range ("0 - 1", "1 - *", "2 - 5").
 * Nesting:    4 spaces or 1 tab per level, unlimited depth. A line with fewer
 *             spaces implicitly ends all deeper structures and lands on the
 *             level matching its indentation.
 * Types:      a type with indented properties becomes an object schema; its
 *             details may only be defined at its FIRST occurrence - every other
 *             usage references it by name, resulting in a single schema plus
 *             $refs in the output (recursive types are possible). A type
 *             without properties is either a built-in or a reference to a type
 *             defined elsewhere in the file. Every type definition needs at
 *             least one property: a property-less object schema means 'any
 *             object' in OpenAPI, so DTO generators drop the model. Property
 *             names must be unique within a type. Custom type names must differ
 *             by more than case and underscores ('MyType' vs. 'my_type' would
 *             collapse into one generated class), the check reports the line.
 *             Built-ins (case-insensitive, so both 'string' and 'String' work):
 *             - OpenAPI-style: string, number, integer, boolean, date, datetime, uuid
 *             - Java primitives/wrappers: int, long, short, byte, char, float,
 *               double, boolean, String, BigDecimal, BigInteger
 *             - java.time (Java 8+): LocalDate, LocalDateTime, OffsetDateTime,
 *               ZonedDateTime, Instant, LocalTime, OffsetTime
 * Inheritance: inside a type definition a line 'extended by <SubType>' starts
 *             a subtype block; its nested lines are the ADDITIONAL properties
 *             of the subtype, emitted as an allOf composition. Re-declaring a
 *             property of the base chain is an error - the block only adds.
 *             Subtype blocks may be nested for deeper hierarchies. Marking one
 *             base property with the reserved type 'discriminator' makes the
 *             hierarchy polymorphic (discriminator + mapping in the YAML,
 *             Jackson @JsonTypeInfo/@JsonSubTypes in the generated Java) AND is
 *             what makes the DTO generator emit real Java inheritance
 *             ('class Car extends Vehicle'). Without a discriminator the allOf
 *             composition is valid OpenAPI, but openapi-generator flattens it:
 *             the subtype becomes a standalone class that repeats the base
 *             properties instead of extending the base class.
 *                 vehicles (0 - *) : Vehicle
 *                     vehicleType (1) : discriminator
 *                     maxSpeed (1) : int
 *                     extended by Car
 *                         doors (1) : int
 *                     extended by Bike
 *                         electric (1) : boolean
 * Enums:      shorthand via the keyword 'enum' followed by the values in
 *             brackets, e.g.: gender (1) : enum [M, F]
 *             The value type defaults to string and can be set explicitly with
 *             'enum:<built-in>', e.g.: gender (1) : enum:String [M, F]
 *             or, for numeric enums:   level (1) : enum:int [1, 2, 3]
 *             (emitted as an inline enum; the DTO generator turns it into an
 *             inner Java enum of the surrounding class)
 *             Reusable named enums carry a name between keyword and values:
 *                 status (1) : enum PetStatus [AVAILABLE, PENDING, SOLD]
 *                 rating (1) : enum:int Rating [1, 2, 3]
 *             The first occurrence defines the enum (like object types); every
 *             other usage references it plainly by name: status (1) : PetStatus
 *             (emitted as a named schema; the DTO generator turns it into a
 *             top-level Java enum shared by all referencing DTOs)
 * A top-level line named 'response' (mandatory) defines the response payload; a
 * top-level line named 'request' (optional) defines the request payload. With a
 * request body the operation is a POST (body required if the request occurrence
 * minimum is >= 1), without one it is a GET with an empty request.
 *
 * Headers: direct children of 'request'/'response' whose name starts with '@'
 * are HTTP headers instead of body properties, e.g.:
 *     @X-Client-Id (1) : uuid
 * Occurrence works as usual ((1) -> required, (0 - 1) -> optional, (0 - *) ->
 * repeatable). Request headers become 'in: header' parameters, response headers
 * land in the response's 'headers' section. A request that contains only
 * headers (no body properties) has no request body and stays a GET.
 * Header names must be unique per part (HTTP header names are case-insensitive).
 * Additional top-level lines may define further reusable types.
 *
 * Parameters: direct children of 'request' may also carry the other parameter
 * sigils. Saying where a parameter comes from is OPTIONAL, so a first sketch can
 * state that an operation takes a 'petId' before the URL shape is settled:
 *     $petId (1) : long                // a parameter - location not decided yet
 *     $path:petId (1) : long           // decided later: a path parameter
 *     $query:status (0 - 1) : PetStatus
 *     $cookie:session (0 - 1) : uuid
 *     $header:X-Client-Id (1) : uuid   // the long form of '@X-Client-Id'
 * '?name' is shorthand for '$query:name' and '{name}' for '$path:name', so the
 * two common cases read like the URL they end up in:
 *     ?status (0 - 1) : PetStatus
 *     {petId} (1) : long
 * OpenAPI has no 'in' value for 'not decided', so an undecided '$name' is
 * emitted as a query parameter and reported - the document stays usable and the
 * guess never stays silent. Occurrence, types and attributes work as on headers;
 * parameters are never body properties, so a request of parameters alone has no
 * body and stays a GET. Names must be unique per location (a path and a query
 * parameter may share one). 'in: path' is the single location OpenAPI
 * constrains: it must be required ((1)), cannot repeat, cannot carry an object
 * type, and its name has to appear in the path template - which is derived from
 * the file name, so path parameters are appended to it in declaration order
 * ('getPet' + '{petId}' -> '/getPet/{petId}'). Where the segments really sit in
 * the URL is not expressible yet, so the derived path is reported.
 *
 * Imports: a top-level line 'import <TypeName>' declares a type whose details
 * live in an existing shared/common yaml file - no local schema is generated,
 * every usage becomes an external $ref with a type-specific placeholder meant
 * to be filled in:
 *     import Address
 *     ->  $ref: 'TODO-IMPORT/Address.yaml#/components/schemas/Address'
 * A pathless import is reported with its line, because no OpenAPI tool can
 * resolve the placeholder. Filling the path into the RESULT file works too:
 * the next run adopts it back into the sketch's 'import' line, so the sketch
 * stays the single source of truth. If the file is already known,
 * 'import Address from "common/types.yaml"' emits the real ref right away.
 * Paths are relative to the SKETCH; when the yaml is written to a different
 * directory they are rebased, so a sketch never has to know where it lands.
 * Defining properties for an imported type is an error; imports join the type
 * registry (define-once, name rules).
 *
 * Attributes: a property line may end with an optional {key: value, ...} block
 * holding validation attributes, validated against the property's type:
 *     name (1) : string {minLength: 1, maxLength: 100}
 *     email (0 - 1) : string {pattern: "^.+@.+$"}
 *     age (0 - 1) : int {min: 0, max: 150}
 * Strings support minLength, maxLength and pattern; numeric types support min
 * and max (aliases: minimum, maximum). minLength/maxLength/pattern are limited
 * to the plain 'string' type: date, datetime, uuid and the java.time built-ins
 * become non-String Java types, where the generated @Size/@Pattern would fail
 * at validation time. Values may be double-quoted (required when they contain
 * commas or braces, e.g. patterns with quantifiers). On arrays the attributes
 * apply to the items - the array bounds already come from the occurrence.
 *
 * Comments: every line may end with a comment introduced by '//', '#' or
 * '/* ... *&#47;' (the block form may also sit mid-line and must be closed on
 * the same line). Comment markers inside a double-quoted value are part of the
 * value, so patterns and import paths may contain '#', '//' and '/*'. Lines
 * containing only a comment are ignored at any indentation and never affect the
 * nesting. Blank lines are ignored as well.
 *
 * Usage: java SpecSketchGenerator.java <input.sketch> [<output.yaml> | -]
 *        With only the input path the YAML is saved next to the input file as
 *        <input-basename>.yaml - sketch and result side by side, which is the
 *        normal layout: both are version-controlled, neither belongs to a build
 *        output folder. A second argument sets an explicit output path (that is
 *        what the Maven build does, writing into target/); '-' prints to stdout.
 *        An existing result file is overwritten, but if its content differed it
 *        is reported - in a repository the overwrite shows up as a change to
 *        review, and it used to happen without any trace.
 *        (No build step needed thanks to the JDK source launcher; a plain
 *        'javac SpecSketchGenerator.java' works as well since there are no
 *        dependencies outside the JDK.)
 */
public final class SpecSketchGenerator {

    private static final int UNBOUNDED = -1;
    private static final int INDENT_WIDTH = 4;

    private static final Pattern EXTENDED_BY_LINE = Pattern.compile("extended\\s+by\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern IMPORT_LINE = Pattern.compile(
            "import\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+from\\s+\"([^\"]+)\")?");

    private static final String NAME = "[A-Za-z_][A-Za-z0-9_-]*";

    /**
     * A line's name, optionally carrying a parameter sigil: '@' header, '?' query, '{...}' path,
     * '$' a parameter whose location is undecided, '$&lt;location&gt;:' an explicit one. No capturing
     * group, so the group numbers of {@link #LINE} stay as they read.
     */
    private static final String SIGIL_NAME = "(?:[@?]|\\$(?:[A-Za-z]+:)?)?" + NAME + "|\\{" + NAME + "\\}";

    private static final Pattern LINE = Pattern.compile(
            "(" + SIGIL_NAME + ")\\s*\\(\\s*(\\d+)\\s*(?:-\\s*(\\d+|\\*)\\s*)?\\)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*))?(?:\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*(?:\\[([^\\]]*)\\])?\\s*(?:\\{(.*)\\})?");

    private static final Map<String, Map<String, Object>> BUILT_INS = builtInTypes();

    /**
     * Built-in type names (all lowercase - lookup is case-insensitive, so the Java
     * wrapper spellings like Integer, Long, String, BigDecimal work as well).
     */
    private static Map<String, Map<String, Object>> builtInTypes() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        // OpenAPI-style names
        m.put("string", prim("string", null));
        m.put("number", prim("number", null));
        m.put("integer", prim("integer", "int32"));
        m.put("boolean", prim("boolean", null));
        m.put("bool", prim("boolean", null));
        m.put("date", prim("string", "date"));
        m.put("datetime", prim("string", "date-time"));
        m.put("uuid", prim("string", "uuid"));
        // Java primitives (and, via case-insensitivity, their wrappers)
        m.put("int", prim("integer", "int32"));
        m.put("short", prim("integer", "int32"));
        m.put("byte", prim("integer", "int32"));
        m.put("long", prim("integer", "int64"));
        m.put("float", prim("number", "float"));
        m.put("double", prim("number", "double"));
        Map<String, Object> singleChar = prim("string", null);
        singleChar.put("minLength", 1);
        singleChar.put("maxLength", 1);
        m.put("char", singleChar);
        m.put("character", singleChar);
        m.put("bigdecimal", prim("number", null));
        m.put("biginteger", prim("integer", null));
        // java.time (Java 8+) date classes
        m.put("localdate", prim("string", "date"));
        m.put("localdatetime", prim("string", "date-time"));
        m.put("offsetdatetime", prim("string", "date-time"));
        m.put("zoneddatetime", prim("string", "date-time"));
        m.put("instant", prim("string", "date-time"));
        m.put("localtime", prim("string", "time"));
        m.put("offsettime", prim("string", "time"));
        return m;
    }

    /**
     * Where a parameter line lands in the document. UNSPECIFIED is the draft form '$name': the
     * sketch says 'this is a parameter' without deciding where it comes from. OpenAPI has no 'in'
     * value for that, so it is resolved to QUERY (and reported) before anything is emitted - which
     * is why {@link #wireName()} is never called on it.
     */
    private enum ParamIn {
        PATH, QUERY, HEADER, COOKIE, UNSPECIFIED;

        /** The OpenAPI 'in' value. */
        String wireName() {
            return name().toLowerCase();
        }

        /** The location named by a '$&lt;location&gt;:' prefix, or null if the word is not one. */
        static ParamIn of(String word) {
            for (ParamIn location : List.of(PATH, QUERY, HEADER, COOKIE)) {
                if (location.wireName().equals(word)) {
                    return location;
                }
            }
            return null;
        }
    }

    private static final class Node {
        final int lineNo;
        final String name;
        final ParamIn location;        // null -> a body property; otherwise the parameter sigil
        final boolean isSubtypeMarker; // an 'extended by <SubType>' line; type holds the subtype name
        final boolean isImport;        // an 'import <TypeName>' line; type holds the imported name
        final String importPath;       // the optional 'from "..."' path; null -> placeholder ref
        final int min;
        final int max;
        final String type;
        final String enumValueType;   // null unless the type is the 'enum' shorthand
        final String enumName;        // null unless the enum shorthand carries a name
        final List<Object> enumValues; // null unless the type is the 'enum' shorthand
        final Map<String, Object> attributes; // null unless the line carries a {...} block
        final List<Node> children = new ArrayList<>();

        Node(int lineNo, String name, ParamIn location, boolean isSubtypeMarker,
             boolean isImport, String importPath, int min, int max, String type,
             String enumValueType, String enumName, List<Object> enumValues, Map<String, Object> attributes) {
            this.lineNo = lineNo;
            this.name = name;
            this.location = location;
            this.isSubtypeMarker = isSubtypeMarker;
            this.isImport = isImport;
            this.importPath = importPath;
            this.min = min;
            this.max = max;
            this.type = type;
            this.enumValueType = enumValueType;
            this.enumName = enumName;
            this.enumValues = enumValues;
            this.attributes = attributes;
        }

        boolean isArray() {
            return max == UNBOUNDED || max > 1;
        }

        boolean isHeader() {
            return location == ParamIn.HEADER;
        }

        /** Any parameter line: a header or one of the other locations - never a body property. */
        boolean isParameter() {
            return location != null;
        }

        /** The name as it reads in a sketch, so a message quotes the line it is about. */
        String sigilName() {
            if (location == null) {
                return name;
            }
            return switch (location) {
                case HEADER -> "@" + name;
                case PATH -> "{" + name + "}";
                case QUERY -> "?" + name;
                case COOKIE -> "$cookie:" + name;
                case UNSPECIFIED -> "$" + name;
            };
        }
    }

    static final class SpecException extends RuntimeException {
        SpecException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: java SpecSketchGenerator.java <input.sketch> [<output.yaml> | -]");
            System.err.println("       (without an output path the YAML is saved next to the input file)");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        try {
            if (!Files.isReadable(input)) {
                throw new SpecException(Files.exists(input) ? "input file is not readable" : "input file not found");
            }
            if (args.length == 2 && args[1].equals("-")) {
                List<String> messages = new ArrayList<>();
                System.out.print(generateYaml(Files.readAllLines(input), baseNameOf(input), messages));
                for (String message : messages) {
                    System.err.println("SpecSketchGenerator: " + message);
                }
                return;
            }
            // default: save the YAML in the same path as the input file
            Path output = args.length == 2 ? Path.of(args[1]) : input.resolveSibling(baseNameOf(input) + ".yaml");
            List<String> messages = new ArrayList<>();
            boolean written = translate(input, output, messages);
            for (String message : messages) {
                System.err.println("SpecSketchGenerator: " + message);
            }
            if (written) {
                System.out.println("SpecSketchGenerator: " + input + " -> " + output);
            }
        } catch (SpecException e) {
            System.err.println("SpecSketchGenerator: " + input + ": " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("SpecSketchGenerator: " + input + ": " + e);
            System.exit(1);
        }
    }

    static String baseNameOf(Path input) {
        return input.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    }

    /**
     * File-level translation. In real use the sketch and its yaml sit in the SAME directory,
     * both outside any build output folder - the yaml is a durable artifact, not a throwaway.
     * Hence two things happen here that a pure text translation would not do:
     * <ul>
     *   <li>if a TODO-IMPORT placeholder was filled in with a real path in the result file, that
     *       path is adopted back into the sketch's 'import' line, so the sketch stays the single
     *       source of truth and a fresh clone regenerates the same document;</li>
     *   <li>overwriting a result file that differs is allowed (the file is version-controlled, so
     *       it simply becomes a change to review), but it is reported instead of happening
     *       silently - previously any manual edit vanished without a trace.</li>
     * </ul>
     * Import paths are written relative to the sketch; if the output goes somewhere else they are
     * rebased, so a sketch never has to know where its yaml lands.
     *
     * @return whether the result file was written (false if it was already up to date)
     */
    static boolean translate(Path input, Path output, List<String> messages) throws IOException {
        if (output.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())) {
            throw new SpecException("output path would overwrite the input file: " + output);
        }
        Path sketchDir = directoryOf(input);
        Path outputDir = directoryOf(output);
        List<String> lines = Files.readAllLines(input);
        String existing = Files.exists(output) ? Files.readString(output) : null;

        // the translation may run twice (adopting import paths re-reads the sketch); its messages
        // describe the sketch as it stands, so only those of the run that produced the yaml count
        List<String> translated = new ArrayList<>();
        String yaml = generateYaml(rebaseImportPaths(lines, sketchDir, outputDir), baseNameOf(input), translated);
        if (existing != null) {
            Map<String, String> resolved = resolvedImportPaths(existing, yaml);
            if (!resolved.isEmpty()) {
                List<String> updated = applyImportPaths(lines, resolved, outputDir, sketchDir, messages);
                Files.write(input, updated);
                lines = updated;
                translated.clear();
                yaml = generateYaml(rebaseImportPaths(lines, sketchDir, outputDir), baseNameOf(input), translated);
            }
        }
        messages.addAll(translated);
        warnAboutPathlessImports(lines, messages);
        if (yaml.equals(existing)) {
            messages.add(output + " is already up to date");
            return false;
        }
        if (existing != null) {
            messages.add("warning: " + output + " differed from the sketch and was overwritten"
                    + " - review the change before committing it");
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, yaml);
        return true;
    }

    /**
     * A pathless import emits a TODO-IMPORT placeholder, which no OpenAPI tool can resolve - the
     * build fails deep inside the generator with a path that says nothing about the sketch.
     */
    static void warnAboutPathlessImports(List<String> lines, List<String> messages) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = IMPORT_LINE.matcher(stripComments(lines.get(i), i + 1).trim());
            if (m.matches() && m.group(2) == null) {
                messages.add("warning: line " + (i + 1) + ": import '" + m.group(1) + "' has no path yet"
                        + " - the result file gets a TODO-IMPORT placeholder that OpenAPI tooling cannot"
                        + " resolve; add: import " + m.group(1) + " from \"<file>\"");
            }
        }
    }

    private static Path directoryOf(Path file) {
        Path parent = file.toAbsolutePath().normalize().getParent();
        return parent != null ? parent : Path.of("").toAbsolutePath();
    }

    // ------------------------------------------------------------ import paths

    /** A ref emitted for an 'import' without a path - the part meant to be filled in. */
    private static final Pattern PLACEHOLDER_REF = Pattern.compile(
            "'TODO-IMPORT/([A-Za-z_][A-Za-z0-9_]*)\\.yaml#/components/schemas/\\1'");

    /**
     * Import paths are written relative to the SKETCH, but the emitted $ref is resolved relative
     * to the RESULT FILE. Both live in the same directory in normal use, so nothing happens; only
     * an output redirected elsewhere (as the Maven build does) needs the rebase.
     */
    static List<String> rebaseImportPaths(List<String> lines, Path sketchDir, Path outputDir) {
        if (sketchDir.equals(outputDir)) {
            return lines;
        }
        List<String> rebased = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String path = importPathOf(raw, i + 1);
            rebased.add(path == null ? raw : replaceQuotedValue(raw, rebase(path, sketchDir, outputDir)));
        }
        return rebased;
    }

    /** Types whose placeholder ref carries a real path in the existing result file. */
    static Map<String, String> resolvedImportPaths(String existingYaml, String freshYaml) {
        Map<String, String> resolved = new LinkedHashMap<>();
        Matcher placeholder = PLACEHOLDER_REF.matcher(freshYaml);
        while (placeholder.find()) {
            String type = placeholder.group(1);
            Matcher filledIn = Pattern.compile("'([^']+)#/components/schemas/" + type + "'").matcher(existingYaml);
            if (filledIn.find() && !filledIn.group(1).equals("TODO-IMPORT/" + type + ".yaml")) {
                resolved.putIfAbsent(type, filledIn.group(1));
            }
        }
        return resolved;
    }

    /** Writes the adopted paths into the pathless 'import' lines, keeping trailing comments. */
    static List<String> applyImportPaths(List<String> lines, Map<String, String> pathsRelativeToOutput,
                                         Path outputDir, Path sketchDir, List<String> messages) {
        List<String> updated = new ArrayList<>(lines);
        for (Map.Entry<String, String> resolved : pathsRelativeToOutput.entrySet()) {
            String type = resolved.getKey();
            String path = rebase(resolved.getValue(), outputDir, sketchDir);
            for (int i = 0; i < updated.size(); i++) {
                String raw = updated.get(i);
                Matcher m = IMPORT_LINE.matcher(stripComments(raw, i + 1).trim());
                if (!m.matches() || !m.group(1).equals(type) || m.group(2) != null) {
                    continue;
                }
                updated.set(i, insertFromClause(raw, type, path));
                messages.add("line " + (i + 1) + ": adopted the path filled in for 'import " + type
                        + "' into the sketch: from \"" + path + "\"");
                break;
            }
        }
        return updated;
    }

    private static String rebase(String path, Path fromDir, Path toDir) {
        if (fromDir.equals(toDir) || isAbsoluteOrUrl(path)) {
            return path;
        }
        return toDir.relativize(fromDir.resolve(path).normalize()).toString().replace('\\', '/');
    }

    private static boolean isAbsoluteOrUrl(String path) {
        return path.startsWith("/")
                || path.matches("(?i)[a-z][a-z0-9+.-]*://.*") // http://, https://, file://
                || path.matches("(?i)[a-z]:[/\\\\].*");       // windows drive letter
    }

    /** The 'from "..."' path of an import line, or null if this is not a path-carrying import. */
    private static String importPathOf(String raw, int lineNo) {
        Matcher m = IMPORT_LINE.matcher(stripComments(raw, lineNo).trim());
        return m.matches() ? m.group(2) : null;
    }

    private static String replaceQuotedValue(String raw, String value) {
        int start = raw.indexOf('"');
        int end = raw.indexOf('"', start + 1);
        return raw.substring(0, start + 1) + value + raw.substring(end);
    }

    private static String insertFromClause(String raw, String type, String path) {
        int afterKeyword = raw.indexOf("import") + "import".length();
        int afterType = raw.indexOf(type, afterKeyword) + type.length();
        return raw.substring(0, afterType) + " from \"" + path + "\"" + raw.substring(afterType);
    }

    /**
     * Entry point for tests and for {@link #main}: SpecSketch lines in, OpenAPI YAML out.
     * {@code messages} collects what the translation decided on the sketch's behalf - a parameter
     * whose location was left open, and the path template derived for path parameters.
     */
    static String generateYaml(List<String> lines, String baseName, List<String> messages) {
        List<Node> roots = parse(lines);
        if (roots.isEmpty()) {
            throw new SpecException("no definitions found");
        }
        return generate(roots, baseName, messages);
    }

    // ---------------------------------------------------------------- parsing

    private static List<Node> parse(List<String> lines) {
        List<Node> roots = new ArrayList<>();
        List<Node> stack = new ArrayList<>(); // stack.get(i) = currently open node at depth i
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (i == 0 && !raw.isEmpty() && raw.charAt(0) == '﻿') {
                raw = raw.substring(1); // a UTF-8 BOM would make the first line unparseable
            }
            if (raw.isBlank()) {
                continue;
            }
            int lineNo = i + 1;
            int width = 0;
            int pos = 0;
            while (pos < raw.length() && (raw.charAt(pos) == ' ' || raw.charAt(pos) == '\t')) {
                width += raw.charAt(pos) == '\t' ? INDENT_WIDTH : 1;
                pos++;
            }
            // strip comments before validating indentation, so comment-only lines
            // are ignored at any indentation and never affect the nesting
            String body = stripComments(raw.substring(pos), lineNo).trim();
            if (body.isEmpty()) {
                continue;
            }
            if (width % INDENT_WIDTH != 0) {
                throw new SpecException("line " + lineNo + ": indentation must be a multiple of "
                        + INDENT_WIDTH + " spaces (or tabs), found " + width);
            }
            int level = width / INDENT_WIDTH;
            if (level > stack.size()) {
                throw new SpecException("line " + lineNo + ": indented to level " + level
                        + " but the previous line only allows nesting up to level " + stack.size());
            }
            Node node = parseBody(body, lineNo);
            // implicit end of deeper structures: dedent lands on the level matching the indentation
            while (stack.size() > level) {
                stack.remove(stack.size() - 1);
            }
            if (level == 0) {
                if (node.isSubtypeMarker) {
                    throw new SpecException("line " + lineNo
                            + ": 'extended by' is only allowed inside a type definition");
                }
                roots.add(node);
            } else if (node.isImport) {
                throw new SpecException("line " + lineNo + ": 'import' is only allowed at the top level");
            } else {
                stack.get(level - 1).children.add(node);
            }
            stack.add(node);
        }
        return roots;
    }

    /**
     * Removes '//' and '#' comments (rest of line) and single-line '/* ... *&#47;' block comments.
     * Comment markers inside a double-quoted value are kept, so patterns and import paths may
     * contain '#', '//' and '/*' (e.g. {pattern: "^#[0-9a-f]{6}$"} or a https:// import path).
     */
    private static String stripComments(String text, int lineNo) {
        StringBuilder result = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                result.append(c);
                i++;
                continue;
            }
            if (!inQuotes) {
                boolean hasNext = i + 1 < text.length();
                if (c == '#' || (c == '/' && hasNext && text.charAt(i + 1) == '/')) {
                    break;
                }
                if (c == '/' && hasNext && text.charAt(i + 1) == '*') {
                    int end = text.indexOf("*/", i + 2);
                    if (end < 0) {
                        throw new SpecException("line " + lineNo
                                + ": block comment '/*' is not closed on the same line");
                    }
                    result.append(' ');
                    i = end + 2;
                    continue;
                }
            }
            result.append(c);
            i++;
        }
        if (inQuotes) {
            throw new SpecException("line " + lineNo + ": unterminated double quote");
        }
        return result.toString();
    }

    private static Node parseBody(String body, int lineNo) {
        Matcher importMatcher = IMPORT_LINE.matcher(body);
        if (importMatcher.matches()) {
            String importedType = importMatcher.group(1);
            return new Node(lineNo, importedType, null, false, true, importMatcher.group(2),
                    1, 1, importedType, null, null, null, null);
        }
        Matcher extendedByMatcher = EXTENDED_BY_LINE.matcher(body);
        if (extendedByMatcher.matches()) {
            String subType = extendedByMatcher.group(1);
            return new Node(lineNo, subType, null, true, false, null, 1, 1, subType, null, null, null, null);
        }
        Matcher m = LINE.matcher(body);
        if (!m.matches()) {
            throw new SpecException("line " + lineNo + ": expected '<name> (<occurrence>) : <type>', got: " + body);
        }
        int min = parseOccurrence(m.group(2), lineNo);
        String maxToken = m.group(3);
        int max = maxToken == null ? min : maxToken.equals("*") ? UNBOUNDED : parseOccurrence(maxToken, lineNo);
        if (max != UNBOUNDED && max < min) {
            throw new SpecException("line " + lineNo + ": maximum occurrence " + max + " is smaller than minimum " + min);
        }
        if (max == 0) {
            throw new SpecException("line " + lineNo + ": maximum occurrence must be at least 1");
        }
        String type = m.group(4);
        String valueType = m.group(5);
        String enumName = m.group(6);
        if (enumName != null && !type.equalsIgnoreCase("enum")) {
            throw new SpecException("line " + lineNo + ": unexpected '" + enumName
                    + "' - only the keyword 'enum' may declare a type name");
        }
        List<Object> enumValues = parseEnumValues(type, valueType, m.group(7), lineNo);
        String enumValueType = enumValues == null ? null : valueType == null ? "string" : valueType;
        Map<String, Object> attributes = parseAttributes(type, enumValues != null, m.group(8), lineNo);
        String rawName = m.group(1);
        return new Node(lineNo, nameOf(rawName), locationOf(rawName, lineNo), false, false, null,
                min, max, type, enumValueType, enumName, enumValues, attributes);
    }

    /** The location a name's sigil states, or null when the line is a plain body property. */
    private static ParamIn locationOf(String rawName, int lineNo) {
        return switch (rawName.charAt(0)) {
            case '@' -> ParamIn.HEADER;
            case '?' -> ParamIn.QUERY;
            case '{' -> ParamIn.PATH;
            case '$' -> explicitLocationOf(rawName, lineNo);
            default -> null;
        };
    }

    /** '$name' leaves the location open; '$&lt;location&gt;:name' names one, and has to name a real one. */
    private static ParamIn explicitLocationOf(String rawName, int lineNo) {
        int colon = rawName.indexOf(':');
        if (colon < 0) {
            return ParamIn.UNSPECIFIED;
        }
        String word = rawName.substring(1, colon);
        ParamIn location = ParamIn.of(word);
        if (location == null) {
            throw new SpecException("line " + lineNo + ": unknown parameter location '" + word
                    + "' (supported: path, query, header, cookie; plain '$" + rawName.substring(colon + 1)
                    + "' leaves the location undecided)");
        }
        return location;
    }

    /** The name without its sigil: '@X-Id', '?q', '{id}' and '$path:id' all carry a plain name. */
    private static String nameOf(String rawName) {
        if (rawName.charAt(0) == '{') {
            return rawName.substring(1, rawName.length() - 1);
        }
        if ("@?$".indexOf(rawName.charAt(0)) < 0) {
            return rawName;
        }
        int colon = rawName.indexOf(':');
        return rawName.substring(colon < 0 ? 1 : colon + 1);
    }

    /** The regex only guarantees digits, so the single failure mode is an int overflow. */
    private static int parseOccurrence(String token, int lineNo) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new SpecException("line " + lineNo + ": occurrence '" + token + "' is too large (maximum "
                    + Integer.MAX_VALUE + ")");
        }
    }

    // ------------------------------------------------------------ attributes

    /** Parses and validates the optional {key: value, ...} attribute block against the type. */
    private static Map<String, Object> parseAttributes(String type, boolean isEnum, String body, int lineNo) {
        if (body == null) {
            return null;
        }
        if (isEnum) {
            throw new SpecException("line " + lineNo + ": attributes are not supported on enums");
        }
        if (type.equalsIgnoreCase("discriminator")) {
            throw new SpecException("line " + lineNo + ": attributes are not supported on the discriminator");
        }
        if (type.equalsIgnoreCase("char") || type.equalsIgnoreCase("character")) {
            throw new SpecException("line " + lineNo + ": attributes are not supported on char (fixed length 1)");
        }
        Map<String, Object> builtIn = BUILT_INS.get(type.toLowerCase());
        if (builtIn == null) {
            throw new SpecException("line " + lineNo
                    + ": attributes are only supported on built-in types, not on type references");
        }
        String family = (String) builtIn.get("type"); // string | integer | number | boolean
        // only the plain 'string' built-in becomes a Java String: date/datetime/uuid/... carry a
        // format and map to java.time/UUID values, where @Size/@Pattern cannot be validated
        boolean plainString = family.equals("string") && !builtIn.containsKey("format");
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String part : splitAttributes(body)) {
            if (part.isBlank()) {
                throw new SpecException("line " + lineNo + ": empty attribute in {" + body + "}");
            }
            int colon = part.indexOf(':');
            if (colon < 0) {
                throw new SpecException("line " + lineNo + ": expected 'key: value' attribute, got: " + part.trim());
            }
            String key = part.substring(0, colon).trim();
            String value = unquote(part.substring(colon + 1).trim());
            String canonical = switch (key.toLowerCase()) {
                case "minlength", "maxlength" -> {
                    requirePlainString(key, type, family, plainString, lineNo, " (use min/max for numeric ranges)");
                    yield key.toLowerCase().equals("minlength") ? "minLength" : "maxLength";
                }
                case "pattern" -> {
                    requirePlainString(key, type, family, plainString, lineNo, "");
                    yield "pattern";
                }
                case "min", "minimum", "max", "maximum" -> {
                    if (!family.equals("integer") && !family.equals("number")) {
                        throw new SpecException("line " + lineNo + ": '" + key + "' is only allowed on numeric types"
                                + " (use minLength/maxLength for strings)");
                    }
                    yield key.toLowerCase().startsWith("min") ? "minimum" : "maximum";
                }
                default -> throw new SpecException("line " + lineNo + ": unknown attribute '" + key
                        + "' (supported: minLength, maxLength, pattern, min/minimum, max/maximum)");
            };
            if (attributes.containsKey(canonical)) {
                throw new SpecException("line " + lineNo + ": duplicate attribute '" + canonical + "'");
            }
            attributes.put(canonical, attributeValue(canonical, value, lineNo));
        }
        checkAttributeBounds(attributes, "minLength", "maxLength", lineNo);
        checkAttributeBounds(attributes, "minimum", "maximum", lineNo);
        return attributes;
    }

    /**
     * minLength/maxLength/pattern are only meaningful where the type ends up as a Java String -
     * on a date or uuid the generated @Size/@Pattern would fail with an UnexpectedTypeException
     * the first time the DTO is validated.
     */
    private static void requirePlainString(String key, String type, String family, boolean plainString,
                                           int lineNo, String numericHint) {
        if (plainString) {
            return;
        }
        if (!family.equals("string")) {
            throw new SpecException("line " + lineNo + ": '" + key + "' is only allowed on string types" + numericHint);
        }
        throw new SpecException("line " + lineNo + ": '" + key + "' is not supported on '" + type
                + "' - only the plain 'string' type maps to a Java String (formatted types like date,"
                + " datetime or uuid become java.time/UUID values, where the constraint cannot be validated)");
    }

    /** Splits on commas outside double quotes, so quoted values may contain commas. */
    private static List<String> splitAttributes(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (char c : body.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static Object attributeValue(String canonical, String value, int lineNo) {
        try {
            return switch (canonical) {
                case "minLength", "maxLength" -> {
                    int length = Integer.parseInt(value);
                    if (length < 0) {
                        throw new SpecException("line " + lineNo + ": '" + canonical + "' must not be negative");
                    }
                    yield length;
                }
                case "minimum", "maximum" -> new java.math.BigDecimal(value);
                default -> value; // pattern
            };
        } catch (NumberFormatException e) {
            throw new SpecException("line " + lineNo + ": invalid numeric value '" + value
                    + "' for attribute '" + canonical + "'");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void checkAttributeBounds(Map<String, Object> attributes, String minKey, String maxKey, int lineNo) {
        Object min = attributes.get(minKey);
        Object max = attributes.get(maxKey);
        if (min != null && max != null && ((Comparable) min).compareTo(max) > 0) {
            throw new SpecException("line " + lineNo + ": '" + minKey + "' (" + min
                    + ") is greater than '" + maxKey + "' (" + max + ")");
        }
    }

    private static List<Object> parseEnumValues(String type, String valueType, String bracketBody, int lineNo) {
        boolean isEnum = type.equalsIgnoreCase("enum");
        if (valueType != null && !isEnum) {
            throw new SpecException("line " + lineNo + ": only the keyword 'enum' supports a value type (':" + valueType + "')");
        }
        if (bracketBody == null) {
            if (isEnum) {
                throw new SpecException("line " + lineNo + ": 'enum' requires its values in brackets, e.g. enum [M, F]");
            }
            return null;
        }
        if (!isEnum) {
            throw new SpecException("line " + lineNo + ": only the keyword 'enum' may be followed by [values]");
        }
        // the value type defaults to string; any string or numeric built-in is allowed
        Map<String, Object> base = BUILT_INS.get((valueType == null ? "string" : valueType).toLowerCase());
        if (base == null) {
            throw new SpecException("line " + lineNo + ": enum value type '" + valueType + "' is not a built-in type");
        }
        String openApiType = (String) base.get("type");
        if (!openApiType.equals("string") && !openApiType.equals("integer") && !openApiType.equals("number")) {
            throw new SpecException("line " + lineNo + ": enum value type '" + valueType
                    + "' is not supported (use a string or numeric built-in type)");
        }
        List<Object> values = new ArrayList<>();
        for (String raw : bracketBody.split(",", -1)) {
            String text = raw.trim();
            Object value = switch (openApiType) {
                case "integer" -> parseEnumNumber(text, "-?\\d+", lineNo);
                case "number" -> parseEnumNumber(text, "-?\\d+(\\.\\d+)?", lineNo);
                default -> parseEnumString(text, lineNo);
            };
            if (containsEnumValue(values, value)) {
                throw new SpecException("line " + lineNo + ": duplicate enum value '" + text + "'");
            }
            values.add(value);
        }
        return values;
    }

    /**
     * Numeric values are compared by value, not by representation: BigDecimal.equals() is
     * scale-sensitive, so '1.0', '1.00' and '1' would all pass as distinct enum entries and
     * produce Java enum constants that share one value.
     */
    private static boolean containsEnumValue(List<Object> values, Object candidate) {
        for (Object existing : values) {
            if (existing instanceof java.math.BigDecimal a && candidate instanceof java.math.BigDecimal b) {
                if (a.compareTo(b) == 0) {
                    return true;
                }
            } else if (existing.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Object parseEnumNumber(String text, String pattern, int lineNo) {
        if (!text.matches(pattern)) {
            throw new SpecException("line " + lineNo + ": invalid enum value '" + text + "' for a numeric enum");
        }
        return new java.math.BigDecimal(text);
    }

    private static String parseEnumString(String text, int lineNo) {
        if (!text.matches("[A-Za-z0-9_-]+")) {
            throw new SpecException("line " + lineNo + ": invalid enum value '" + text + "'");
        }
        return text;
    }

    // ------------------------------------------------------- schema generation

    /** The document-wide type registries filled by the definition pass. */
    private static final class Registry {
        final Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        final Map<String, Integer> definedAt = new LinkedHashMap<>();
        final Map<String, Spelling> firstSpelling = new LinkedHashMap<>(); // normalized name -> first occurrence
        final Map<String, Node> references = new LinkedHashMap<>();
        final Map<String, Node> imports = new LinkedHashMap<>(); // imported name -> its 'import' line
    }

    private static String generate(List<Node> roots, String baseName, List<String> messages) {
        Registry registry = new Registry();
        collectImports(roots, registry);

        Node request = null;
        Node response = null;
        for (Node root : roots) {
            if (root.isImport) {
                continue;
            }
            if (root.name.equals("request")) {
                if (request != null) {
                    throw new SpecException("line " + root.lineNo + ": duplicate top-level 'request' definition");
                }
                request = root;
            } else if (root.name.equals("response")) {
                if (response != null) {
                    throw new SpecException("line " + root.lineNo + ": duplicate top-level 'response' definition");
                }
                response = root;
            }
        }
        if (response == null) {
            throw new SpecException("missing top-level 'response' definition");
        }
        validateParameterPlacement(roots, request, response);

        // the request is the only part that may consist of parameters alone (-> no body, no schema)
        for (Node root : roots) {
            if (!root.isImport) {
                defineTypes(root, registry, root == request);
            }
        }
        for (Map.Entry<String, Node> ref : registry.references.entrySet()) {
            if (!registry.schemas.containsKey(ref.getKey()) && !registry.imports.containsKey(ref.getKey())) {
                throw new SpecException("line " + ref.getValue().lineNo + ": type '" + ref.getKey()
                        + "' is used but never defined (define its properties at one of its usages, or import it)");
            }
        }

        Map<String, Map<String, Object>> schemas = registry.schemas;
        Map<String, Node> imports = registry.imports;
        List<Parameter> requestParameters = requestParameters(request, messages);
        // the path parameter rules need the schemas, so they are checked once those are known
        for (Parameter parameter : requestParameters) {
            if (parameter.in() == ParamIn.PATH) {
                validatePathParameter(parameter.node(), registry);
            }
        }
        List<Node> responseHeaders = headerChildren(response);
        // a request whose children are all parameters has no body and stays a GET
        boolean hasBody = request != null && (request.children.isEmpty() || !bodyChildren(request).isEmpty());

        Map<String, Object> okResponse = new LinkedHashMap<>();
        okResponse.put("description", "Generated from SpecSketch definition '" + response.name + "'");
        if (!responseHeaders.isEmpty()) {
            Map<String, Object> headers = new LinkedHashMap<>();
            for (Node header : responseHeaders) {
                Map<String, Object> h = new LinkedHashMap<>();
                if (header.min >= 1) {
                    h.put("required", true);
                }
                h.put("schema", occurrenceSchema(header, imports));
                headers.put(header.name, h);
            }
            okResponse.put("headers", headers);
        }
        okResponse.put("content", Map.of("application/json", Map.of("schema", occurrenceSchema(response, imports))));

        // with a request body the operation is a POST, without one a GET with an empty request
        String method = hasBody ? "post" : "get";
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", method + response.type);
        if (!requestParameters.isEmpty()) {
            List<Object> parameters = new ArrayList<>();
            for (Parameter parameter : requestParameters) {
                Node node = parameter.node();
                Map<String, Object> emitted = new LinkedHashMap<>();
                emitted.put("name", node.name);
                emitted.put("in", parameter.in().wireName());
                if (node.min >= 1) {
                    emitted.put("required", true);
                }
                emitted.put("schema", occurrenceSchema(node, imports));
                parameters.add(emitted);
            }
            operation.put("parameters", parameters);
        }
        if (hasBody) {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("required", request.min >= 1);
            requestBody.put("content", Map.of("application/json", Map.of("schema", occurrenceSchema(request, imports))));
            operation.put("requestBody", requestBody);
        }
        operation.put("responses", map("200", okResponse));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");
        doc.put("info", map("title", baseName + " API (generated from SpecSketch)", "version", "1.0.0"));
        doc.put("paths", map(operationPath(baseName, requestParameters, messages), map(method, operation)));
        doc.put("components", map("schemas", schemas));

        StringBuilder sb = new StringBuilder();
        emitMap(sb, doc, 0);
        return sb.toString();
    }

    /**
     * The path key of the operation: derived from the file name, plus one templated segment per
     * path parameter in declaration order - 'in: path' is only valid for a name the path template
     * really contains. Where those segments sit in the real URL cannot be said in a sketch yet, so
     * the derived path is reported rather than presented as the truth.
     */
    private static String operationPath(String baseName, List<Parameter> parameters, List<String> messages) {
        StringBuilder path = new StringBuilder("/").append(baseName);
        boolean templated = false;
        for (Parameter parameter : parameters) {
            if (parameter.in() == ParamIn.PATH) {
                path.append("/{").append(parameter.node().name).append('}');
                templated = true;
            }
        }
        if (templated) {
            messages.add("the operation path is derived from the file name plus one segment per path"
                    + " parameter, in declaration order: '" + path + "'");
        }
        return path.toString();
    }

    private static void collectImports(List<Node> roots, Registry registry) {
        for (Node root : roots) {
            if (!root.isImport) {
                continue;
            }
            if (!root.children.isEmpty()) {
                throw new SpecException("line " + root.lineNo + ": imported type '" + root.type
                        + "' must not define properties (its details live in the external file)");
            }
            if (BUILT_INS.containsKey(root.type.toLowerCase()) || root.type.equalsIgnoreCase("discriminator")) {
                throw new SpecException("line " + root.lineNo + ": '" + root.type
                        + "' is a built-in type and cannot be imported");
            }
            checkCaseCollision(root.type, root.lineNo, registry);
            if (registry.imports.putIfAbsent(root.type, root) != null) {
                throw new SpecException("line " + root.lineNo + ": type '" + root.type + "' is already imported");
            }
        }
    }

    /**
     * The '@' headers of a part. Duplicates are rejected: two same-named header parameters make the
     * document invalid, and two same-named response headers would silently collapse into one.
     */
    private static List<Node> headerChildren(Node node) {
        List<Node> headers = new ArrayList<>();
        Map<String, Node> seen = new LinkedHashMap<>();
        for (Node child : node.children) {
            if (!child.isHeader()) {
                continue;
            }
            Node first = seen.putIfAbsent(child.name.toLowerCase(), child);
            if (first != null) {
                throw duplicateParameter(child, first, ParamIn.HEADER);
            }
            headers.add(child);
        }
        return headers;
    }

    /** A request parameter with its location resolved - '$name' left it to the generator. */
    private record Parameter(Node node, ParamIn in) {
    }

    /**
     * The parameter lines of the request in declaration order, headers included: on the request
     * side every one of them is an entry of the operation's 'parameters' list, and keeping them in
     * one list keeps a mixed sketch emitting them in the order it declares them.
     *
     * A location left open resolves to 'query' here, which is the point where the guess is
     * reported. Names have to be unique per location - '{id}' and '?id' are two distinct
     * parameters for OpenAPI - and header names are compared case-insensitively, as HTTP does.
     */
    private static List<Parameter> requestParameters(Node request, List<String> messages) {
        List<Parameter> parameters = new ArrayList<>();
        if (request == null) {
            return parameters;
        }
        Map<String, Node> seen = new LinkedHashMap<>();
        for (Node child : request.children) {
            if (!child.isParameter()) {
                continue;
            }
            ParamIn in = child.location;
            if (in == ParamIn.UNSPECIFIED) {
                in = ParamIn.QUERY;
                messages.add("warning: line " + child.lineNo + ": parameter '$" + child.name
                        + "' does not say where it comes from and was emitted as 'in: query'"
                        + " - write '?" + child.name + "' to keep it, or '{" + child.name
                        + "}' for a path parameter");
            }
            String key = in.wireName() + " "
                    + (in == ParamIn.HEADER ? child.name.toLowerCase() : child.name);
            Node first = seen.putIfAbsent(key, child);
            if (first != null) {
                throw duplicateParameter(child, first, in);
            }
            parameters.add(new Parameter(child, in));
        }
        return parameters;
    }

    private static SpecException duplicateParameter(Node duplicate, Node first, ParamIn in) {
        if (in == ParamIn.HEADER) {
            return new SpecException("line " + duplicate.lineNo + ": duplicate header '@" + duplicate.name
                    + "' (already declared at line " + first.lineNo
                    + "; HTTP header names are case-insensitive)");
        }
        return new SpecException("line " + duplicate.lineNo + ": duplicate " + in.wireName()
                + " parameter '" + duplicate.sigilName() + "' (already declared at line "
                + first.lineNo + ")");
    }

    /**
     * 'in: path' is the one location OpenAPI constrains: the value sits in the URL itself, so it is
     * always required, appears exactly once, and has no serialization for an object.
     */
    private static void validatePathParameter(Node node, Registry registry) {
        String name = "'{" + node.name + "}'";
        if (node.min < 1) {
            throw new SpecException("line " + node.lineNo + ": path parameter " + name
                    + " cannot be optional - it is part of the URL, so its occurrence must be (1)");
        }
        if (node.isArray()) {
            throw new SpecException("line " + node.lineNo + ": path parameter " + name
                    + " cannot be repeated - one URL segment carries one value");
        }
        if (node.enumValues != null || node.enumName != null
                || BUILT_INS.containsKey(node.type.toLowerCase())) {
            return;
        }
        Map<String, Object> schema = registry.schemas.get(node.type);
        boolean object = !node.children.isEmpty()
                || (schema != null && (schema.containsKey("properties") || schema.containsKey("allOf")));
        if (object) {
            throw new SpecException("line " + node.lineNo + ": path parameter " + name
                    + " cannot carry the object type '" + node.type + "' - a URL segment has no"
                    + " serialization for it (use a built-in type or an enum)");
        }
    }

    private static String indentedBelow(Node node) {
        return " (line " + node.children.get(0).lineNo + " is indented below it)";
    }

    /** The children that become body properties: neither parameters nor 'extended by' markers. */
    private static List<Node> bodyChildren(Node node) {
        List<Node> body = new ArrayList<>();
        for (Node child : node.children) {
            if (!child.isParameter() && !child.isSubtypeMarker) {
                body.add(child);
            }
        }
        return body;
    }

    /** The body properties of one type/subtype block by name, rejecting duplicates. */
    private static Map<String, Node> declaredProperties(Node node) {
        Map<String, Node> properties = new LinkedHashMap<>();
        for (Node child : bodyChildren(node)) {
            Node first = properties.putIfAbsent(child.name, child);
            if (first != null) {
                throw new SpecException("line " + child.lineNo + ": duplicate property '" + child.name
                        + "' (already declared at line " + first.lineNo + ")");
            }
        }
        return properties;
    }

    /**
     * Parameters describe one operation, so they only sit directly below its parts: any location
     * below 'request', and headers below 'response' as well - a response has no other parameters.
     */
    private static void validateParameterPlacement(List<Node> roots, Node request, Node response) {
        for (Node root : roots) {
            if (root.isParameter()) {
                rejectParameter(root);
            }
            for (Node child : root.children) {
                if (child.isParameter() && root != request && !(root == response && child.isHeader())) {
                    rejectParameter(child);
                }
                rejectNestedParameters(child);
            }
        }
    }

    private static void rejectNestedParameters(Node node) {
        for (Node child : node.children) {
            if (child.isParameter()) {
                rejectParameter(child);
            }
            rejectNestedParameters(child);
        }
    }

    private static void rejectParameter(Node node) {
        if (node.isHeader()) {
            throw new SpecException("line " + node.lineNo + ": header '@" + node.name
                    + "' is only allowed directly below 'request' or 'response'");
        }
        throw new SpecException("line " + node.lineNo + ": parameter '" + node.sigilName()
                + "' is only allowed directly below 'request'");
    }

    private static void defineTypes(Node node, Registry registry, boolean isRequestRoot) {
        List<Node> subtypes = subtypeMarkers(node);
        Map<String, Node> properties = node.children.isEmpty() ? Map.of() : declaredProperties(node);
        // parameters (headers included) are not part of the schema, so a line carrying only those
        // (or nothing at all) defines nothing - it is a plain reference and may point at a type
        // defined elsewhere
        if (properties.isEmpty() && subtypes.isEmpty()) {
            boolean parameterOnlyRequest = isRequestRoot && !node.children.isEmpty();
            if (!parameterOnlyRequest) {
                registerLeaf(node, registry); // such a request has no body, so its type is unused
            }
            for (Node child : node.children) {
                defineTypes(child, registry, false); // the parameter types still need defining
            }
            return;
        }
        if (properties.isEmpty()) {
            // an object schema without properties is a free-form object for OpenAPI tooling: the
            // model is dropped, so the subtypes would silently lose their base class
            throw new SpecException("line " + subtypes.get(0).lineNo
                    + ": 'extended by' requires the base type to define at least one property");
        }
        // these three name the indented line as well: the mistake is almost always there, not here
        if (node.enumValues != null) {
            throw new SpecException("line " + node.lineNo + ": an enum cannot have nested properties"
                    + indentedBelow(node));
        }
        if (node.type.equalsIgnoreCase("discriminator")) {
            throw new SpecException("line " + node.lineNo
                    + ": 'discriminator' is a reserved type and cannot have nested properties"
                    + indentedBelow(node));
        }
        if (BUILT_INS.containsKey(node.type.toLowerCase())) {
            throw new SpecException("line " + node.lineNo + ": built-in type '" + node.type
                    + "' cannot have nested properties" + indentedBelow(node));
        }
        checkCaseCollision(node.type, node.lineNo, registry);
        if (registry.imports.containsKey(node.type)) {
            throw new SpecException("line " + node.lineNo + ": type '" + node.type
                    + "' is imported (line " + registry.imports.get(node.type).lineNo
                    + ") - its details are defined in the external file");
        }
        // a type's details are defined exactly once, at its first occurrence with properties
        if (registry.schemas.containsKey(node.type)) {
            throw new SpecException("line " + node.lineNo + ": type '" + node.type
                    + "' is already defined at line " + registry.definedAt.get(node.type)
                    + " - later occurrences must reference it by name, without nested properties");
        }
        Node discriminator = validateDiscriminator(node, subtypes);
        Map<String, Object> schema = objectSchema(node, registry.imports);
        if (discriminator != null) {
            schema.put("discriminator", discriminatorSchema(discriminator, subtypes));
        }
        registry.schemas.put(node.type, schema);
        registry.definedAt.put(node.type, node.lineNo);
        for (Node child : node.children) {
            if (child.isSubtypeMarker) {
                defineSubtype(child, node.type, properties, registry);
            } else {
                defineTypes(child, registry, false);
            }
        }
    }

    /** A line that defines nothing: a built-in, a named enum, or a reference to a named type. */
    private static void registerLeaf(Node node, Registry registry) {
        if (node.enumName != null) {
            defineNamedEnum(node, registry);
        } else if (node.enumValues == null
                && !node.type.equalsIgnoreCase("discriminator")
                && !BUILT_INS.containsKey(node.type.toLowerCase())) {
            checkCaseCollision(node.type, node.lineNo, registry);
            registry.references.putIfAbsent(node.type, node);
        }
    }

    /**
     * A subtype = allOf(base, additional properties); registered like any other named type.
     * {@code inherited} carries the property names of the whole base chain: re-declaring one of
     * them would produce a Java subclass that cannot override its parent's accessor.
     */
    private static void defineSubtype(Node marker, String baseName, Map<String, Node> inherited, Registry registry) {
        String subName = marker.type;
        if (BUILT_INS.containsKey(subName.toLowerCase()) || subName.equalsIgnoreCase("discriminator")) {
            throw new SpecException("line " + marker.lineNo + ": subtype name '" + subName
                    + "' collides with a built-in type");
        }
        checkCaseCollision(subName, marker.lineNo, registry);
        if (registry.imports.containsKey(subName)) {
            throw new SpecException("line " + marker.lineNo + ": type '" + subName
                    + "' is imported (line " + registry.imports.get(subName).lineNo
                    + ") - its details are defined in the external file");
        }
        if (registry.schemas.containsKey(subName)) {
            throw new SpecException("line " + marker.lineNo + ": type '" + subName
                    + "' is already defined at line " + registry.definedAt.get(subName));
        }
        Map<String, Node> additionalProperties = declaredProperties(marker);
        for (Map.Entry<String, Node> property : additionalProperties.entrySet()) {
            Node base = inherited.get(property.getKey());
            if (base != null) {
                throw new SpecException("line " + property.getValue().lineNo + ": property '" + property.getKey()
                        + "' is already declared by the base type at line " + base.lineNo
                        + " - a subtype block only adds properties");
            }
        }
        List<Node> subtypes = subtypeMarkers(marker);
        Node discriminator = validateDiscriminator(marker, subtypes);
        List<Object> allOf = new ArrayList<>();
        allOf.add(map("$ref", "#/components/schemas/" + baseName));
        Map<String, Object> additional = objectSchema(marker, registry.imports);
        if (additional.containsKey("properties") || additional.containsKey("required")) {
            allOf.add(additional);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("allOf", allOf);
        if (discriminator != null) {
            schema.put("discriminator", discriminatorSchema(discriminator, subtypes));
        }
        registry.schemas.put(subName, schema);
        registry.definedAt.put(subName, marker.lineNo);
        Map<String, Node> chain = new LinkedHashMap<>(inherited);
        chain.putAll(additionalProperties);
        for (Node child : marker.children) {
            if (child.isSubtypeMarker) {
                defineSubtype(child, subName, chain, registry);
            } else {
                defineTypes(child, registry, false);
            }
        }
    }

    private static List<Node> subtypeMarkers(Node node) {
        List<Node> markers = new ArrayList<>();
        for (Node child : node.children) {
            if (child.isSubtypeMarker) {
                markers.add(child);
            }
        }
        return markers;
    }

    /** Finds and validates the (at most one) property with the reserved type 'discriminator'. */
    private static Node validateDiscriminator(Node node, List<Node> subtypes) {
        Node discriminator = null;
        for (Node child : node.children) {
            if (child.isSubtypeMarker || !child.type.equalsIgnoreCase("discriminator")
                    || !child.children.isEmpty()) {
                continue;
            }
            if (child.isParameter()) {
                throw new SpecException("line " + child.lineNo + ": a "
                        + (child.isHeader() ? "header" : "parameter") + " cannot be a discriminator");
            }
            if (discriminator != null) {
                throw new SpecException("line " + child.lineNo + ": type '" + node.type
                        + "' has more than one discriminator property");
            }
            if (child.min < 1 || child.max != 1) {
                throw new SpecException("line " + child.lineNo
                        + ": the discriminator property must have occurrence (1)");
            }
            discriminator = child;
        }
        if (discriminator != null && subtypes.isEmpty()) {
            throw new SpecException("line " + discriminator.lineNo + ": type '" + node.type
                    + "' declares a discriminator but has no subtypes (add 'extended by <SubType>' lines)");
        }
        return discriminator;
    }

    /** discriminator: propertyName + mapping of ALL (transitive) subtype names to their schemas. */
    private static Map<String, Object> discriminatorSchema(Node discriminator, List<Node> subtypes) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        addSubtypeMapping(subtypes, mapping);
        return map("propertyName", discriminator.name, "mapping", mapping);
    }

    private static void addSubtypeMapping(List<Node> markers, Map<String, Object> mapping) {
        for (Node marker : markers) {
            mapping.put(marker.type, "#/components/schemas/" + marker.type);
            addSubtypeMapping(subtypeMarkers(marker), mapping);
        }
    }

    /** A named enum joins the type registry like an object type: defined once, referenced by name. */
    private static void defineNamedEnum(Node node, Registry registry) {
        if (BUILT_INS.containsKey(node.enumName.toLowerCase())) {
            throw new SpecException("line " + node.lineNo + ": enum name '" + node.enumName
                    + "' collides with a built-in type");
        }
        checkCaseCollision(node.enumName, node.lineNo, registry);
        if (registry.imports.containsKey(node.enumName)) {
            throw new SpecException("line " + node.lineNo + ": type '" + node.enumName
                    + "' is imported (line " + registry.imports.get(node.enumName).lineNo
                    + ") - its details are defined in the external file");
        }
        if (registry.schemas.containsKey(node.enumName)) {
            throw new SpecException("line " + node.lineNo + ": type '" + node.enumName
                    + "' is already defined at line " + registry.definedAt.get(node.enumName)
                    + " - later occurrences must reference it by name, without values");
        }
        registry.schemas.put(node.enumName, enumSchema(node, registry.imports));
        registry.definedAt.put(node.enumName, node.lineNo);
    }

    private record Spelling(String name, int lineNo) {
    }

    /**
     * Custom type names must differ by more than case and underscores. Names differing only in
     * case are almost certainly typos, and OpenAPI tooling camelizes schema names, so 'my_type'
     * and 'MyType' would collapse into a single generated class - silently losing one of them.
     */
    private static void checkCaseCollision(String typeName, int lineNo, Registry registry) {
        String normalized = typeName.toLowerCase().replace("_", "");
        Spelling first = registry.firstSpelling.putIfAbsent(normalized, new Spelling(typeName, lineNo));
        if (first != null && !first.name().equals(typeName)) {
            throw new SpecException("line " + lineNo + ": type '" + typeName
                    + "' differs only in case or underscores from '" + first.name()
                    + "' (line " + first.lineNo() + ")");
        }
    }

    private static Map<String, Object> objectSchema(Node node, Map<String, Node> imports) {
        List<String> required = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Node child : declaredProperties(node).values()) {
            if (child.min >= 1) {
                required.add(child.name);
            }
            properties.put(child.name, occurrenceSchema(child, imports));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        if (!properties.isEmpty()) {
            schema.put("properties", properties);
        }
        return schema;
    }

    private static Map<String, Object> occurrenceSchema(Node node, Map<String, Node> imports) {
        // a named enum is referenced like any other named type, even on its defining line
        Map<String, Object> item = node.enumName != null ? map("$ref", "#/components/schemas/" + node.enumName)
                : node.enumValues != null ? enumSchema(node, imports)
                : typeSchema(node.type, imports);
        if (node.attributes != null) {
            item.putAll(node.attributes); // on arrays this applies to the items, by design
        }
        if (!node.isArray()) {
            return item;
        }
        Map<String, Object> array = new LinkedHashMap<>();
        array.put("type", "array");
        array.put("items", item);
        if (node.min > 0) {
            array.put("minItems", node.min);
        }
        if (node.max != UNBOUNDED) {
            array.put("maxItems", node.max);
        }
        return array;
    }

    private static Map<String, Object> enumSchema(Node node, Map<String, Node> imports) {
        // start from the value type's schema (type + format), then restrict it to the values
        Map<String, Object> m = typeSchema(node.enumValueType, imports);
        m.put("enum", new ArrayList<>(node.enumValues));
        return m;
    }

    private static Map<String, Object> typeSchema(String type, Map<String, Node> imports) {
        if (type.equalsIgnoreCase("discriminator")) {
            return prim("string", null); // the discriminator property carries the subtype name
        }
        Map<String, Object> builtIn = BUILT_INS.get(type.toLowerCase());
        if (builtIn != null) {
            return new LinkedHashMap<>(builtIn);
        }
        Node imported = imports.get(type);
        if (imported != null) {
            // external ref; without a known path a type-specific placeholder for manual post-editing
            String file = imported.importPath != null ? imported.importPath : "TODO-IMPORT/" + type + ".yaml";
            return map("$ref", file + "#/components/schemas/" + type);
        }
        return map("$ref", "#/components/schemas/" + type);
    }

    private static Map<String, Object> prim(String type, String format) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (format != null) {
            m.put("format", format);
        }
        return m;
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------ YAML output

    private static void emitMap(StringBuilder sb, Map<String, ?> map, int depth) {
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            sb.append("  ".repeat(depth));
            emitEntry(sb, entry.getKey(), entry.getValue(), depth);
        }
    }

    private static void emitList(StringBuilder sb, List<?> list, int depth) {
        for (Object element : list) {
            if (element instanceof Map<?, ?> map) {
                if (map.isEmpty()) {
                    sb.append("  ".repeat(depth)).append("- {}\n");
                    continue;
                }
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    // first entry inline after the dash, the rest aligned below it
                    sb.append(first ? "  ".repeat(depth) + "- " : "  ".repeat(depth + 1));
                    emitEntry(sb, (String) entry.getKey(), entry.getValue(), depth + 1);
                    first = false;
                }
            } else {
                sb.append("  ".repeat(depth)).append("- ").append(yamlScalar(element)).append("\n");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void emitEntry(StringBuilder sb, String key, Object value, int depth) {
        sb.append(yamlKey(key)).append(":");
        if (value instanceof Map<?, ?> nested) {
            // an empty block would emit a bare 'key:', i.e. null - not the object/array the
            // OpenAPI schema demands (e.g. 'components.schemas is not of type object')
            if (nested.isEmpty()) {
                sb.append(" {}\n");
                return;
            }
            sb.append("\n");
            emitMap(sb, (Map<String, ?>) nested, depth + 1);
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append(" []\n");
                return;
            }
            sb.append("\n");
            emitList(sb, list, depth + 1);
        } else {
            sb.append(" ").append(yamlScalar(value)).append("\n");
        }
    }

    /**
     * Words a YAML 1.1 parser resolves to a boolean or null when they are not quoted - as KEYS
     * too, so a property named 'on' would silently turn into the boolean 'true'.
     */
    private static boolean isReservedYamlWord(String s) {
        return s.matches("(?i)y|n|yes|no|true|false|on|off|null|~");
    }

    private static String yamlKey(String key) {
        boolean plainSafe = key.matches("[A-Za-z_][A-Za-z0-9_.-]*") && !isReservedYamlWord(key);
        return plainSafe ? key : yamlQuoted(key);
    }

    private static String yamlScalar(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String s = String.valueOf(value);
        boolean plainSafe = s.matches("[A-Za-z0-9_][A-Za-z0-9_ .-]*")
                && !isReservedYamlWord(s) && !s.matches("\\d+");
        return plainSafe ? s : yamlQuoted(s);
    }

    private static String yamlQuoted(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
