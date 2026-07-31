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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The class diagram of a sketch, in a form no diagram language has yet: boxes with members,
 * inheritance arrows and labelled associations. SketchMermaidGenerator and SketchPlantUmlGenerator
 * are the two spellings of exactly this - which is the point of the split. Deciding what a diagram
 * shows (and what it leaves out) is where all the thinking sits; writing it down in mermaid or in
 * PlantUML is a formatting question, and one nobody should have to answer twice. A rule added here
 * reaches both languages, and neither can drift away from the other.
 *
 * Nothing here parses the DSL. The sketch is handed to SpecSketchGenerator, translated to YAML and
 * that result thrown away: a sketch that would not produce a valid OpenAPI document produces no
 * diagram either, and fails with the same message and the same line number. What is drawn is the
 * very node tree the translation walks, so the picture can never disagree with the document.
 *
 * The mapping, in one place:
 * <ul>
 *   <li><b>a type with properties becomes a box</b>, in declaration order. The request and the
 *       response type carry a 'request'/'response' stereotype - the entry points of the operation
 *       the sketch describes - and an imported type the stereotype 'external';</li>
 *   <li><b>a property of a built-in type becomes a member</b>, spelled as the sketch spells it
 *       ('+long id', '+Instant createdAt'), followed by a UML multiplicity unless the occurrence is
 *       exactly one: '[0..1]' optional, '[0..*]' / '[1..*]' / '[0..3]' repeatable. The reserved
 *       'discriminator' type stays visible as the type of the property carrying it;</li>
 *   <li><b>a property of a named type becomes an association</b> labelled with the property name
 *       and carrying that multiplicity, a self-reference included (the recursion it is);</li>
 *   <li><b>a parameter or header becomes a member keeping its sigil</b>, so a reader sees where a
 *       value travels: '@X-Request-Id', '?status', '$cookie:session', '$petId' while the location
 *       is undecided. A path parameter uses the DSL's long form '$path:petId': the braces of
 *       '{petId}' would end the class body both languages write in braces. A parameter of a named
 *       type becomes an association labelled with its sigil name;</li>
 *   <li><b>an enum becomes a box of kind ENUM</b> listing its values. A named enum appears once
 *       under its name; an inline (anonymous) one under '&lt;OwnerType&gt;&lt;Property&gt;Enum',
 *       the inner enum openapi-generator derives from it. A value type other than string is stated
 *       in the box's note, since 'enum [1, 2]' and 'enum:int [1, 2]' list the same values but
 *       describe different enums;</li>
 *   <li><b>a subtype becomes an inheritance arrow</b>, its box holding only the properties it adds
 *       - exactly the allOf composition of the YAML;</li>
 *   <li><b>an import becomes a box with a note</b> naming the file its details live in.</li>
 * </ul>
 *
 * A part that declares its payload instead of naming a type gets a box named after itself, with the
 * payload as its 'body' member: 'response (1) : string' becomes a 'response' box holding
 * '+string body' (and its headers next to it), and so does 'response (1) : enum Status [A, B]',
 * whose enum is a box of its own that 'body' then points at. A request consisting of parameters
 * only shows the parameters alone - it has no body in the YAML either.
 *
 * Deliberately left out is everything that describes the wire format rather than the model:
 * validation attributes ('{minLength: 1}'), the OpenAPI type and format behind each built-in, the
 * HTTP method, the derived path template and the content type. Those live in the generated YAML; a
 * class diagram repeating them would be unreadable without being any more complete.
 *
 * {@link View#STRUCTURE} keeps the type graph and drops the detail: every box, association and
 * inheritance arrow stays, the members of a built-in type go. On a model of any size the full view
 * spends most of its space on strings and numbers, while the question 'which types are there and
 * how do they relate' is answered by the boxes and arrows alone. Two things stay although they are
 * no types of their own, because they say what a type IS rather than what it holds: the values of
 * an enum, and the discriminator - the marker that turns a hierarchy into a polymorphic one (and a
 * generated Java subclass into a real subclass).
 */
final class SketchDiagram {

    /** Mirrors SpecSketchGenerator's marker for the '*' of an unbounded occurrence. */
    private static final int UNBOUNDED = -1;

    /** The option both generators take to ask for the structure view. */
    private static final String STRUCTURE_OPTION = "--structure";

    /** How much of the sketch a diagram shows. */
    enum View {
        /** Every property, parameter and header the sketch declares. */
        FULL,
        /** The type graph: no members of a built-in type, the discriminator excepted. */
        STRUCTURE
    }

    /** What a box is. Both languages have a distinct form for an enum. */
    enum Kind {
        CLASS, ENUM
    }

    /** One box of the diagram. */
    static final class Type {
        final String name;
        Kind kind = Kind.CLASS;
        /** request, response or external - what an enum is comes from {@link #kind}. */
        String stereotype;
        /** At most one: the file an import comes from, or the value type of an enum. */
        String note;
        final List<String> members = new ArrayList<>();

        private Type(String name) {
            this.name = name;
        }

        /** A box with nothing to show inside it - the languages write it without a body. */
        boolean hasBody() {
            return kind == Kind.ENUM || !members.isEmpty();
        }

        /**
         * 'external' says what the type IS and wins; 'request'/'response' only state which part
         * uses it, so they fill an empty slot - a response that references a type defined further
         * down marks that very box, and must not overwrite what the box already says.
         */
        private void stereotype(String stereotype) {
            boolean part = stereotype.equals("request") || stereotype.equals("response");
            if (this.stereotype == null || !part) {
                this.stereotype = stereotype;
            }
        }
    }

    /** '<i>base</i> is extended by <i>subtype</i>'. */
    record Inheritance(String base, String subtype) {
    }

    /** '<i>from</i> holds <i>multiplicity</i> of <i>to</i>, under the name <i>label</i>'. */
    record Association(String from, String multiplicity, String to, String label) {
    }

    private final Set<String> declaredTypes;
    private final View view;
    private final List<String> messages;
    private final Map<String, Type> types = new LinkedHashMap<>();
    private final List<Inheritance> inheritance = new ArrayList<>();
    private final List<Association> associations = new ArrayList<>();

    private SketchDiagram(Set<String> declaredTypes, View view, List<String> messages) {
        this.declaredTypes = declaredTypes;
        this.view = view;
        this.messages = messages;
    }

    /**
     * SpecSketch lines in, the diagram out.
     *
     * {@code messages} collects what the drawing decided on the sketch's behalf, which is only ever
     * a synthesized box name that had to be disambiguated. The translation's own messages are NOT
     * forwarded: they describe the YAML no diagram generator writes (the path template derived for
     * path parameters, and the query fallback for a parameter whose location is undecided - which a
     * diagram simply draws as undecided).
     */
    static SketchDiagram of(List<String> sketchLines, String sourceName, View view, List<String> messages) {
        // only a sketch the toolchain accepts gets a picture - same rules, same line numbers
        SpecSketchGenerator.generateYaml(sketchLines, sourceName.replaceFirst("\\.[^.]+$", ""), new ArrayList<>());
        List<SpecSketchGenerator.Node> roots = SpecSketchGenerator.parse(sketchLines);

        SketchDiagram diagram = new SketchDiagram(declaredTypes(roots), view, messages);
        for (SpecSketchGenerator.Node root : roots) {
            if (root.isImport) {
                diagram.importedType(root);
            } else if (root.name.equals("request") || root.name.equals("response")) {
                Type part = diagram.partType(root);
                part.stereotype(root.name);
                diagram.addChildren(part, root);
            } else if (root.enumName != null) {
                diagram.enumType(root.enumName, root);
            } else if (definesType(root)) {
                diagram.addType(root);
            }
            // a top-level line that only references a type defined elsewhere adds nothing on its own
        }
        return diagram;
    }

    View view() {
        return view;
    }

    /** The boxes in declaration order. */
    Collection<Type> types() {
        return types.values();
    }

    List<Inheritance> inheritance() {
        return inheritance;
    }

    List<Association> associations() {
        return associations;
    }

    // ------------------------------------------------------------- the tree walk

    /**
     * Every name that stands for a box, collected before anything is drawn: a property may
     * reference a type the sketch defines further down, and only a name that is NOT in here can be
     * a built-in (the translation already rejected everything undefined).
     */
    private static Set<String> declaredTypes(List<SpecSketchGenerator.Node> roots) {
        Set<String> declared = new LinkedHashSet<>();
        for (SpecSketchGenerator.Node root : roots) {
            if (root.isImport) {
                declared.add(root.type);
            } else {
                collectDeclaredTypes(root, declared);
            }
        }
        return declared;
    }

    private static void collectDeclaredTypes(SpecSketchGenerator.Node node, Set<String> declared) {
        if (node.enumName != null) {
            declared.add(node.enumName);
        } else if (node.isSubtypeMarker || definesType(node)) {
            declared.add(node.type);
        }
        for (SpecSketchGenerator.Node child : node.children) {
            collectDeclaredTypes(child, declared);
        }
    }

    /** A line defines its type where it carries properties or subtypes; otherwise it references one. */
    private static boolean definesType(SpecSketchGenerator.Node node) {
        for (SpecSketchGenerator.Node child : node.children) {
            if (!child.isParameter()) {
                return true; // a body property or an 'extended by' marker
            }
        }
        return false;
    }

    /**
     * The box of the 'request'/'response' part, which is normally the box of the type it names.
     * Where the part declares its payload instead - a built-in, or an enum written out on the line,
     * whose name is not the type word at all - the part gets a box named after itself and the
     * payload becomes its 'body' member; the same box holds the parameters of a request that has no
     * payload to begin with (parameters only, whose type name reaches the YAML just as little).
     * Without that, 'response (1) : string' would draw a box called 'string' and its headers would
     * land inside it.
     */
    private Type partType(SpecSketchGenerator.Node part) {
        // a request of parameters alone has no body - a response always carries one
        boolean noPayload = part.name.equals("request") && !part.children.isEmpty() && !definesType(part);
        if (!noPayload && part.enumName == null && part.enumValues == null
                && declaredTypes.contains(part.type)) {
            return typeFor(part.type);
        }
        Type box = typeFor(part.name);
        if (!noPayload) {
            addMember(box, part, "body");
        }
        return box;
    }

    private void addType(SpecSketchGenerator.Node node) {
        addChildren(typeFor(node.type), node);
    }

    private void addChildren(Type owner, SpecSketchGenerator.Node node) {
        for (SpecSketchGenerator.Node child : node.children) {
            if (child.isSubtypeMarker) {
                inheritance.add(new Inheritance(owner.name, child.type));
                // a subtype block holds the properties it adds, and may open subtypes of its own
                addChildren(typeFor(child.type), child);
                continue;
            }
            addMember(owner, child, child.isParameter() ? sigilName(child) : child.name);
            if (definesType(child)) {
                addType(child);
            }
        }
    }

    /**
     * One property, parameter or header under {@code label}: a member of the owner box if its type
     * is a built-in, an association to another box otherwise.
     */
    private void addMember(Type owner, SpecSketchGenerator.Node node, String label) {
        if (node.enumName != null) {                       // the line defining a named enum
            enumType(node.enumName, node);
            associate(owner, node.enumName, node, label);
        } else if (node.enumValues != null) {              // an inline, anonymous enum
            // the box is named after the property, so a parameter contributes its plain name
            String inlineEnum = inlineEnumType(owner, node, node.isParameter() ? node.name : label);
            associate(owner, inlineEnum, node, label);
        } else if (declaredTypes.contains(node.type)) {    // object type, named enum, import
            associate(owner, node.type, node, label);
        } else if (view == View.FULL || isDiscriminator(node)) {
            // a built-in member, or the discriminator - which the structure view keeps, because it
            // is what makes the hierarchy below it polymorphic
            owner.members.add("+" + node.type + " " + label + multiplicitySuffix(node));
        }
    }

    private static boolean isDiscriminator(SpecSketchGenerator.Node node) {
        return node.type.equalsIgnoreCase("discriminator");
    }

    /**
     * The name as the sketch declares it, sigil included - the shortest way to show a reader that a
     * value travels in the URL, in a header or in a cookie rather than in the payload. A path
     * parameter uses the equivalent long form: '{petId}' would end the class body.
     */
    private static String sigilName(SpecSketchGenerator.Node node) {
        return switch (node.location) {
            case HEADER -> "@" + node.name;
            case PATH -> "$path:" + node.name;
            case QUERY -> "?" + node.name;
            case COOKIE -> "$cookie:" + node.name;
            case UNSPECIFIED -> "$" + node.name;
        };
    }

    /** The UML multiplicity of an occurrence: '1', '0..1', '0..*', '1..*', '2', '2..5'. */
    private static String multiplicity(SpecSketchGenerator.Node node) {
        if (node.min == node.max) {
            return String.valueOf(node.min);
        }
        return node.min + ".." + (node.max == UNBOUNDED ? "*" : String.valueOf(node.max));
    }

    /** On a member the multiplicity is only spelled out where it is not exactly one. */
    private static String multiplicitySuffix(SpecSketchGenerator.Node node) {
        String multiplicity = multiplicity(node);
        return multiplicity.equals("1") ? "" : " [" + multiplicity + "]";
    }

    // ----------------------------------------------------------------- the boxes

    /**
     * The box of a type, created at its first appearance. Boxes are shared on purpose: a response
     * that only references a type defined further down marks that very box.
     */
    private Type typeFor(String name) {
        return types.computeIfAbsent(name, Type::new);
    }

    private void associate(Type owner, String target, SpecSketchGenerator.Node node, String label) {
        associations.add(new Association(owner.name, multiplicity(node), target, label));
    }

    private void enumType(String name, SpecSketchGenerator.Node node) {
        Type box = typeFor(name);
        box.kind = Kind.ENUM;
        // 'enum [1, 2]' and 'enum:int [1, 2]' list the same values but produce a String and an int
        // enum; the values alone do not say which, so a non-string value type is stated in the note
        if (!node.enumValueType.equalsIgnoreCase("string")) {
            box.note = "values of type " + node.enumValueType;
        }
        for (Object value : node.enumValues) {
            box.members.add(String.valueOf(value));
        }
    }

    /**
     * An inline enum has no name in the sketch, so it gets the one openapi-generator derives for
     * the inner Java enum it becomes: the owning type plus the property, e.g. 'Payment' + 'method'
     * -> 'PaymentMethodEnum'. Both languages identify a box by that name, so it is reduced to name
     * characters (a header like '@X-Request-Id' may carry dashes) and disambiguated against the
     * names already taken - the one case worth reporting, because the diagram then shows a name the
     * sketch does not contain.
     */
    private String inlineEnumType(Type owner, SpecSketchGenerator.Node node, String property) {
        String candidate = (owner.name + property.substring(0, 1).toUpperCase() + property.substring(1)
                + "Enum").replaceAll("[^A-Za-z0-9_]", "_");
        String name = candidate;
        for (int i = 2; declaredTypes.contains(name) || types.containsKey(name); i++) {
            name = candidate + "_" + i;
        }
        if (!name.equals(candidate)) {
            messages.add("line " + node.lineNo + ": the inline enum of '" + node.name + "' is drawn as class '"
                    + name + "', because '" + candidate + "' is already taken");
        }
        enumType(name, node);
        return name;
    }

    private void importedType(SpecSketchGenerator.Node node) {
        Type box = typeFor(node.type);
        box.stereotype("external");
        box.note = node.importPath != null
                ? "imported from " + node.importPath
                : "imported - no path in the sketch yet";
    }

    // ------------------------------------------------------- what both tools do

    /** The header both languages write into the file, as comment text without their comment marker. */
    List<String> header(String sourceName, String tool) {
        List<String> lines = new ArrayList<>();
        lines.add("generated from " + sourceName + " by " + tool + " - change the sketch, not this file");
        // the reader has to know why the boxes carry no members
        if (view == View.STRUCTURE) {
            lines.add("structure view: the custom types and how they relate;"
                    + " attributes of a built-in type are left out");
        }
        return lines;
    }

    /** A diagram generator: SpecSketch lines in, one language's text out. */
    interface Renderer {
        String render(List<String> sketchLines, String sourceName, View view, List<String> messages);
    }

    /**
     * The command line both generators share: {@code <input.sketch> [<output> | -] [--structure]}.
     * Exit code 1 for a sketch (or a file) that cannot be read, 2 for a misuse of the arguments.
     */
    static void run(String[] args, String tool, String suffix, Renderer renderer) throws IOException {
        List<String> paths = new ArrayList<>();
        View view = View.FULL;
        for (String arg : args) {
            if (arg.equals(STRUCTURE_OPTION)) {
                view = View.STRUCTURE;
            } else if (arg.startsWith("-") && !arg.equals("-")) {
                System.err.println(tool + ": unknown option '" + arg + "'");
                usage(tool, suffix);
            } else {
                paths.add(arg);
            }
        }
        if (paths.isEmpty() || paths.size() > 2) {
            usage(tool, suffix);
        }
        Path input = Path.of(paths.get(0));
        try {
            if (!Files.isReadable(input)) {
                throw new SpecSketchGenerator.SpecException(
                        Files.exists(input) ? "input file is not readable" : "input file not found");
            }
            List<String> messages = new ArrayList<>();
            if (paths.size() == 2 && paths.get(1).equals("-")) {
                System.out.print(renderer.render(Files.readAllLines(input), fileNameOf(input), view, messages));
                report(tool, messages);
                return;
            }
            // default: save the diagram in the same path as the input file
            Path output = paths.size() == 2 ? Path.of(paths.get(1)) : defaultOutput(input, view, suffix);
            requireDistinct(input, output);
            String diagram = renderer.render(Files.readAllLines(input), fileNameOf(input), view, messages);
            boolean written = write(output, diagram, messages);
            report(tool, messages);
            if (written) {
                System.out.println(tool + ": " + input + " -> " + output);
            }
        } catch (SpecSketchGenerator.SpecException e) {
            System.err.println(tool + ": " + input + ": " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println(tool + ": " + input + ": " + e);
            System.exit(1);
        }
    }

    private static void usage(String tool, String suffix) {
        System.err.println("usage: java " + tool + ".java <input.sketch> [<output" + suffix + "> | -] ["
                + STRUCTURE_OPTION + "]");
        System.err.println("       (without an output path the diagram is saved next to the input file)");
        System.err.println("       " + STRUCTURE_OPTION + "  the type graph only: no attributes of a built-in type");
        System.exit(2);
    }

    private static void report(String tool, List<String> messages) {
        for (String message : messages) {
            System.err.println(tool + ": " + message);
        }
    }

    static String fileNameOf(Path input) {
        return input.getFileName().toString();
    }

    /**
     * Where a diagram lands without an explicit output path: next to the sketch. The two views get
     * two names, so asking for the other one never overwrites the one already there.
     */
    static Path defaultOutput(Path input, View view, String suffix) {
        String name = SpecSketchGenerator.baseNameOf(input) + (view == View.STRUCTURE ? "-structure" : "");
        return input.resolveSibling(name + suffix);
    }

    static void requireDistinct(Path input, Path output) {
        if (output.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())) {
            throw new SpecSketchGenerator.SpecException("output path would overwrite the input file: " + output);
        }
    }

    /**
     * Saves a diagram the way SpecSketchGenerator saves its YAML: the file belongs next to the
     * sketch and under version control, so one that differs is overwritten - it is generated, after
     * all - but never silently.
     *
     * @return whether the file was written (false if it was already up to date)
     */
    static boolean write(Path output, String diagram, List<String> messages) throws IOException {
        String existing = Files.exists(output) ? Files.readString(output) : null;
        if (diagram.equals(existing)) {
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
        Files.writeString(output, diagram);
        return true;
    }
}
