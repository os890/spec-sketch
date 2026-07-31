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
 * SpecSketch -&gt; PlantUML class diagram. Dependency-free, JDK 17+.
 *
 * The same picture as SketchMermaidGenerator draws, in the other language. What the diagram
 * contains - which box, which member, which arrow, and what a view leaves out - is decided in
 * {@link SketchDiagram} and shared by both, so neither language can drift away from the other; all
 * that is left here is how PlantUML spells it:
 * <ul>
 *   <li>the file is wrapped in {@code @startuml} / {@code @enduml}, comments start with an
 *       apostrophe, and statements are not indented under anything;</li>
 *   <li>a stereotype sits on the declaration ({@code class Pet <<response>>}) instead of inside the
 *       body, so a box that only carries one needs no body at all;</li>
 *   <li>PlantUML has {@code enum} as a form of its own, which frees the stereotype: an enum that is
 *       also a part reads {@code enum OrderStatus <<response>>}, something mermaid's single
 *       annotation slot cannot show;</li>
 *   <li>a note is attached where it belongs - {@code note top of Money : imported from …} directly
 *       below its box - rather than collected in a section;</li>
 *   <li>associations and inheritance are spelled as in mermaid ({@code From --> "0..*" To : label},
 *       {@code Base <|-- Sub}); the two languages agree here.</li>
 * </ul>
 * Two rendering directives are emitted with the diagram: 'hide empty members' (a box without
 * members is drawn as the plain box it is, which the structure view consists of) and
 * 'skinparam classAttributeIconSize 0' (members appear as written - '+long id' - instead of with
 * PlantUML's visibility icons). Both affect the picture only, never its content.
 *
 * Usage: java SketchPlantUmlGenerator.java &lt;input.sketch&gt; [&lt;output.puml&gt; | -] [--structure]
 *        With only the input path the diagram is saved next to the input file as
 *        &lt;input-basename&gt;.puml, so sketch, YAML and diagram sit side by side under version
 *        control. The structure view (custom types and their relations, without the built-in
 *        fields) is written to &lt;input-basename&gt;-structure.puml instead, so both views of one
 *        sketch can live next to each other. A second argument sets an explicit output path; '-'
 *        prints to stdout. An existing diagram is overwritten, but if its content differed it is
 *        reported: the file is a view of the sketch, so a hand edit is a change that would silently
 *        vanish otherwise.
 *        This generator needs SpecSketchGenerator and SketchDiagram, so the single-file source
 *        launcher only runs it on JDK 22+ (which compiles the sibling files from the same
 *        directory). On JDK 17-21 compile them once ('javac -d out src/main/tools/*.java', then
 *        'java -cp out SketchPlantUmlGenerator ...') or use the runnable jar Maven builds:
 *        'java -jar sketch-first/target/sketch-first-&lt;version&gt;-plantuml.jar my-api.sketch'.
 */
public final class SketchPlantUmlGenerator {

    /** The suffix PlantUML's own tooling (the jar, the server, the IDE plugins) reads. */
    static final String PLANTUML_SUFFIX = ".puml";

    private static final String NAME = "SketchPlantUmlGenerator";

    private static final String INDENT = "  ";

    public static void main(String[] args) throws IOException {
        SketchDiagram.run(args, NAME, PLANTUML_SUFFIX, SketchPlantUmlGenerator::generatePlantUml);
    }

    /** Where a diagram lands without an explicit output path: next to the sketch. */
    static Path defaultOutput(Path input, SketchDiagram.View view) {
        return SketchDiagram.defaultOutput(input, view, PLANTUML_SUFFIX);
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
        String diagram = generatePlantUml(Files.readAllLines(input), SketchDiagram.fileNameOf(input), view, messages);
        return SketchDiagram.write(output, diagram, messages);
    }

    /**
     * Entry point for tests and for {@link #main}: SpecSketch lines in, PlantUML out.
     * {@code sourceName} is the sketch's file name, named in the diagram's header comment so a
     * reader of the picture knows which file to change; {@code messages} collects what the drawing
     * decided on the sketch's behalf (see {@link SketchDiagram#of}).
     */
    static String generatePlantUml(List<String> lines, String sourceName, SketchDiagram.View view,
                                   List<String> messages) {
        SketchDiagram diagram = SketchDiagram.of(lines, sourceName, view, messages);
        StringBuilder sb = new StringBuilder("@startuml\n");
        for (String line : diagram.header(sourceName, NAME)) {
            sb.append("' ").append(line).append("\n");
        }
        sb.append("' the two directives below affect the picture, not its content\n");
        sb.append("hide empty members\n");
        sb.append("skinparam classAttributeIconSize 0\n");
        for (SketchDiagram.Type type : diagram.types()) {
            sb.append("\n").append(type.kind == SketchDiagram.Kind.ENUM ? "enum " : "class ").append(type.name);
            if (type.stereotype != null) {
                sb.append(" <<").append(type.stereotype).append(">>");
            }
            if (type.hasBody()) {
                sb.append(" {\n");
                for (String member : type.members) {
                    sb.append(INDENT).append(member).append("\n");
                }
                sb.append("}\n");
            } else {
                sb.append("\n"); // a box with nothing to show needs no body in PlantUML
            }
            if (type.note != null) {
                sb.append("note top of ").append(type.name).append(" : ").append(type.note).append("\n");
            }
        }
        appendSection(sb, diagram.inheritance().stream()
                .map(step -> step.base() + " <|-- " + step.subtype()).toList());
        appendSection(sb, diagram.associations().stream()
                .map(association -> association.from() + " --> \"" + association.multiplicity() + "\" "
                        + association.to() + " : " + association.label()).toList());
        return sb.append("\n@enduml\n").toString();
    }

    private static void appendSection(StringBuilder sb, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
    }
}
