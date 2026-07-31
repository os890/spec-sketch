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
import java.util.List;

/**
 * SpecSketch -&gt; Mermaid class diagram (UML). Dependency-free, JDK 17+.
 *
 * The picture beside the document: SpecSketchGenerator turns a sketch into the OpenAPI file a
 * toolchain consumes, this one into the diagram a reader consumes. What the diagram contains -
 * which box, which member, which arrow, and what a view leaves out - is decided in
 * {@link SketchDiagram}, shared with SketchPlantUmlGenerator so the two languages cannot drift
 * apart. All that is left here is how mermaid spells it:
 * <ul>
 *   <li>the whole diagram is one indented block under {@code classDiagram}, comments start
 *       with {@code %%};</li>
 *   <li>a box is {@code class Name { … }}, or the bare {@code class Name} where it has nothing to
 *       show - {@code class Name {}} is not what mermaid expects;</li>
 *   <li>a stereotype is a body line, so mermaid shows exactly one: {@code <<enumeration>>} for an
 *       enum (mermaid has no enum form of its own), otherwise {@code <<request>>},
 *       {@code <<response>>} or {@code <<external>>};</li>
 *   <li>an association is {@code From --> "0..*" To : label}, inheritance {@code Base <|-- Sub};</li>
 *   <li>a note is {@code note for Name "text"} and lands in its own section at the end.</li>
 * </ul>
 * Members are written as they come: {@code +long id}, {@code +string tags [0..*]}. Mermaid reads a
 * member containing a parenthesis as a method and a brace as the end of the class body, which is
 * what the sketch's {@code {petId}} is drawn as {@code $path:petId} for.
 *
 * Usage: java SketchMermaidGenerator.java &lt;input.sketch&gt; [&lt;output.mmd&gt; | -] [--structure]
 *        With only the input path the diagram is saved next to the input file as
 *        &lt;input-basename&gt;.mmd - the suffix mermaid's own tooling uses - so sketch, YAML and
 *        diagram sit side by side under version control. The structure view (custom types and
 *        their relations, without the built-in fields) is written to
 *        &lt;input-basename&gt;-structure.mmd instead, so both views of one sketch can live next to
 *        each other. A second argument sets an explicit output path; '-' prints to stdout. An
 *        existing diagram is overwritten, but if its content differed it is reported: the file is a
 *        view of the sketch, so a hand edit is a change that would silently vanish otherwise.
 *        This generator needs SpecSketchGenerator and SketchDiagram, so the single-file source
 *        launcher only runs it on JDK 22+ (which compiles the sibling files from the same
 *        directory). On JDK 17-21 compile them once ('javac -d out src/main/tools/*.java', then
 *        'java -cp out SketchMermaidGenerator ...') or use the runnable jar Maven builds:
 *        'java -jar sketch-first/target/sketch-first-&lt;version&gt;-mermaid.jar my-api.sketch'.
 */
public final class SketchMermaidGenerator {

    /** The suffix mermaid's own tooling (mmdc, editors, IDE plugins) reads. */
    static final String MERMAID_SUFFIX = ".mmd";

    private static final String NAME = "SketchMermaidGenerator";

    private static final String INDENT = "    ";

    public static void main(String[] args) throws IOException {
        SketchDiagram.run(args, NAME, MERMAID_SUFFIX, SketchMermaidGenerator::generateMermaid);
    }

    /** Where a diagram lands without an explicit output path: next to the sketch. */
    static Path defaultOutput(Path input, SketchDiagram.View view) {
        return SketchDiagram.defaultOutput(input, view, MERMAID_SUFFIX);
    }

    /**
     * File-level rendering, laid out like SpecSketchGenerator.translate: an existing diagram that
     * differs is overwritten - it is generated, after all - but never silently.
     *
     * @return whether the diagram file was written (false if it was already up to date)
     */
    static boolean render(Path input, Path output, SketchDiagram.View view, List<String> messages)
            throws IOException {
        SketchDiagram.requireDistinct(input, output);
        String diagram = generateMermaid(Files.readAllLines(input), SketchDiagram.fileNameOf(input), view, messages);
        return SketchDiagram.write(output, diagram, messages);
    }

    /**
     * Entry point for tests and for {@link #main}: SpecSketch lines in, mermaid out.
     * {@code sourceName} is the sketch's file name, named in the diagram's header comment so a
     * reader of the picture knows which file to change; {@code messages} collects what the drawing
     * decided on the sketch's behalf (see {@link SketchDiagram#of}).
     */
    static String generateMermaid(List<String> lines, String sourceName, SketchDiagram.View view,
                                  List<String> messages) {
        SketchDiagram diagram = SketchDiagram.of(lines, sourceName, view, messages);
        StringBuilder sb = new StringBuilder("classDiagram\n");
        for (String line : diagram.header(sourceName, NAME)) {
            sb.append(INDENT).append("%% ").append(line).append("\n");
        }
        for (SketchDiagram.Type type : diagram.types()) {
            sb.append("\n").append(INDENT).append("class ").append(type.name);
            String stereotype = stereotypeOf(type);
            // an empty body would be '{}': a box with nothing to show is declared by its bare name
            if (stereotype == null && type.members.isEmpty()) {
                sb.append("\n");
                continue;
            }
            sb.append(" {\n");
            if (stereotype != null) {
                sb.append(INDENT.repeat(2)).append("<<").append(stereotype).append(">>\n");
            }
            for (String member : type.members) {
                sb.append(INDENT.repeat(2)).append(member).append("\n");
            }
            sb.append(INDENT).append("}\n");
        }
        List<String> inheritance = diagram.inheritance().stream()
                .map(step -> step.base() + " <|-- " + step.subtype()).toList();
        List<String> associations = diagram.associations().stream()
                .map(association -> association.from() + " --> \"" + association.multiplicity() + "\" "
                        + association.to() + " : " + association.label()).toList();
        List<String> notes = diagram.types().stream()
                .filter(type -> type.note != null)
                .map(type -> "note for " + type.name + " \"" + type.note + "\"").toList();
        appendSection(sb, inheritance);
        appendSection(sb, associations);
        appendSection(sb, notes);
        return sb.toString();
    }

    /**
     * Mermaid shows one annotation per class, so an enum spends it on saying that it is one: it has
     * no enum form of its own, and 'AVAILABLE, PENDING, SOLD' in a plain class would say nothing.
     */
    private static String stereotypeOf(SketchDiagram.Type type) {
        return type.kind == SketchDiagram.Kind.ENUM ? "enumeration" : type.stereotype;
    }

    private static void appendSection(StringBuilder sb, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n");
        for (String line : lines) {
            sb.append(INDENT).append(line).append("\n");
        }
    }
}
