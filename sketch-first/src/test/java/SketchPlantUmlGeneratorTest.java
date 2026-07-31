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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests the SpecSketch -> PlantUML rendering. What a diagram contains is decided in SketchDiagram
 * and covered by SketchMermaidGeneratorTest as well; what these tests are about is the language:
 * the {@code @startuml} frame, the stereotype on the declaration, PlantUML's own {@code enum} form,
 * a note attached below its box - and that nothing lands in a member which PlantUML reads as
 * something else than a member.
 *
 * There is no PlantUML parser on the classpath (the generators stay dependency-free), so every
 * diagram produced here goes through {@link #assertWellFormedPlantUml}, which re-reads the output
 * with the grammar this generator is allowed to emit and rejects everything else. The last test
 * checks the promise of the shared model from the other side: for one sketch, both languages have
 * to state the same boxes and the same arrows.
 */
class SketchPlantUmlGeneratorTest {

    private static String render(String... sketchLines) {
        return render(new ArrayList<>(), sketchLines);
    }

    /** For the cases that assert what the rendering decided on the sketch's behalf. */
    private static String render(List<String> messages, String... sketchLines) {
        return render(SketchDiagram.View.FULL, messages, sketchLines);
    }

    /** The type graph without the built-in members. */
    private static String renderStructure(String... sketchLines) {
        return render(SketchDiagram.View.STRUCTURE, new ArrayList<>(), sketchLines);
    }

    private static String render(SketchDiagram.View view, List<String> messages, String... sketchLines) {
        String diagram = SketchPlantUmlGenerator.generatePlantUml(
                List.of(sketchLines), "sample.sketch", view, messages);
        assertWellFormedPlantUml(diagram);
        return diagram;
    }

    // ------------------------------------------------------- plantuml grammar

    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]*";
    private static final Pattern BOX_LINE = Pattern.compile("(class|enum) (" + NAME + ")( <<[a-z]+>>)?( \\{)?");
    private static final Pattern INHERITANCE = Pattern.compile("(" + NAME + ") <\\|-- (" + NAME + ")");
    private static final Pattern ASSOCIATION = Pattern.compile(
            "(" + NAME + ") --> \"([0-9]+|[0-9]+\\.\\.([0-9]+|\\*))\" (" + NAME + ") : (\\S+)");
    private static final Pattern NOTE = Pattern.compile("note top of (" + NAME + ") : (.+)");
    private static final Pattern DIRECTIVE = Pattern.compile("hide empty members|skinparam \\w+ \\w+");

    /**
     * Reads the diagram back with the grammar the generator may emit. Anything PlantUML would read
     * differently than intended - a brace or parenthesis in a member (the body would end, or the
     * line would become a method), a member starting a comment or a directive, a box linked but
     * never declared - fails here.
     */
    private static void assertWellFormedPlantUml(String diagram) {
        List<String> lines = List.of(diagram.split("\n"));
        assertEquals("@startuml", lines.get(0), () -> "diagram must open the PlantUML frame:\n" + diagram);
        assertTrue(diagram.endsWith("\n@enduml\n"), () -> "diagram must close the frame:\n" + diagram);

        Set<String> declared = new LinkedHashSet<>();
        Set<String> referenced = new LinkedHashSet<>();
        String openBox = null;
        for (String line : lines.subList(1, lines.size() - 1)) {
            if (openBox != null) {
                if (line.equals("}")) {
                    openBox = null;
                    continue;
                }
                String box = openBox;
                assertFalse(line.isBlank(), () -> "blank line inside the body of '" + box + "':\n" + diagram);
                assertMemberIsSafe(line.trim(), diagram);
                continue;
            }
            if (line.isEmpty() || line.startsWith("'")) {
                continue; // separator or comment
            }
            Matcher box = BOX_LINE.matcher(line);
            Matcher inheritance = INHERITANCE.matcher(line);
            Matcher association = ASSOCIATION.matcher(line);
            Matcher note = NOTE.matcher(line);
            if (box.matches()) {
                assertTrue(declared.add(box.group(2)),
                        () -> "box '" + box.group(2) + "' is declared twice:\n" + diagram);
                openBox = box.group(4) != null ? box.group(2) : null;
            } else if (inheritance.matches()) {
                referenced.add(inheritance.group(1));
                referenced.add(inheritance.group(2));
            } else if (association.matches()) {
                referenced.add(association.group(1));
                referenced.add(association.group(4));
            } else if (note.matches()) {
                referenced.add(note.group(1));
            } else if (!DIRECTIVE.matcher(line).matches()) {
                fail("line is not valid PlantUML for this generator: '" + line + "'\n" + diagram);
            }
        }
        String unterminated = openBox;
        assertNull(unterminated, () -> "unterminated body for '" + unterminated + "':\n" + diagram);
        referenced.removeAll(declared);
        assertTrue(referenced.isEmpty(),
                () -> "linked but never declared as a box: " + referenced + "\n" + diagram);
    }

    private static void assertMemberIsSafe(String member, String diagram) {
        for (String forbidden : List.of("{", "}", "(", ")")) {
            assertFalse(member.contains(forbidden),
                    () -> "member '" + member + "' contains '" + forbidden + "', which PlantUML reads"
                            + " as something else than a member:\n" + diagram);
        }
        assertFalse(member.startsWith("'"), () -> "member '" + member + "' would be a comment:\n" + diagram);
        assertFalse(member.startsWith("@"), () -> "member '" + member + "' would be a directive:\n" + diagram);
        assertFalse(member.equals("--"), () -> "member '" + member + "' would be a separator:\n" + diagram);
    }

    /**
     * The declaration line of one box plus its body lines, the body un-indented - the declaration
     * alone for a box without one.
     */
    private static String box(String diagram, String name) {
        Pattern declaration = Pattern.compile("(class|enum) " + Pattern.quote(name) + "( <<[a-z]+>>)?( \\{)?");
        StringBuilder found = new StringBuilder();
        boolean inside = false;
        for (String line : diagram.split("\n")) {
            if (inside) {
                found.append(line.trim()).append("\n");
                if (line.equals("}")) {
                    return found.toString();
                }
            } else if (declaration.matcher(line).matches()) {
                found.append(line).append("\n");
                if (!line.endsWith(" {")) {
                    return found.toString();
                }
                inside = true;
            }
        }
        return fail("no box '" + name + "' in:\n" + diagram);
    }

    private static void assertMessage(List<String> messages, String fragment) {
        assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
                () -> "expected a message containing '" + fragment + "' but got: " + messages);
    }

    // ---------------------------------------------------------- boxes and members

    @Test
    void builtInPropertiesBecomeMembersSpelledAsTheSketchSpellsThem() {
        String diagram = render(
                "response (1) : Pet",
                "    id (1) : long",
                "    name (0 - 1) : string",
                "    tags (0 - *) : string",
                "    createdAt (1) : Instant");

        assertEquals("""
                class Pet <<response>> {
                +long id
                +string name [0..1]
                +string tags [0..*]
                +Instant createdAt
                }
                """, box(diagram, "Pet"));
    }

    @Test
    void theStereotypeSitsOnTheDeclarationSoABoxWithoutMembersNeedsNoBody() {
        // in mermaid the stereotype is a body line, so the same box has to carry braces there
        String diagram = render(
                "response (1) : Owner",
                "    contact (1) : Contact",
                "        email (1) : string");

        assertEquals("class Owner <<response>>\n", box(diagram, "Owner"));
        assertTrue(diagram.contains("Owner --> \"1\" Contact : contact"), diagram);
    }

    @Test
    void aBoxWithNeitherStereotypeNorMembersIsJustItsName() {
        String diagram = render(
                "response (1) : Res",
                "    owner (1) : Owner",
                "        contact (1) : Contact",
                "            email (1) : string");

        assertEquals("class Owner\n", box(diagram, "Owner"));
    }

    @Test
    void theRenderingDirectivesAreEmittedWithTheDiagram() {
        String diagram = render("response (1) : Res", "    id (1) : long");

        assertTrue(diagram.contains("\nhide empty members\n"), diagram);
        assertTrue(diagram.contains("\nskinparam classAttributeIconSize 0\n"), diagram);
        assertTrue(diagram.contains("' the two directives below affect the picture, not its content"), diagram);
    }

    @Test
    void theHeaderCommentNamesTheSketchToChange() {
        String diagram = SketchPlantUmlGenerator.generatePlantUml(
                List.of("response (1) : Res", "    id (1) : long"), "petstore.sketch",
                SketchDiagram.View.FULL, new ArrayList<>());

        assertWellFormedPlantUml(diagram);
        assertTrue(diagram.contains("' generated from petstore.sketch by SketchPlantUmlGenerator"), diagram);
    }

    // ------------------------------------------------------------------- enums

    @Test
    void enumsUsePlantUmlsOwnEnumFormInsteadOfAStereotype() {
        String diagram = render(
                "response (1) : Res",
                "    status (1) : enum PetStatus [AVAILABLE, PENDING, SOLD]",
                "    method (1) : enum [CARD, PAYPAL]");

        assertEquals("""
                enum PetStatus {
                AVAILABLE
                PENDING
                SOLD
                }
                """, box(diagram, "PetStatus"));
        assertFalse(diagram.contains("<<enumeration>>"), diagram);
        // an inline enum keeps the name openapi-generator derives for the inner Java enum
        assertEquals("enum ResMethodEnum {\nCARD\nPAYPAL\n}\n", box(diagram, "ResMethodEnum"));
        assertTrue(diagram.contains("Res --> \"1\" ResMethodEnum : method"), diagram);
    }

    @Test
    void anEnumThatIsAlsoAPartKeepsTheEnumFormAndTheStereotype() {
        // mermaid has one annotation slot per class and spends it on <<enumeration>>; here both fit
        String diagram = render(
                "response (1) : OrderStatus",              // the payload is that very enum
                "extra (1) : Res",
                "    status (1) : enum OrderStatus [PLACED, PAID]");

        assertEquals("enum OrderStatus <<response>> {\nPLACED\nPAID\n}\n", box(diagram, "OrderStatus"));
        // mermaid can only say one of the two
        String mermaid = SketchMermaidGenerator.generateMermaid(
                List.of("response (1) : OrderStatus", "extra (1) : Res",
                        "    status (1) : enum OrderStatus [PLACED, PAID]"),
                "sample.sketch", SketchDiagram.View.FULL, new ArrayList<>());
        assertTrue(mermaid.contains("<<enumeration>>"), mermaid);
        assertFalse(mermaid.contains("<<response>>"), mermaid);
    }

    @Test
    void anEnumWrittenOnThePartLineBecomesItsBody() {
        // the part declares the payload instead of naming a type, so it gets a box of its own
        String diagram = render("response (1) : enum OrderStatus [PLACED, PAID]");

        assertEquals("class response <<response>>\n", box(diagram, "response"));
        assertEquals("enum OrderStatus {\nPLACED\nPAID\n}\n", box(diagram, "OrderStatus"));
        assertTrue(diagram.contains("response --> \"1\" OrderStatus : body"), diagram);
    }

    @Test
    void aValueTypeOtherThanStringIsStatedInANoteBelowTheEnum() {
        String diagram = render(
                "response (1) : Res",
                "    rating (0 - 1) : enum:int Stars [1, 2, 3]",
                "    country (1) : enum:String [AT, DE]");

        assertTrue(diagram.contains("""
                enum Stars {
                  1
                  2
                  3
                }
                note top of Stars : values of type int
                """), diagram);
        assertFalse(diagram.contains("values of type String"), diagram);
    }

    @Test
    void aSynthesizedEnumNameThatIsTakenIsDisambiguatedAndReported() {
        List<String> messages = new ArrayList<>();
        String diagram = render(messages,
                "response (1) : Pet",
                "    kind (1) : enum [DOG, CAT]",
                "    inner (1) : PetKindEnum",
                "        marker (1) : string");

        assertEquals("enum PetKindEnum_2 {\nDOG\nCAT\n}\n", box(diagram, "PetKindEnum_2"));
        assertMessage(messages, "line 2: the inline enum of 'kind' is drawn as class 'PetKindEnum_2'");
    }

    // ------------------------------------------------- inheritance, parameters

    @Test
    void subtypesBecomeInheritanceArrowsAndTheDiscriminatorStaysVisible() {
        String diagram = render(
                "response (1) : Res",
                "    events (0 - *) : PetEvent",
                "        eventType (1) : discriminator",
                "        extended by VaccinationEvent",
                "            vaccine (1) : string",
                "            extended by BoosterEvent",
                "                dose (1) : int");

        assertTrue(diagram.contains("PetEvent <|-- VaccinationEvent"), diagram);
        assertTrue(diagram.contains("VaccinationEvent <|-- BoosterEvent"), diagram);
        assertEquals("class PetEvent {\n+discriminator eventType\n}\n", box(diagram, "PetEvent"));
        assertEquals("class VaccinationEvent {\n+string vaccine\n}\n", box(diagram, "VaccinationEvent"));
    }

    @Test
    void parametersAndHeadersKeepTheSigilThatDeclaresThem() {
        String diagram = render(
                "request (1) : Req",
                "    @X-Client-Id (1) : uuid",
                "    {petId} (1) : long",
                "    ?dryRun (0 - 1) : boolean",
                "    $cookie:session (0 - 1) : uuid",
                "    $couponSource (0 - 1) : string",
                "    name (1) : string",
                "response (1) : Res",
                "    id (1) : long");

        assertEquals("""
                class Req <<request>> {
                +uuid @X-Client-Id
                +long $path:petId
                +boolean ?dryRun [0..1]
                +uuid $cookie:session [0..1]
                +string $couponSource [0..1]
                +string name
                }
                """, box(diagram, "Req"));
        // a brace in a member would end the body PlantUML writes in braces, just as in mermaid
        assertFalse(diagram.contains("{petId}"), diagram);
    }

    // ----------------------------------------------------------------- imports

    @Test
    void anImportedTypeCarriesItsFileInANoteBelowTheBox() {
        String diagram = render(
                "import Money from \"common-types.yaml\"",
                "response (1) : Invoice",
                "    total (1) : Money");

        assertTrue(diagram.contains("""
                class Money <<external>>
                note top of Money : imported from common-types.yaml
                """), diagram);
        assertTrue(diagram.contains("Invoice --> \"1\" Money : total"), diagram);
    }

    @Test
    void anImportWithoutAPathSaysSoInItsNote() {
        String diagram = render(
                "import Money",
                "response (1) : Invoice",
                "    total (1) : Money");

        assertTrue(diagram.contains("note top of Money : imported - no path in the sketch yet"), diagram);
    }

    // ---------------------------------------------------------- structure view

    @Test
    void theStructureViewKeepsTheTypeGraphAndDropsTheBuiltInMembers() {
        String[] sketch = {
                "response (1) : PetPageResponse",
                "    @X-Total (1) : int",
                "    pets (0 - *) : Pet",
                "        id (1) : long",
                "        status (1) : enum PetStatus [AVAILABLE, SOLD]",
                "        events (0 - *) : PetEvent",
                "            eventType (1) : discriminator",
                "            extended by AdoptionEvent",
                "                newOwner (1) : string",
                "    totalCount (1) : int"};

        String diagram = renderStructure(sketch);

        assertEquals("class PetPageResponse <<response>>\n", box(diagram, "PetPageResponse"));
        assertEquals("class Pet\n", box(diagram, "Pet"));
        assertEquals("class AdoptionEvent\n", box(diagram, "AdoptionEvent"));
        assertFalse(diagram.contains("+long id"), diagram);
        assertFalse(diagram.contains("@X-Total"), diagram);
        // what a type IS stays: the enum's values and the discriminator
        assertEquals("enum PetStatus {\nAVAILABLE\nSOLD\n}\n", box(diagram, "PetStatus"));
        assertEquals("class PetEvent {\n+discriminator eventType\n}\n", box(diagram, "PetEvent"));
        assertTrue(diagram.contains("PetEvent <|-- AdoptionEvent"), diagram);
        assertTrue(diagram.contains("PetPageResponse --> \"0..*\" Pet : pets"), diagram);
        // the full view of the same sketch has the fields
        assertTrue(render(sketch).contains("+long id"));
    }

    @Test
    void theStructureViewSaysSoInItsHeader() {
        String diagram = renderStructure("response (1) : Res", "    id (1) : long");

        assertTrue(diagram.contains("' structure view: the custom types and how they relate;"
                + " attributes of a built-in type are left out"), diagram);
    }

    @Test
    void theTwoViewsGetTwoDefaultFileNames() {
        Path sketch = Path.of("api", "petstore.sketch");

        assertEquals(Path.of("api", "petstore.puml"),
                SketchPlantUmlGenerator.defaultOutput(sketch, SketchDiagram.View.FULL));
        assertEquals(Path.of("api", "petstore-structure.puml"),
                SketchPlantUmlGenerator.defaultOutput(sketch, SketchDiagram.View.STRUCTURE));
    }

    // ------------------------------------------------- rules and the two languages

    @Test
    void aSketchTheTranslationRejectsYieldsNoDiagramEither() {
        assertSpecFailure(() -> render(
                "response (1) : Res",
                "    item (1) : Missing"), "line 2: type 'Missing' is used but never defined");
        assertSpecFailure(() -> render("request (1) : Req", "    id (1) : long"),
                "missing top-level 'response' definition");
    }

    /**
     * The reason both generators share SketchDiagram: for one sketch they have to state the same
     * boxes and the same arrows. Only the spelling may differ - which is what the rest of this test
     * class is about.
     */
    @Test
    void bothLanguagesDrawTheSameBoxesAndArrows() {
        List<String> sketch = List.of(
                "import Money from \"common-types.yaml\"",
                "request (1) : CreatePetRequest",
                "    ?status (0 - 1) : enum PetStatus [AVAILABLE, SOLD]",
                "    name (1) : string",
                "response (1) : PetPageResponse",
                "    pets (0 - *) : Pet",
                "        id (1) : long",
                "        price (0 - 1) : Money",
                "        kind (1) : enum [DOG, CAT]",
                "        events (0 - *) : PetEvent",
                "            eventType (1) : discriminator",
                "            extended by AdoptionEvent",
                "                newOwner (1) : string",
                "    totalCount (1) : int");

        for (SketchDiagram.View view : SketchDiagram.View.values()) {
            String plantUml = SketchPlantUmlGenerator.generatePlantUml(sketch, "s.sketch", view, new ArrayList<>());
            String mermaid = SketchMermaidGenerator.generateMermaid(sketch, "s.sketch", view, new ArrayList<>());
            assertWellFormedPlantUml(plantUml);

            assertEquals(names(mermaid, "class (" + NAME + ")"), names(plantUml, "(?:class|enum) (" + NAME + ")"),
                    () -> "different boxes for the " + view + " view:\n" + mermaid + "\n" + plantUml);
            assertEquals(lines(mermaid, ASSOCIATION), lines(plantUml, ASSOCIATION),
                    () -> "different associations for the " + view + " view");
            assertEquals(lines(mermaid, INHERITANCE), lines(plantUml, INHERITANCE),
                    () -> "different inheritance for the " + view + " view");
        }
    }

    private static Set<String> names(String diagram, String pattern) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(pattern).matcher(diagram);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static List<String> lines(String diagram, Pattern pattern) {
        List<String> found = new ArrayList<>();
        for (String line : diagram.split("\n")) {
            if (pattern.matcher(line.trim()).matches()) {
                found.add(line.trim());
            }
        }
        return found;
    }

    // -------------------------------------------------------------- file level

    @Test
    void theDiagramIsWrittenNextToTheSketchWithThePlantUmlSuffix(@TempDir Path dir) throws IOException {
        Path sketch = dir.resolve("petstore.sketch");
        Files.write(sketch, List.of("response (1) : Pet", "    id (1) : long"));
        List<String> messages = new ArrayList<>();

        boolean written = SketchPlantUmlGenerator.render(
                sketch, dir.resolve("petstore.puml"), SketchDiagram.View.FULL, messages);

        assertTrue(written);
        String diagram = Files.readString(dir.resolve("petstore.puml"));
        assertWellFormedPlantUml(diagram);
        assertTrue(diagram.contains("+long id"), diagram);
        assertTrue(messages.isEmpty(), () -> "unexpected messages: " + messages);
    }

    @Test
    void anUnchangedDiagramFileIsLeftAlone(@TempDir Path dir) throws IOException {
        Path sketch = dir.resolve("petstore.sketch");
        Path diagram = dir.resolve("petstore.puml");
        Files.write(sketch, List.of("response (1) : Pet", "    id (1) : long"));
        SketchPlantUmlGenerator.render(sketch, diagram, SketchDiagram.View.FULL, new ArrayList<>());

        List<String> messages = new ArrayList<>();
        assertFalse(SketchPlantUmlGenerator.render(sketch, diagram, SketchDiagram.View.FULL, messages));

        assertEquals(List.of(diagram + " is already up to date"), messages);
    }

    @Test
    void overwritingAModifiedDiagramFileIsReported(@TempDir Path dir) throws IOException {
        Path sketch = dir.resolve("petstore.sketch");
        Path diagram = dir.resolve("petstore.puml");
        Files.write(sketch, List.of("response (1) : Pet", "    id (1) : long"));
        SketchPlantUmlGenerator.render(sketch, diagram, SketchDiagram.View.FULL, new ArrayList<>());

        Files.writeString(diagram, Files.readString(diagram).replace("@enduml", "' HAND-EDITED\n@enduml"));
        List<String> messages = new ArrayList<>();
        SketchPlantUmlGenerator.render(sketch, diagram, SketchDiagram.View.FULL, messages);

        assertFalse(Files.readString(diagram).contains("HAND-EDITED"));
        assertMessage(messages, "was overwritten");
    }

    @Test
    void writingOverTheSketchItselfIsRejected(@TempDir Path dir) throws IOException {
        Path sketch = dir.resolve("petstore.sketch");
        Files.write(sketch, List.of("response (1) : Pet", "    id (1) : long"));

        SpecSketchGenerator.SpecException e = assertThrows(SpecSketchGenerator.SpecException.class,
                () -> SketchPlantUmlGenerator.render(sketch, sketch, SketchDiagram.View.FULL, new ArrayList<>()));
        assertTrue(e.getMessage().contains("would overwrite the input file"));
    }

    private static void assertSpecFailure(org.junit.jupiter.api.function.Executable executable, String messagePart) {
        SpecSketchGenerator.SpecException e = assertThrows(SpecSketchGenerator.SpecException.class, executable);
        assertTrue(e.getMessage().contains(messagePart),
                () -> "expected message to contain '" + messagePart + "' but was: " + e.getMessage());
    }
}
