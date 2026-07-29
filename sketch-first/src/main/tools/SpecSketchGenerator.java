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
 *             defined elsewhere in the file. Custom type names are case-sensitive;
 *             two custom types whose names differ only in case are an error.
 *             Built-ins (case-insensitive, so both 'string' and 'String' work):
 *             - OpenAPI-style: string, number, integer, boolean, date, datetime, uuid
 *             - Java primitives/wrappers: int, long, short, byte, char, float,
 *               double, boolean, String, BigDecimal, BigInteger
 *             - java.time (Java 8+): LocalDate, LocalDateTime, OffsetDateTime,
 *               ZonedDateTime, Instant, LocalTime, OffsetTime
 * Inheritance: inside a type definition a line 'extended by <SubType>' starts
 *             a subtype block; its nested lines are the ADDITIONAL properties
 *             of the subtype (emitted as an allOf composition, which the DTO
 *             generator turns into 'class SubType extends Base'). Subtype
 *             blocks may be nested for deeper hierarchies. Marking one base
 *             property with the reserved type 'discriminator' makes the
 *             hierarchy polymorphic (discriminator + mapping in the YAML,
 *             Jackson @JsonTypeInfo/@JsonSubTypes in the generated Java):
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
 * Additional top-level lines may define further reusable types.
 *
 * Imports: a top-level line 'import <TypeName>' declares a type whose details
 * live in an existing shared/common yaml file - no local schema is generated,
 * every usage becomes an external $ref with a type-specific placeholder meant
 * for manual post-editing:
 *     import Address
 *     ->  $ref: 'TODO-IMPORT/Address.yaml#/components/schemas/Address'
 * If the file is already known, 'import Address from "common/types.yaml"'
 * emits the real ref instead. Defining properties for an imported type is an
 * error; imports join the type registry (define-once, case rules).
 *
 * Attributes: a property line may end with an optional {key: value, ...} block
 * holding validation attributes, validated against the property's type:
 *     name (1) : string {minLength: 1, maxLength: 100}
 *     email (0 - 1) : string {pattern: "^.+@.+$"}
 *     age (0 - 1) : int {min: 0, max: 150}
 * Strings support minLength, maxLength and pattern; numeric types support min
 * and max (aliases: minimum, maximum). Values may be double-quoted (required
 * when they contain commas or braces, e.g. patterns with quantifiers). On
 * arrays the attributes apply to the items - the array bounds already come
 * from the occurrence.
 *
 * Comments: every line may end with a comment introduced by '//', '#' or
 * '/* ... *&#47;' (the block form may also sit mid-line and must be closed on
 * the same line). Lines containing only a comment are ignored at any
 * indentation and never affect the nesting. Blank lines are ignored as well.
 *
 * Usage: java SpecSketchGenerator.java <input.sketch> [<output.yaml> | -]
 *        With only the input path the YAML is saved next to the input file as
 *        <input-basename>.yaml; a second argument sets an explicit output path;
 *        '-' prints to stdout instead.
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

    private static final Pattern LINE = Pattern.compile(
            "(@?[A-Za-z_][A-Za-z0-9_-]*)\\s*\\(\\s*(\\d+)\\s*(?:-\\s*(\\d+|\\*)\\s*)?\\)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*))?(?:\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*(?:\\[([^\\]]*)\\])?\\s*(?:\\{(.*)\\})?");

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

    private static final class Node {
        final int lineNo;
        final String name;
        final boolean isHeader;        // name started with '@'
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

        Node(int lineNo, String name, boolean isHeader, boolean isSubtypeMarker,
             boolean isImport, String importPath, int min, int max, String type,
             String enumValueType, String enumName, List<Object> enumValues, Map<String, Object> attributes) {
            this.lineNo = lineNo;
            this.name = name;
            this.isHeader = isHeader;
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
            String baseName = input.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            String yaml = generateYaml(Files.readAllLines(input), baseName);
            if (args.length == 2 && args[1].equals("-")) {
                System.out.print(yaml);
                return;
            }
            // default: save the YAML in the same path as the input file
            Path output = args.length == 2 ? Path.of(args[1]) : input.resolveSibling(baseName + ".yaml");
            if (output.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())) {
                throw new SpecException("output path would overwrite the input file: " + output);
            }
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.writeString(output, yaml);
            System.out.println("SpecSketchGenerator: " + input + " -> " + output);
        } catch (SpecException e) {
            System.err.println("SpecSketchGenerator: " + input + ": " + e.getMessage());
            System.exit(1);
        }
    }

    /** Entry point for tests and for {@link #main}: SpecSketch lines in, OpenAPI YAML out. */
    static String generateYaml(List<String> lines, String baseName) {
        List<Node> roots = parse(lines);
        if (roots.isEmpty()) {
            throw new SpecException("no definitions found");
        }
        return generate(roots, baseName);
    }

    // ---------------------------------------------------------------- parsing

    private static List<Node> parse(List<String> lines) {
        List<Node> roots = new ArrayList<>();
        List<Node> stack = new ArrayList<>(); // stack.get(i) = currently open node at depth i
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
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

    /** Removes '//' and '#' comments (rest of line) and single-line '/* ... *&#47;' block comments. */
    private static String stripComments(String text, int lineNo) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean hasNext = i + 1 < text.length();
            if (c == '#' || (c == '/' && hasNext && text.charAt(i + 1) == '/')) {
                break;
            }
            if (c == '/' && hasNext && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new SpecException("line " + lineNo + ": block comment '/*' is not closed on the same line");
                }
                result.append(' ');
                i = end + 2;
                continue;
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    private static Node parseBody(String body, int lineNo) {
        Matcher importMatcher = IMPORT_LINE.matcher(body);
        if (importMatcher.matches()) {
            String importedType = importMatcher.group(1);
            return new Node(lineNo, importedType, false, false, true, importMatcher.group(2),
                    1, 1, importedType, null, null, null, null);
        }
        Matcher extendedByMatcher = EXTENDED_BY_LINE.matcher(body);
        if (extendedByMatcher.matches()) {
            String subType = extendedByMatcher.group(1);
            return new Node(lineNo, subType, false, true, false, null, 1, 1, subType, null, null, null, null);
        }
        Matcher m = LINE.matcher(body);
        if (!m.matches()) {
            throw new SpecException("line " + lineNo + ": expected '<name> (<occurrence>) : <type>', got: " + body);
        }
        int min = Integer.parseInt(m.group(2));
        String maxToken = m.group(3);
        int max = maxToken == null ? min : maxToken.equals("*") ? UNBOUNDED : Integer.parseInt(maxToken);
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
        boolean isHeader = rawName.startsWith("@");
        return new Node(lineNo, isHeader ? rawName.substring(1) : rawName, isHeader, false, false, null,
                min, max, type, enumValueType, enumName, enumValues, attributes);
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
                    if (!family.equals("string")) {
                        throw new SpecException("line " + lineNo + ": '" + key + "' is only allowed on string types"
                                + " (use min/max for numeric ranges)");
                    }
                    yield key.toLowerCase().equals("minlength") ? "minLength" : "maxLength";
                }
                case "pattern" -> {
                    if (!family.equals("string")) {
                        throw new SpecException("line " + lineNo + ": 'pattern' is only allowed on string types");
                    }
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
            if (values.contains(value)) {
                throw new SpecException("line " + lineNo + ": duplicate enum value '" + text + "'");
            }
            values.add(value);
        }
        return values;
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

    private static String generate(List<Node> roots, String baseName) {
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        Map<String, Integer> definedAt = new LinkedHashMap<>();
        Map<String, Spelling> firstSpelling = new LinkedHashMap<>(); // lowercase name -> first occurrence
        Map<String, Node> references = new LinkedHashMap<>();
        Map<String, Node> imports = new LinkedHashMap<>(); // imported name -> its 'import' line
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
            checkCaseCollision(root.type, root.lineNo, firstSpelling);
            if (imports.putIfAbsent(root.type, root) != null) {
                throw new SpecException("line " + root.lineNo + ": type '" + root.type + "' is already imported");
            }
        }
        for (Node root : roots) {
            if (!root.isImport) {
                defineTypes(root, schemas, definedAt, firstSpelling, references, imports);
            }
        }
        for (Map.Entry<String, Node> ref : references.entrySet()) {
            if (!schemas.containsKey(ref.getKey()) && !imports.containsKey(ref.getKey())) {
                throw new SpecException("line " + ref.getValue().lineNo + ": type '" + ref.getKey()
                        + "' is used but never defined (define its properties at one of its usages, or import it)");
            }
        }

        Node request = null;
        Node response = null;
        for (Node root : roots) {
            if (root.isImport) {
                continue; // collected below, after the registries exist
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

        validateHeaderPlacement(roots, request, response);
        List<Node> requestHeaders = request == null ? List.of() : headerChildren(request);
        List<Node> responseHeaders = headerChildren(response);
        // a request whose children are all '@' headers has no body and stays a GET
        boolean hasBody = request != null
                && (request.children.isEmpty() || request.children.size() > requestHeaders.size());

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
        if (!requestHeaders.isEmpty()) {
            List<Object> parameters = new ArrayList<>();
            for (Node header : requestHeaders) {
                Map<String, Object> parameter = new LinkedHashMap<>();
                parameter.put("name", header.name);
                parameter.put("in", "header");
                if (header.min >= 1) {
                    parameter.put("required", true);
                }
                parameter.put("schema", occurrenceSchema(header, imports));
                parameters.add(parameter);
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
        doc.put("paths", map("/" + baseName, map(method, operation)));
        doc.put("components", map("schemas", schemas));

        StringBuilder sb = new StringBuilder();
        emitMap(sb, doc, 0);
        return sb.toString();
    }

    private static List<Node> headerChildren(Node node) {
        List<Node> headers = new ArrayList<>();
        for (Node child : node.children) {
            if (child.isHeader) {
                headers.add(child);
            }
        }
        return headers;
    }

    private static void validateHeaderPlacement(List<Node> roots, Node request, Node response) {
        for (Node root : roots) {
            if (root.isHeader) {
                rejectHeader(root);
            }
            boolean headersAllowed = root == request || root == response;
            for (Node child : root.children) {
                if (child.isHeader && !headersAllowed) {
                    rejectHeader(child);
                }
                rejectNestedHeaders(child);
            }
        }
    }

    private static void rejectNestedHeaders(Node node) {
        for (Node child : node.children) {
            if (child.isHeader) {
                rejectHeader(child);
            }
            rejectNestedHeaders(child);
        }
    }

    private static void rejectHeader(Node node) {
        throw new SpecException("line " + node.lineNo + ": header '@" + node.name
                + "' is only allowed directly below 'request' or 'response'");
    }

    private static void defineTypes(Node node, Map<String, Map<String, Object>> schemas,
                                    Map<String, Integer> definedAt, Map<String, Spelling> firstSpelling,
                                    Map<String, Node> references, Map<String, Node> imports) {
        if (node.children.isEmpty()) {
            if (node.enumName != null) {
                defineNamedEnum(node, schemas, definedAt, firstSpelling, imports);
            } else if (node.enumValues == null
                    && !node.type.equalsIgnoreCase("discriminator")
                    && !BUILT_INS.containsKey(node.type.toLowerCase())) {
                checkCaseCollision(node.type, node.lineNo, firstSpelling);
                references.putIfAbsent(node.type, node);
            }
            return;
        }
        if (node.enumValues != null) {
            throw new SpecException("line " + node.lineNo + ": an enum cannot have nested properties");
        }
        if (node.type.equalsIgnoreCase("discriminator")) {
            throw new SpecException("line " + node.lineNo
                    + ": 'discriminator' is a reserved type and cannot have nested properties");
        }
        if (BUILT_INS.containsKey(node.type.toLowerCase())) {
            throw new SpecException("line " + node.lineNo + ": built-in type '" + node.type
                    + "' cannot have nested properties");
        }
        checkCaseCollision(node.type, node.lineNo, firstSpelling);
        if (imports.containsKey(node.type)) {
            throw new SpecException("line " + node.lineNo + ": type '" + node.type
                    + "' is imported (line " + imports.get(node.type).lineNo
                    + ") - its details are defined in the external file");
        }
        // a type's details are defined exactly once, at its first occurrence with properties
        if (schemas.containsKey(node.type)) {
            throw new SpecException("line " + node.lineNo + ": type '" + node.type
                    + "' is already defined at line " + definedAt.get(node.type)
                    + " - later occurrences must reference it by name, without nested properties");
        }
        List<Node> subtypes = subtypeMarkers(node);
        Node discriminator = validateDiscriminator(node, subtypes);
        Map<String, Object> schema = objectSchema(node, imports);
        if (discriminator != null) {
            schema.put("discriminator", discriminatorSchema(discriminator, subtypes));
        }
        schemas.put(node.type, schema);
        definedAt.put(node.type, node.lineNo);
        for (Node child : node.children) {
            if (child.isSubtypeMarker) {
                defineSubtype(child, node.type, schemas, definedAt, firstSpelling, references, imports);
            } else {
                defineTypes(child, schemas, definedAt, firstSpelling, references, imports);
            }
        }
    }

    /** A subtype = allOf(base, additional properties); registered like any other named type. */
    private static void defineSubtype(Node marker, String baseName, Map<String, Map<String, Object>> schemas,
                                      Map<String, Integer> definedAt, Map<String, Spelling> firstSpelling,
                                      Map<String, Node> references, Map<String, Node> imports) {
        String subName = marker.type;
        if (BUILT_INS.containsKey(subName.toLowerCase()) || subName.equalsIgnoreCase("discriminator")) {
            throw new SpecException("line " + marker.lineNo + ": subtype name '" + subName
                    + "' collides with a built-in type");
        }
        checkCaseCollision(subName, marker.lineNo, firstSpelling);
        if (imports.containsKey(subName)) {
            throw new SpecException("line " + marker.lineNo + ": type '" + subName
                    + "' is imported (line " + imports.get(subName).lineNo
                    + ") - its details are defined in the external file");
        }
        if (schemas.containsKey(subName)) {
            throw new SpecException("line " + marker.lineNo + ": type '" + subName
                    + "' is already defined at line " + definedAt.get(subName));
        }
        List<Node> subtypes = subtypeMarkers(marker);
        Node discriminator = validateDiscriminator(marker, subtypes);
        List<Object> allOf = new ArrayList<>();
        allOf.add(map("$ref", "#/components/schemas/" + baseName));
        Map<String, Object> additional = objectSchema(marker, imports);
        if (additional.containsKey("properties") || additional.containsKey("required")) {
            allOf.add(additional);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("allOf", allOf);
        if (discriminator != null) {
            schema.put("discriminator", discriminatorSchema(discriminator, subtypes));
        }
        schemas.put(subName, schema);
        definedAt.put(subName, marker.lineNo);
        for (Node child : marker.children) {
            if (child.isSubtypeMarker) {
                defineSubtype(child, subName, schemas, definedAt, firstSpelling, references, imports);
            } else {
                defineTypes(child, schemas, definedAt, firstSpelling, references, imports);
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
            if (child.isHeader) {
                throw new SpecException("line " + child.lineNo + ": a header cannot be a discriminator");
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
    private static void defineNamedEnum(Node node, Map<String, Map<String, Object>> schemas,
                                        Map<String, Integer> definedAt, Map<String, Spelling> firstSpelling,
                                        Map<String, Node> imports) {
        if (BUILT_INS.containsKey(node.enumName.toLowerCase())) {
            throw new SpecException("line " + node.lineNo + ": enum name '" + node.enumName
                    + "' collides with a built-in type");
        }
        checkCaseCollision(node.enumName, node.lineNo, firstSpelling);
        if (imports.containsKey(node.enumName)) {
            throw new SpecException("line " + node.lineNo + ": type '" + node.enumName
                    + "' is imported (line " + imports.get(node.enumName).lineNo
                    + ") - its details are defined in the external file");
        }
        if (schemas.containsKey(node.enumName)) {
            throw new SpecException("line " + node.lineNo + ": type '" + node.enumName
                    + "' is already defined at line " + definedAt.get(node.enumName)
                    + " - later occurrences must reference it by name, without values");
        }
        schemas.put(node.enumName, enumSchema(node, imports));
        definedAt.put(node.enumName, node.lineNo);
    }

    private record Spelling(String name, int lineNo) {
    }

    /** Custom type names are case-sensitive - names that differ only in case are almost certainly typos. */
    private static void checkCaseCollision(String typeName, int lineNo, Map<String, Spelling> firstSpelling) {
        Spelling first = firstSpelling.putIfAbsent(typeName.toLowerCase(), new Spelling(typeName, lineNo));
        if (first != null && !first.name().equals(typeName)) {
            throw new SpecException("line " + lineNo + ": type '" + typeName
                    + "' differs only in case from '" + first.name() + "' (line " + first.lineNo() + ")");
        }
    }

    private static Map<String, Object> objectSchema(Node node, Map<String, Node> imports) {
        List<String> required = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Node child : node.children) {
            if (child.isHeader || child.isSubtypeMarker) {
                continue; // headers and subtype blocks are not body properties
            }
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
            sb.append("\n");
            emitMap(sb, (Map<String, ?>) nested, depth + 1);
        } else if (value instanceof List<?> list) {
            sb.append("\n");
            emitList(sb, list, depth + 1);
        } else {
            sb.append(" ").append(yamlScalar(value)).append("\n");
        }
    }

    private static String yamlKey(String key) {
        return key.matches("[A-Za-z_][A-Za-z0-9_.-]*") ? key : "'" + key.replace("'", "''") + "'";
    }

    private static String yamlScalar(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String s = String.valueOf(value);
        boolean plainSafe = s.matches("[A-Za-z0-9_][A-Za-z0-9_ .-]*")
                && !s.matches("(?i)true|false|null|yes|no|on|off|~|\\d+");
        return plainSafe ? s : "'" + s.replace("'", "''") + "'";
    }
}
