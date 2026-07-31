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
 * Tests the SpecSketch -> Mermaid rendering: which part of a sketch becomes a class, an attribute,
 * an association, an inheritance arrow or an enumeration - and that a sketch the OpenAPI
 * translation rejects yields no diagram either.
 *
 * There is no mermaid parser on the classpath (both generators stay dependency-free, and a
 * headless browser is not a test dependency), so every diagram produced here goes through
 * {@link #assertWellFormedMermaid}: it re-reads the output with the grammar this generator is
 * allowed to emit - class blocks, {@code <<annotation>>} lines, members, {@code <|--}
 * inheritance, {@code -->} associations, notes - and rejects anything mermaid would misread. That
 * is what pins down the escaping rules the generator has to obey, above all that no member text
 * may contain a brace (it would end the class body) or a parenthesis (mermaid would read the line
 * as a method), which is why a path parameter is drawn as '$path:petId' instead of '{petId}'.
 */
class SketchMermaidGeneratorTest {

    private static String render(String... sketchLines) {
        return render(new ArrayList<>(), sketchLines);
    }

    /** For the cases that assert what the rendering decided on the sketch's behalf. */
    private static String render(List<String> messages, String... sketchLines) {
        return render(SketchDiagram.View.FULL, messages, sketchLines);
    }

    /** The type graph without the built-in attributes. */
    private static String renderStructure(String... sketchLines) {
        return render(SketchDiagram.View.STRUCTURE, new ArrayList<>(), sketchLines);
    }

    private static String render(SketchDiagram.View view, List<String> messages, String... sketchLines) {
        String diagram = SketchMermaidGenerator.generateMermaid(
                List.of(sketchLines), "sample.sketch", view, messages);
        assertWellFormedMermaid(diagram);
        return diagram;
    }

    // --------------------------------------------------------- mermaid grammar

    private static final Pattern CLASS_LINE = Pattern.compile("class ([A-Za-z_][A-Za-z0-9_]*)( \\{)?");
    private static final Pattern INHERITANCE = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*) <\\|-- ([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern ASSOCIATION = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*) --> \"([0-9]+|[0-9]+\\.\\.([0-9]+|\\*))\" ([A-Za-z_][A-Za-z0-9_]*) : (\\S+)");
    private static final Pattern NOTE = Pattern.compile("note for ([A-Za-z_][A-Za-z0-9_]*) \"([^\"]*)\"");
    private static final Pattern ANNOTATION = Pattern.compile("<<[a-z]+>>");

    /**
     * Reads the diagram back with the grammar the generator may emit. Everything mermaid would
     * read differently than intended - a brace or parenthesis in a member, a second annotation, a
     * blank line inside a class body, a class linked but never declared - fails here.
     */
    private static void assertWellFormedMermaid(String diagram) {
        List<String> lines = List.of(diagram.split("\n"));
        assertEquals("classDiagram", lines.get(0), () -> "diagram must open with the type:\n" + diagram);
        assertTrue(diagram.endsWith("\n"), () -> "diagram must end with a newline:\n" + diagram);

        Set<String> declared = new LinkedHashSet<>();
        Set<String> referenced = new LinkedHashSet<>();
        String openClass = null;
        int members = 0;
        for (String raw : lines.subList(1, lines.size())) {
            String line = raw.trim();
            if (openClass != null) {
                if (line.equals("}")) {
                    openClass = null;
                    continue;
                }
                assertFalse(line.isEmpty(), () -> "blank line inside a class body:\n" + diagram);
                assertMemberIsSafe(line, members++ == 0, diagram);
                continue;
            }
            members = 0;
            if (line.isEmpty() || line.startsWith("%%")) {
                continue; // separator or comment
            }
            Matcher classLine = CLASS_LINE.matcher(line);
            Matcher inheritance = INHERITANCE.matcher(line);
            Matcher association = ASSOCIATION.matcher(line);
            Matcher note = NOTE.matcher(line);
            if (classLine.matches()) {
                assertTrue(declared.add(classLine.group(1)),
                        () -> "class '" + classLine.group(1) + "' is declared twice:\n" + diagram);
                openClass = classLine.group(2) != null ? classLine.group(1) : null;
            } else if (inheritance.matches()) {
                referenced.add(inheritance.group(1));
                referenced.add(inheritance.group(2));
            } else if (association.matches()) {
                referenced.add(association.group(1));
                referenced.add(association.group(4));
            } else if (note.matches()) {
                referenced.add(note.group(1));
            } else {
                fail("line is not valid mermaid for this generator: '" + line + "'\n" + diagram);
            }
        }
        String unterminated = openClass;
        assertNull(unterminated, () -> "unterminated class body for '" + unterminated + "':\n" + diagram);
        referenced.removeAll(declared);
        assertTrue(referenced.isEmpty(),
                () -> "linked but never declared as a class: " + referenced + "\n" + diagram);
    }

    private static void assertMemberIsSafe(String member, boolean first, String diagram) {
        if (ANNOTATION.matcher(member).matches()) {
            assertTrue(first, () -> "the <<annotation>> must be the first line of a class body:\n" + diagram);
            return;
        }
        for (String forbidden : List.of("{", "}", "(", ")", "<<", ">>")) {
            assertFalse(member.contains(forbidden),
                    () -> "member '" + member + "' contains '" + forbidden + "', which mermaid reads"
                            + " as something else than a member:\n" + diagram);
        }
    }

    /** The body lines of one class block, annotation included - '' for a class without a body. */
    private static String classBlock(String diagram, String name) {
        StringBuilder body = new StringBuilder();
        boolean inside = false;
        for (String raw : diagram.split("\n")) {
            String line = raw.trim();
            if (inside) {
                if (line.equals("}")) {
                    return body.toString();
                }
                body.append(line).append("\n");
            } else if (line.equals("class " + name + " {")) {
                inside = true;
            } else if (line.equals("class " + name)) {
                return "";
            }
        }
        return fail("no class '" + name + "' in:\n" + diagram);
    }

    private static void assertMessage(List<String> messages, String fragment) {
        assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
                () -> "expected a message containing '" + fragment + "' but got: " + messages);
    }

    // ------------------------------------------------------ attributes / types

    @Test
    void builtInPropertiesBecomeAttributesSpelledAsTheSketchSpellsThem() {
        String diagram = render(
                "response (1) : Pet",
                "    id (1) : long",
                "    name (1) : string",
                "    createdAt (1) : Instant",
                "    price (1) : BigDecimal");

        assertEquals("""
                <<response>>
                +long id
                +string name
                +Instant createdAt
                +BigDecimal price
                """, classBlock(diagram, "Pet"));
    }

    @Test
    void occurrenceBecomesAUmlMultiplicityOnlyWhereItIsNotExactlyOne() {
        String diagram = render(
                "response (1) : Res",
                "    required (1) : string",
                "    optional (0 - 1) : string",
                "    repeatable (0 - *) : string",
                "    atLeastOne (1 - *) : string",
                "    exactlyTwo (2) : string",
                "    upToThree (0 - 3) : string",
                "    twoToFive (2 - 5) : string");

        assertEquals("""
                <<response>>
                +string required
                +string optional [0..1]
                +string repeatable [0..*]
                +string atLeastOne [1..*]
                +string exactlyTwo [2]
                +string upToThree [0..3]
                +string twoToFive [2..5]
                """, classBlock(diagram, "Res"));
    }

    @Test
    void validationAttributesStayOutOfTheDiagram() {
        // they describe the wire format, not the model - the YAML carries them
        String diagram = render(
                "response (1) : Res",
                "    name (1) : string {minLength: 1, maxLength: 100}",
                "    age (0 - 1) : int {min: 0, max: 150}");

        assertEquals("""
                <<response>>
                +string name
                +int age [0..1]
                """, classBlock(diagram, "Res"));
    }

    @Test
    void namedTypesBecomeAssociationsWithTheirMultiplicityAndPropertyName() {
        String diagram = render(
                "response (1) : PetPageResponse",
                "    pets (0 - *) : Pet",
                "        id (1) : long",
                "    favorite (0 - 1) : Pet",
                "    totalCount (1) : int");

        assertTrue(diagram.contains("PetPageResponse --> \"0..*\" Pet : pets"), diagram);
        assertTrue(diagram.contains("PetPageResponse --> \"0..1\" Pet : favorite"), diagram);
        // the type is defined once, so it is one class - referenced twice
        assertEquals("+long id\n", classBlock(diagram, "Pet"));
        assertEquals("<<response>>\n+int totalCount\n", classBlock(diagram, "PetPageResponse"));
    }

    @Test
    void aRecursiveTypeDrawsTheSelfAssociationItIs() {
        String diagram = render(
                "response (1) : Category",
                "    id (1) : long",
                "    children (0 - *) : Category");

        assertTrue(diagram.contains("Category --> \"0..*\" Category : children"), diagram);
    }

    @Test
    void aClassWhoseEveryPropertyIsAnAssociationIsDeclaredWithoutABody() {
        // an empty '{}' body is not what mermaid expects, so the bare class name declares it
        String diagram = render(
                "response (1) : Owner",
                "    contact (1) : Contact",
                "        email (1) : string");

        assertTrue(diagram.contains("\n    class Contact {\n"), diagram);
        assertEquals("<<response>>\n", classBlock(diagram, "Owner"));
    }

    @Test
    void requestAndResponseCarryTheStereotypeOfTheirPart() {
        String diagram = render(
                "request (1) : CreatePetRequest",
                "    name (1) : string",
                "response (1) : Pet",
                "    id (1) : long");

        assertTrue(classBlock(diagram, "CreatePetRequest").startsWith("<<request>>\n"), diagram);
        assertTrue(classBlock(diagram, "Pet").startsWith("<<response>>\n"), diagram);
    }

    // ------------------------------------------------------------------- enums

    @Test
    void aNamedEnumBecomesOneEnumerationClassEveryUsageLinksTo() {
        String diagram = render(
                "request (1) : Req",
                "    status (1) : enum PetStatus [AVAILABLE, PENDING, SOLD]",
                "response (1) : Pet",
                "    status (0 - 1) : PetStatus");

        assertEquals("""
                <<enumeration>>
                AVAILABLE
                PENDING
                SOLD
                """, classBlock(diagram, "PetStatus"));
        assertTrue(diagram.contains("Req --> \"1\" PetStatus : status"), diagram);
        assertTrue(diagram.contains("Pet --> \"0..1\" PetStatus : status"), diagram);
    }

    @Test
    void anInlineEnumIsDrawnUnderTheNameTheDtoGeneratorDerivesForIt() {
        // openapi-generator turns an inline enum into an inner enum of the surrounding class
        String diagram = render(
                "response (1) : Payment",
                "    method (1) : enum [CARD, PAYPAL, INVOICE]");

        assertEquals("<<enumeration>>\nCARD\nPAYPAL\nINVOICE\n", classBlock(diagram, "PaymentMethodEnum"));
        assertTrue(diagram.contains("Payment --> \"1\" PaymentMethodEnum : method"), diagram);
    }

    @Test
    void aSynthesizedEnumNameThatIsTakenIsDisambiguatedAndReported() {
        List<String> messages = new ArrayList<>();
        String diagram = render(messages,
                "response (1) : Pet",
                "    kind (1) : enum [DOG, CAT]",
                "    inner (1) : PetKindEnum",   // a real type under the synthesized name
                "        marker (1) : string");

        assertEquals("<<enumeration>>\nDOG\nCAT\n", classBlock(diagram, "PetKindEnum_2"));
        assertEquals("+string marker\n", classBlock(diagram, "PetKindEnum"));
        assertMessage(messages, "line 2: the inline enum of 'kind' is drawn as class 'PetKindEnum_2'");
    }

    @Test
    void aValueTypeOtherThanStringIsStatedInANote() {
        // 'enum [1, 2]' and 'enum:int [1, 2]' list the same values but are different enums
        String diagram = render(
                "response (1) : Res",
                "    rating (0 - 1) : enum:int Stars [1, 2, 3]",
                "    country (1) : enum:String [AT, DE]");

        assertEquals("<<enumeration>>\n1\n2\n3\n", classBlock(diagram, "Stars"));
        assertTrue(diagram.contains("note for Stars \"values of type int\""), diagram);
        // 'String' is the default value type spelled explicitly - nothing to state
        assertFalse(diagram.contains("values of type String"), diagram);
    }

    // ------------------------------------------------------------- inheritance

    @Test
    void subtypesBecomeInheritanceArrowsCarryingOnlyTheirOwnProperties() {
        String diagram = render(
                "response (1) : Res",
                "    shipments (0 - *) : Shipment",
                "        shipmentType (1) : discriminator",
                "        trackingId (1) : uuid",
                "        extended by ParcelShipment",
                "            weightKg (1) : double",
                "            extended by ExpressParcel",
                "                guaranteedBy (1) : Instant",
                "        extended by PickupShipment",
                "            storeId (1) : long");

        assertTrue(diagram.contains("Shipment <|-- ParcelShipment"), diagram);
        assertTrue(diagram.contains("ParcelShipment <|-- ExpressParcel"), diagram);
        assertTrue(diagram.contains("Shipment <|-- PickupShipment"), diagram);
        // like the allOf composition of the YAML, a subtype only holds what it adds
        assertEquals("+double weightKg\n", classBlock(diagram, "ParcelShipment"));
        assertEquals("+Instant guaranteedBy\n", classBlock(diagram, "ExpressParcel"));
    }

    @Test
    void theDiscriminatorStaysVisibleAsTheTypeOfItsProperty() {
        String diagram = render(
                "response (1) : Res",
                "    events (0 - *) : PetEvent",
                "        eventType (1) : discriminator",
                "        extended by AdoptionEvent",
                "            newOwner (1) : string");

        assertEquals("+discriminator eventType\n", classBlock(diagram, "PetEvent"));
    }

    // ------------------------------------------------- parameters and headers

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
                "response (1) : Res2",
                "    @X-Rate-Limit (1) : int",
                "    id (1) : long");

        assertEquals("""
                <<request>>
                +uuid @X-Client-Id
                +long $path:petId
                +boolean ?dryRun [0..1]
                +uuid $cookie:session [0..1]
                +string $couponSource [0..1]
                +string name
                """, classBlock(diagram, "Req"));
        assertEquals("<<response>>\n+int @X-Rate-Limit\n+long id\n", classBlock(diagram, "Res2"));
    }

    @Test
    void aPathParameterUsesTheLongFormBecauseBracesWouldEndTheClassBody() {
        String diagram = render(
                "request (1) : Req",
                "    {petId} (1) : long",
                "    name (1) : string",
                "response (1) : Res",
                "    id (1) : long");

        assertTrue(diagram.contains("+long $path:petId"), diagram);
        assertFalse(diagram.contains("{petId}"), diagram);
    }

    @Test
    void aParameterOfANamedTypeBecomesAnAssociationLabelledWithItsSigilName() {
        String diagram = render(
                "request (1) : Req",
                "    ?status (0 - 1) : enum PetStatus [AVAILABLE, SOLD]",
                "    ?channel (0 - *) : enum [WEB, APP]",
                "    name (1) : string",
                "response (1) : Res",
                "    id (1) : long");

        assertTrue(diagram.contains("Req --> \"0..1\" PetStatus : ?status"), diagram);
        // the synthesized name uses the plain property name, not the sigil
        assertTrue(diagram.contains("Req --> \"0..*\" ReqChannelEnum : ?channel"), diagram);
    }

    @Test
    void aParameterOnlyRequestDrawsItsParametersAndNoBody() {
        // such a request has no schema in the YAML either, so its type name carries nothing
        String diagram = render(
                "request (1) : GetPetParams",
                "    {petId} (1) : long",
                "    ?status (0 - 1) : string",
                "response (1) : Pet",
                "    id (1) : long");

        assertEquals("<<request>>\n+long $path:petId\n+string ?status [0..1]\n",
                classBlock(diagram, "request"));
        assertFalse(diagram.contains("GetPetParams"), diagram);
    }

    @Test
    void aPartWhoseTypeIsNoClassOfItsOwnKeepsItsPayloadAsABodyMember() {
        // 'class string' would be nonsense, and the header still needs a place to live
        String diagram = render(
                "response (1) : string",
                "    @X-Total (1) : int");

        assertEquals("<<response>>\n+string body\n+int @X-Total\n", classBlock(diagram, "response"));
    }

    @Test
    void aResponseThatOnlyReferencesATypeAnnotatesThatVeryClass() {
        String diagram = render(
                "response (1) : Pet",
                "    @X-Total (0 - 1) : int",
                "shared (1) : Pet",
                "    id (1) : long");

        assertEquals("<<response>>\n+int @X-Total [0..1]\n+long id\n", classBlock(diagram, "Pet"));
    }

    // ----------------------------------------------------------------- imports

    @Test
    void anImportedTypeBecomesAnExternalClassWithANoteNamingItsFile() {
        String diagram = render(
                "import Money from \"common-types.yaml\"",
                "response (1) : Invoice",
                "    total (1) : Money");

        assertEquals("<<external>>\n", classBlock(diagram, "Money"));
        assertTrue(diagram.contains("note for Money \"imported from common-types.yaml\""), diagram);
        assertTrue(diagram.contains("Invoice --> \"1\" Money : total"), diagram);
    }

    @Test
    void anImportWithoutAPathSaysSoInItsNote() {
        String diagram = render(
                "import Money",
                "response (1) : Invoice",
                "    total (1) : Money");

        assertTrue(diagram.contains("note for Money \"imported - no path in the sketch yet\""), diagram);
    }

    // ------------------------------------------------- rules and what is drawn

    @Test
    void aSketchTheTranslationRejectsYieldsNoDiagramEither() {
        // every rule lives in SpecSketchGenerator, and reports the same line as for the YAML
        assertSpecFailure(() -> render(
                "response (1) : Res",
                "    item (1) : Missing"), "line 2: type 'Missing' is used but never defined");
        assertSpecFailure(() -> render(
                "response (1) : Res",
                "  wrong (1) : string"), "line 2: indentation must be a multiple of 4");
        assertSpecFailure(() -> render("request (1) : Req", "    id (1) : long"),
                "missing top-level 'response' definition");
    }

    @Test
    void theMessagesAboutTheYamlAreNotForwardedToTheDiagram() {
        // '$petId' is emitted as 'in: query' AND reported when the YAML is written; the diagram
        // draws the undecided location as undecided, so there is nothing to report here
        List<String> messages = new ArrayList<>();
        String diagram = render(messages,
                "request (1) : Req",
                "    $petId (1) : long",
                "    name (1) : string",
                "response (1) : Res",
                "    id (1) : long");

        assertTrue(diagram.contains("+long $petId"), diagram);
        assertTrue(messages.isEmpty(), () -> "unexpected messages: " + messages);
    }

    @Test
    void theHeaderCommentNamesTheSketchToChange() {
        String diagram = SketchMermaidGenerator.generateMermaid(
                List.of("response (1) : Res", "    id (1) : long"), "petstore.sketch",
                SketchDiagram.View.FULL, new ArrayList<>());

        assertWellFormedMermaid(diagram);
        assertTrue(diagram.contains("%% generated from petstore.sketch by SketchMermaidGenerator"), diagram);
    }

    // ---------------------------------------------------------- structure view

    @Test
    void theStructureViewKeepsTheTypeGraphAndDropsTheBuiltInFields() {
        String[] sketch = {
                "request (1) : CreatePetRequest",
                "    @X-Request-Id (0 - 1) : uuid",
                "    name (1) : string",
                "    category (0 - 1) : Category",
                "        id (1) : long",
                "        name (1) : string",
                "response (1) : PetPageResponse",
                "    pets (0 - *) : Pet",
                "        id (1) : long",
                "        category (0 - 1) : Category",
                "    totalCount (1) : int"};

        String diagram = renderStructure(sketch);

        // the classes and their relations are all there ...
        assertTrue(diagram.contains("CreatePetRequest --> \"0..1\" Category : category"), diagram);
        assertTrue(diagram.contains("PetPageResponse --> \"0..*\" Pet : pets"), diagram);
        assertTrue(diagram.contains("Pet --> \"0..1\" Category : category"), diagram);
        // ... while every class is down to what it is, without a single built-in field
        assertEquals("<<request>>\n", classBlock(diagram, "CreatePetRequest"));
        assertEquals("<<response>>\n", classBlock(diagram, "PetPageResponse"));
        assertEquals("", classBlock(diagram, "Pet"));
        assertEquals("", classBlock(diagram, "Category"));
        assertFalse(diagram.contains("+long id"), diagram);
        assertFalse(diagram.contains("@X-Request-Id"), diagram);
        // the full view of the same sketch has them
        assertTrue(render(sketch).contains("+long id"));
    }

    @Test
    void theStructureViewKeepsEnumValuesAndTheDiscriminator() {
        // both say what a type IS, unlike a string or a number it happens to carry
        String diagram = renderStructure(
                "response (1) : Res",
                "    status (1) : enum PetStatus [AVAILABLE, SOLD]",
                "    events (0 - *) : PetEvent",
                "        eventType (1) : discriminator",
                "        occurredAt (1) : Instant",
                "        extended by AdoptionEvent",
                "            newOwner (1) : string");

        assertEquals("<<enumeration>>\nAVAILABLE\nSOLD\n", classBlock(diagram, "PetStatus"));
        assertEquals("+discriminator eventType\n", classBlock(diagram, "PetEvent"));
        assertEquals("", classBlock(diagram, "AdoptionEvent"));
        assertTrue(diagram.contains("PetEvent <|-- AdoptionEvent"), diagram);
    }

    @Test
    void theStructureViewSaysSoInItsHeader() {
        String diagram = renderStructure("response (1) : Res", "    id (1) : long");

        assertTrue(diagram.contains("%% structure view: the custom types and how they relate;"
                + " attributes of a built-in type are left out"), diagram);
    }

    @Test
    void theTwoViewsGetTwoDefaultFileNames() {
        Path sketch = Path.of("api", "petstore.sketch");

        assertEquals(Path.of("api", "petstore.mmd"),
                SketchMermaidGenerator.defaultOutput(sketch, SketchDiagram.View.FULL));
        assertEquals(Path.of("api", "petstore-structure.mmd"),
                SketchMermaidGenerator.defaultOutput(sketch, SketchDiagram.View.STRUCTURE));
    }

    // -------------------------------------------------------------- file level

    @Test
    void theDiagramIsWrittenNextToTheSketchWithTheMermaidSuffix(@TempDir Path dir) throws IOException {
        Path sketch = dir.resolve("petstore.sketch");
        Files.write(sketch, List.of("response (1) : Pet", "    id (1) : long"));
        List<String> messages = new ArrayList<>();

        boolean written = SketchMermaidGenerator.render(sketch, dir.resolve("petstore.mmd"), SketchDiagram.View.FULL, messages);

        assertTrue(written);
        String diagram = Files.readString(dir.resolve("petstore.mmd"));
        assertWellFormedMermaid(diagram);
        assertTrue(diagram.contains("+long id"), diagram);
        assertTrue(messages.isEmpty(), () -> "unexpected messages: " + messages);
    }

    @Test
    void anUnchangedDiagramFileIsLeftAlone(@TempDir Path dir) throws IOException {
        Path sketch = dir.resolve("petstore.sketch");
        Path diagram = dir.resolve("petstore.mmd");
        Files.write(sketch, List.of("response (1) : Pet", "    id (1) : long"));
        SketchMermaidGenerator.render(sketch, diagram, SketchDiagram.View.FULL, new ArrayList<>());

        List<String> messages = new ArrayList<>();
        assertFalse(SketchMermaidGenerator.render(sketch, diagram, SketchDiagram.View.FULL, messages));

        assertEquals(List.of(diagram + " is already up to date"), messages);
    }

    @Test
    void overwritingAModifiedDiagramFileIsReported(@TempDir Path dir) throws IOException {
        Path sketch = dir.resolve("petstore.sketch");
        Path diagram = dir.resolve("petstore.mmd");
        Files.write(sketch, List.of("response (1) : Pet", "    id (1) : long"));
        SketchMermaidGenerator.render(sketch, diagram, SketchDiagram.View.FULL, new ArrayList<>());

        // a hand edit: the diagram is generated, so it goes - but not without a word
        Files.writeString(diagram, Files.readString(diagram) + "    %% HAND-EDITED\n");
        List<String> messages = new ArrayList<>();
        SketchMermaidGenerator.render(sketch, diagram, SketchDiagram.View.FULL, messages);

        assertFalse(Files.readString(diagram).contains("HAND-EDITED"));
        assertMessage(messages, "was overwritten");
    }

    @Test
    void writingOverTheSketchItselfIsRejected(@TempDir Path dir) throws IOException {
        Path sketch = dir.resolve("petstore.sketch");
        Files.write(sketch, List.of("response (1) : Pet", "    id (1) : long"));

        SpecSketchGenerator.SpecException e = assertThrows(SpecSketchGenerator.SpecException.class,
                () -> SketchMermaidGenerator.render(sketch, sketch, SketchDiagram.View.FULL, new ArrayList<>()));
        assertTrue(e.getMessage().contains("would overwrite the input file"));
    }

    private static void assertSpecFailure(org.junit.jupiter.api.function.Executable executable, String messagePart) {
        SpecSketchGenerator.SpecException e = assertThrows(SpecSketchGenerator.SpecException.class, executable);
        assertTrue(e.getMessage().contains(messagePart),
                () -> "expected message to contain '" + messagePart + "' but was: " + e.getMessage());
    }
}
