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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the SpecSketch -> OpenAPI translation directly (no Maven plugin involved):
 * request/response parts, all occurrence forms, and the indentation/nesting rules.
 *
 * SpecSketchGenerator lives in src/main/tools (default package) and is added as a
 * source root via the build-helper-maven-plugin, so this test can call it and
 * the module jar can run it (java -jar target/sketch-first-*.jar).
 */
class SpecSketchGeneratorTest {

    private static String generate(String... specLines) {
        return SpecSketchGenerator.generateYaml(List.of(specLines), "sample");
    }

    /**
     * Returns the indented block below the line '{indent spaces}{key}:', e.g. one
     * schema below 'components:/schemas:' (indent 4) or one property below
     * 'properties:' (indent 8).
     */
    private static String block(String yaml, String key, int indent) {
        String prefix = " ".repeat(indent) + key + ":";
        StringBuilder result = new StringBuilder();
        boolean inside = false;
        for (String line : yaml.split("\n")) {
            if (inside) {
                if (!line.startsWith(" ".repeat(indent + 1))) {
                    break;
                }
                result.append(line).append("\n");
            } else if (line.equals(prefix)) {
                inside = true;
            }
        }
        assertFalse(result.isEmpty(), () -> "no block found for '" + prefix + "' in:\n" + yaml);
        return result.toString();
    }

    private static String schema(String yaml, String name) {
        return block(yaml, name, 4);
    }

    private static String property(String yaml, String name) {
        return block(yaml, name, 8);
    }

    // ------------------------------------------------------ request / response

    @Test
    void responseOnlyBecomesGetWithEmptyRequest() {
        String yaml = generate(
                "response (1) : GreetingDTO",
                "    text (1) : string");

        assertTrue(yaml.contains("get:"));
        assertTrue(yaml.contains("operationId: getGreetingDTO"));
        assertFalse(yaml.contains("requestBody"));
        assertTrue(schema(yaml, "GreetingDTO").contains("text"));
    }

    @Test
    void requestPartBecomesPostWithRequiredBody() {
        String yaml = generate(
                "request (1) : CreateGreeting",
                "    text (1) : string",
                "response (1) : GreetingDTO",
                "    text (1) : string");

        assertTrue(yaml.contains("post:"));
        assertTrue(yaml.contains("operationId: postGreetingDTO"));
        String requestBody = block(yaml, "requestBody", 6);
        assertTrue(requestBody.contains("required: true"));
        assertTrue(requestBody.contains("'#/components/schemas/CreateGreeting'"));
        assertTrue(schema(yaml, "CreateGreeting").contains("text"));
    }

    @Test
    void optionalRequestOccurrenceMakesBodyOptional() {
        String yaml = generate(
                "request (0 - 1) : CreateGreeting",
                "    text (1) : string",
                "response (1) : GreetingDTO",
                "    text (1) : string");

        assertTrue(block(yaml, "requestBody", 6).contains("required: false"));
    }

    @Test
    void requestMayReferenceTypesDefinedInTheResponsePart() {
        String yaml = generate(
                "request (1) : Req",
                "    item (1) : Shared",
                "response (1) : Res",
                "    item (0 - 1) : Shared",
                "        id (1) : long");

        assertTrue(property(schema(yaml, "Req"), "item").contains("'#/components/schemas/Shared'"));
        assertTrue(schema(yaml, "Shared").contains("id"));
    }

    // ------------------------------------------------------- occurrence forms

    @Test
    void occurrenceFormsMapToRequiredAndArrayBounds() {
        String yaml = generate(
                "response (1) : VariantsDTO",
                "    exactlyOne (1) : string",
                "    optionalSingle (0 - 1) : string",
                "    unbounded (0 - *) : string",
                "    atLeastOne (1 - *) : string",
                "    twoToFive (2 - 5) : string",
                "    exactlyThree (3) : string");

        String schema = schema(yaml, "VariantsDTO");
        String required = block(schema, "required", 6);
        assertTrue(required.contains("- exactlyOne"));
        assertTrue(required.contains("- atLeastOne"));
        assertTrue(required.contains("- twoToFive"));
        assertTrue(required.contains("- exactlyThree"));
        assertFalse(required.contains("- optionalSingle"));
        assertFalse(required.contains("- unbounded"));

        // min/max 1 -> plain value, no array
        assertEquals("          type: string\n", property(schema, "exactlyOne"));
        assertEquals("          type: string\n", property(schema, "optionalSingle"));

        String unbounded = property(schema, "unbounded");
        assertTrue(unbounded.contains("type: array"));
        assertFalse(unbounded.contains("minItems"));
        assertFalse(unbounded.contains("maxItems"));

        String atLeastOne = property(schema, "atLeastOne");
        assertTrue(atLeastOne.contains("minItems: 1"));
        assertFalse(atLeastOne.contains("maxItems"));

        String twoToFive = property(schema, "twoToFive");
        assertTrue(twoToFive.contains("minItems: 2"));
        assertTrue(twoToFive.contains("maxItems: 5"));

        // a plain number > 1 means exactly n
        String exactlyThree = property(schema, "exactlyThree");
        assertTrue(exactlyThree.contains("minItems: 3"));
        assertTrue(exactlyThree.contains("maxItems: 3"));
    }

    // --------------------------------------------------------------- imports

    @Test
    void importedTypesEmitPlaceholderRefsInsteadOfSchemas() {
        String yaml = generate(
                "import Address",
                "request (1) : Req",
                "    shipping (1) : Address",
                "response (1) : Res",
                "    billing (0 - 1) : Address");

        // every usage refs the type-specific placeholder, meant for manual post-editing
        assertEquals(2, countOccurrences(yaml, "'TODO-IMPORT/Address.yaml#/components/schemas/Address'"));
        // no local schema is generated for the imported type
        assertFalse(yaml.contains("    Address:\n"));
    }

    @Test
    void importWithKnownPathEmitsTheRealRef() {
        String yaml = generate(
                "import Money from \"common/types.yaml\"",
                "response (1) : Res",
                "    total (1) : Money",
                "    refunds (0 - *) : Money");

        assertEquals(2, countOccurrences(yaml, "'common/types.yaml#/components/schemas/Money'"));
        assertFalse(yaml.contains("TODO-IMPORT"));
    }

    @Test
    void importRuleViolationsAreRejected() {
        // defining details for an imported type
        assertSpecFailure(() -> generate(
                "import Address",
                "response (1) : Res",
                "    shipping (1) : Address",
                "        street (1) : string"), "is imported (line 1)");
        // duplicate import
        assertSpecFailure(() -> generate(
                "import Address",
                "import Address",
                "response (1) : Res",
                "    x (1) : Address"), "already imported");
        // importing a built-in
        assertSpecFailure(() -> generate(
                "import String",
                "response (1) : Res",
                "    x (1) : string"), "built-in type and cannot be imported");
        // import below the top level
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    import Address"), "only allowed at the top level");
        // import with properties
        assertSpecFailure(() -> generate(
                "import Address",
                "    street (1) : string",
                "response (1) : Res",
                "    x (1) : Address"), "must not define properties");
        // case collision with a local type
        assertSpecFailure(() -> generate(
                "import Address",
                "response (1) : Res",
                "    x (1) : address",
                "        street (1) : string"), "differs only in case");
    }

    // ------------------------------------------------------------ attributes

    @Test
    void stringAttributesAreEmittedIntoTheSchema() {
        String yaml = generate(
                "response (1) : PersonDTO",
                "    name (1) : string {minLength: 1, maxLength: 100}",
                "    code (0 - 1) : string {pattern: \"^[a-z]{2,3}$\"}");

        String name = property(schema(yaml, "PersonDTO"), "name");
        assertTrue(name.contains("minLength: 1"));
        assertTrue(name.contains("maxLength: 100"));
        // quoted patterns may contain commas and braces
        assertTrue(property(schema(yaml, "PersonDTO"), "code").contains("pattern: '^[a-z]{2,3}$'"));
    }

    @Test
    void numericAttributesMapToMinimumAndMaximum() {
        String yaml = generate(
                "response (1) : RangeDTO",
                "    age (0 - 1) : int {min: 0, max: 150}",
                "    price (1) : BigDecimal {minimum: 0.5}");

        String age = property(schema(yaml, "RangeDTO"), "age");
        assertTrue(age.contains("minimum: 0"));
        assertTrue(age.contains("maximum: 150"));
        assertTrue(property(schema(yaml, "RangeDTO"), "price").contains("minimum: 0.5"));
    }

    @Test
    void arrayAttributesApplyToTheItems() {
        String yaml = generate(
                "response (1) : TagsDTO",
                "    tags (0 - *) : string {maxLength: 20}");

        String tags = property(schema(yaml, "TagsDTO"), "tags");
        assertTrue(tags.contains("type: array"));
        // maxLength sits inside items:, not on the array itself
        assertTrue(tags.contains("items:"));
        assertTrue(tags.substring(tags.indexOf("items:")).contains("maxLength: 20"));
    }

    @Test
    void invalidAttributesAreRejected() {
        // wrong type family, with a hint each
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    age (1) : int {minLength: 1}"), "only allowed on string types");
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    name (1) : string {min: 1}"), "only allowed on numeric types");
        // unknown key, unsupported targets, duplicates, inverted bounds
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    name (1) : string {length: 5}"), "unknown attribute");
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    pet (1) : Pet {maxLength: 5}",
                "        id (1) : long"), "not on type references");
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    gender (1) : enum [M, F] {maxLength: 1}"), "not supported on enums");
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    name (1) : string {maxLength: 5, maxLength: 9}"), "duplicate attribute");
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    name (1) : string {minLength: 9, maxLength: 5}"), "greater than");
    }

    // -------------------------------------------------------------- headers

    @Test
    void requestHeadersBecomeHeaderParametersAndStayOutOfTheBody() {
        String yaml = generate(
                "request (1) : Req",
                "    @X-Client-Id (1) : uuid",
                "    @X-Feature-Flags (0 - *) : string",
                "    name (1) : string",
                "response (1) : Res",
                "    ok (1) : boolean");

        String parameters = block(yaml, "parameters", 6);
        assertTrue(parameters.contains("- name: X-Client-Id"));
        assertTrue(parameters.contains("in: header"));
        assertTrue(parameters.contains("required: true"));
        assertTrue(parameters.contains("format: uuid"));
        // repeatable header -> array schema; optional -> no required flag on it
        assertTrue(parameters.contains("- name: X-Feature-Flags"));
        assertTrue(parameters.contains("type: array"));

        // headers are not body properties
        String req = schema(yaml, "Req");
        assertTrue(req.contains("name"));
        assertFalse(req.contains("X-Client-Id"));
        assertFalse(req.contains("X-Feature-Flags"));
    }

    @Test
    void headerOnlyRequestHasNoBodyAndStaysGet() {
        String yaml = generate(
                "request (1) : Probe",
                "    @X-Client-Id (1) : uuid",
                "response (1) : Res",
                "    ok (1) : boolean");

        assertTrue(yaml.contains("get:"));
        assertTrue(yaml.contains("- name: X-Client-Id"));
        assertFalse(yaml.contains("requestBody"));
    }

    @Test
    void responseHeadersLandInTheResponseHeadersSection() {
        String yaml = generate(
                "response (1) : Res",
                "    @X-Rate-Limit-Remaining (1) : int",
                "    @X-Level (0 - 1) : enum Level [LOW, HIGH]",
                "    ok (1) : boolean");

        String headers = block(yaml, "headers", 10);
        assertTrue(headers.contains("X-Rate-Limit-Remaining:"));
        assertTrue(headers.contains("required: true"));
        assertTrue(headers.contains("format: int32"));
        // named enum defined on a header line is referenced like everywhere else
        assertTrue(headers.contains("'#/components/schemas/Level'"));
        assertTrue(schema(yaml, "Level").contains("- LOW"));
        assertFalse(schema(yaml, "Res").contains("X-Rate-Limit-Remaining"));
    }

    @Test
    void headersAreOnlyAllowedDirectlyBelowRequestOrResponse() {
        assertSpecFailure(() -> generate(
                "@X-Top-Level (1) : string",
                "response (1) : Res",
                "    ok (1) : boolean"), "only allowed directly below");
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    nested (1) : Inner",
                "        @X-Too-Deep (1) : string"), "only allowed directly below");
    }

    // ------------------------------------------------------------- comments

    @Test
    void trailingCommentsOfAllStylesAreIgnored() {
        String withComments = generate(
                "response (1) : PersonDTO      // the payload",
                "    name (1) : string         # display name",
                "    gender (0 - 1) : enum [M, F] /* to be extended */",
                "    address (0 - 1) : Address /* nested */ // really",
                "        city (1) : string");
        String clean = generate(
                "response (1) : PersonDTO",
                "    name (1) : string",
                "    gender (0 - 1) : enum [M, F]",
                "    address (0 - 1) : Address",
                "        city (1) : string");

        assertEquals(clean, withComments);
    }

    @Test
    void blockCommentsMayAlsoSitMidLine() {
        String yaml = generate(
                "response (1) : PersonDTO",
                "    name (1) /* occurrence */ : /* type */ string");

        assertTrue(property(schema(yaml, "PersonDTO"), "name").contains("type: string"));
    }

    @Test
    void commentOnlyLinesAreIgnoredAtAnyIndentation() {
        String withComments = generate(
                "# header comment",
                "response (1) : RootDTO",
                "    a (1) : A",
                "   // odd indentation is fine for a comment-only line",
                "        x (1) : string",
                "                # deeply indented comment does not open a level",
                "        y (1) : string",
                "  /* dedented comment does not close a level */",
                "        z (1) : string",
                "    b (1) : string");
        String clean = generate(
                "response (1) : RootDTO",
                "    a (1) : A",
                "        x (1) : string",
                "        y (1) : string",
                "        z (1) : string",
                "    b (1) : string");

        assertEquals(clean, withComments);
    }

    @Test
    void unterminatedBlockCommentIsRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    x (1) : string /* oops"), "not closed on the same line");
    }

    // ---------------------------------------------------------- type mapping

    @Test
    void javaPrimitiveAndWrapperTypesAreMapped() {
        String yaml = generate(
                "response (1) : JavaTypesDTO",
                "    anInt (1) : int",
                "    anInteger (1) : Integer",
                "    aShort (1) : short",
                "    aByte (1) : byte",
                "    aLong (1) : Long",
                "    aFloat (1) : float",
                "    aDouble (1) : Double",
                "    aBoolean (1) : Boolean",
                "    aChar (1) : char",
                "    aString (1) : String",
                "    aBigDecimal (1) : BigDecimal",
                "    aBigInteger (1) : BigInteger");

        String schema = schema(yaml, "JavaTypesDTO");
        assertTrue(property(schema, "anInt").contains("format: int32"));
        assertTrue(property(schema, "anInteger").contains("format: int32"));
        assertTrue(property(schema, "aShort").contains("format: int32"));
        assertTrue(property(schema, "aByte").contains("format: int32"));
        assertTrue(property(schema, "aLong").contains("format: int64"));
        assertTrue(property(schema, "aFloat").contains("format: float"));
        assertTrue(property(schema, "aDouble").contains("format: double"));
        assertTrue(property(schema, "aBoolean").contains("type: boolean"));
        assertTrue(property(schema, "aString").contains("type: string"));
        assertTrue(property(schema, "aBigDecimal").contains("type: number"));
        assertTrue(property(schema, "aBigInteger").contains("type: integer"));

        // a char is a string of exactly one character
        String aChar = property(schema, "aChar");
        assertTrue(aChar.contains("type: string"));
        assertTrue(aChar.contains("minLength: 1"));
        assertTrue(aChar.contains("maxLength: 1"));
    }

    @Test
    void java8DateClassesAreMapped() {
        String yaml = generate(
                "response (1) : DatesDTO",
                "    aLocalDate (1) : LocalDate",
                "    aLocalDateTime (1) : LocalDateTime",
                "    anOffsetDateTime (1) : OffsetDateTime",
                "    aZonedDateTime (1) : ZonedDateTime",
                "    anInstant (1) : Instant",
                "    aLocalTime (1) : LocalTime");

        String schema = schema(yaml, "DatesDTO");
        assertTrue(property(schema, "aLocalDate").contains("format: date\n"));
        assertTrue(property(schema, "aLocalDateTime").contains("format: date-time"));
        assertTrue(property(schema, "anOffsetDateTime").contains("format: date-time"));
        assertTrue(property(schema, "aZonedDateTime").contains("format: date-time"));
        assertTrue(property(schema, "anInstant").contains("format: date-time"));
        assertTrue(property(schema, "aLocalTime").contains("format: time"));
    }

    @Test
    void builtInTypesAcceptLowerCaseAndOriginalCase() {
        String yaml = generate(
                "response (1) : CaseDTO",
                "    lower (1) : string",
                "    original (1) : String",
                "    upper (1) : STRING",
                "    lowerDate (1) : localdate",
                "    originalDate (1) : LocalDate");

        String schema = schema(yaml, "CaseDTO");
        assertEquals(property(schema, "lower").replace("lower", "x"),
                property(schema, "original").replace("original", "x"));
        assertTrue(property(schema, "upper").contains("type: string"));
        assertTrue(property(schema, "lowerDate").contains("format: date"));
        assertTrue(property(schema, "originalDate").contains("format: date"));
    }

    @Test
    void customTypesDifferingOnlyInCaseAreRejected() {
        // definition vs definition
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    a (1) : Detail",
                "        x (1) : string",
                "    b (1) : DETAIL",
                "        y (1) : string"), "differs only in case from 'Detail' (line 2)");
        // reference vs definition
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    a (1) : Detail",
                "        x (1) : string",
                "    b (1) : detail"), "differs only in case");
    }

    // -------------------------------------------------------------- enums

    @Test
    void enumShorthandProducesInlineStringEnum() {
        String yaml = generate(
                "response (1) : PersonDTO",
                "    gender (1) : enum [M, F]");

        String gender = property(schema(yaml, "PersonDTO"), "gender");
        assertTrue(gender.contains("type: string"));
        assertTrue(gender.contains("enum:"));
        assertTrue(gender.contains("- M"));
        assertTrue(gender.contains("- F"));
    }

    @Test
    void enumShorthandWorksInsideArrays() {
        String yaml = generate(
                "response (1) : ScheduleDTO",
                "    workingDays (1 - 7) : enum [MON, TUE, WED, THU, FRI, SAT, SUN]");

        String workingDays = property(schema(yaml, "ScheduleDTO"), "workingDays");
        assertTrue(workingDays.contains("type: array"));
        assertTrue(workingDays.contains("minItems: 1"));
        assertTrue(workingDays.contains("maxItems: 7"));
        assertTrue(workingDays.contains("enum:"));
        assertTrue(workingDays.contains("- MON"));
        assertTrue(workingDays.contains("- SUN"));
    }

    @Test
    void explicitStringValueTypeEqualsTheDefault() {
        String defaultType = generate(
                "response (1) : PersonDTO",
                "    gender (1) : enum [M, F]");
        String explicit = generate(
                "response (1) : PersonDTO",
                "    gender (1) : enum:String [M, F]");

        assertEquals(defaultType, explicit);
    }

    @Test
    void numericValueTypesProduceNumberEnums() {
        String yaml = generate(
                "response (1) : RatingDTO",
                "    stars (1) : enum:int [1, 2, 3, 4, 5]",
                "    factor (0 - 1) : enum:double [0.5, 1.0, 1.5]");

        String stars = property(schema(yaml, "RatingDTO"), "stars");
        assertTrue(stars.contains("type: integer"));
        assertTrue(stars.contains("format: int32"));
        assertTrue(stars.contains("- 1\n"));
        assertFalse(stars.contains("'1'"));

        String factor = property(schema(yaml, "RatingDTO"), "factor");
        assertTrue(factor.contains("type: number"));
        assertTrue(factor.contains("- 0.5\n"));
    }

    @Test
    void invalidEnumValueTypesAreRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    x (1) : enum:Pet [A, B]"), "not a built-in type");
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    x (1) : enum:boolean [A, B]"), "not supported");
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    x (1) : enum:int [1, oops]"), "invalid enum value");
        // a value type is only allowed on the 'enum' keyword
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    x (1) : String:int"), "only the keyword 'enum' supports a value type");
    }

    @Test
    void enumWithoutValuesIsRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    gender (1) : enum"), "requires its values");
    }

    @Test
    void enumValuesOnOtherTypesAreRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    gender (1) : string [M, F]"), "only the keyword 'enum'");
    }

    @Test
    void emptyAndDuplicateEnumValuesAreRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    gender (1) : enum [M, , F]"), "invalid enum value");
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    gender (1) : enum [M, F, M]"), "duplicate enum value");
    }

    @Test
    void enumWithNestedPropertiesIsRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    gender (1) : enum [M, F]",
                "        oops (1) : string"), "cannot have nested properties");
    }

    @Test
    void namedEnumIsDefinedOnceAndReferencedByName() {
        String yaml = generate(
                "request (1) : Req",
                "    before (0 - 1) : Level",   // forward reference works
                "response (1) : Res",
                "    level (1) : enum Level [LOW, MID, HIGH]",
                "    fallback (0 - 1) : Level");

        // one named schema carrying the values ...
        String level = schema(yaml, "Level");
        assertTrue(level.contains("type: string"));
        assertTrue(level.contains("- LOW"));
        // ... and $refs everywhere, including the defining line itself
        assertEquals(3, countOccurrences(yaml, "'#/components/schemas/Level'"));
        assertFalse(property(schema(yaml, "Res"), "level").contains("enum:"));
    }

    @Test
    void namedEnumComposesWithValueTypeAndArrays() {
        String yaml = generate(
                "response (1) : Res",
                "    stars (1) : enum:int Rating [1, 2, 3]",
                "    history (0 - *) : Rating");

        String rating = schema(yaml, "Rating");
        assertTrue(rating.contains("type: integer"));
        assertTrue(rating.contains("- 1"));
        String history = property(schema(yaml, "Res"), "history");
        assertTrue(history.contains("type: array"));
        assertTrue(history.contains("'#/components/schemas/Rating'"));
    }

    @Test
    void namedEnumFollowsTheSingleDefinitionAndCaseRules() {
        // redefinition is rejected like for object types
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    a (1) : enum Level [LOW]",
                "    b (1) : enum Level [LOW]"), "already defined at line 2");
        // case collision with another named type is rejected
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    a (1) : enum Level [LOW]",
                "    b (1) : level"), "differs only in case");
        // a built-in name cannot be reused as enum name
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    a (1) : enum String [X]"), "collides with a built-in");
        // a name is only allowed on the 'enum' keyword
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    a (1) : string Level [X]"), "only the keyword 'enum' may declare a type name");
    }

    // ------------------------------------------------------- inheritance

    @Test
    void subtypesBecomeAllOfCompositions() {
        String yaml = generate(
                "response (1) : Garage",
                "    vehicles (0 - *) : Vehicle",
                "        maxSpeed (1) : int",
                "        extended by Car",
                "            doors (1) : int",
                "        extended by Bike",
                "            electric (1) : boolean",
                "    favoriteCar (0 - 1) : Car");

        // the property uses the base type; subtypes are referable like any named type
        assertTrue(property(schema(yaml, "Garage"), "vehicles").contains("'#/components/schemas/Vehicle'"));
        assertTrue(property(schema(yaml, "Garage"), "favoriteCar").contains("'#/components/schemas/Car'"));

        // subtype = allOf(base ref, additional properties); marker lines are not properties
        String car = schema(yaml, "Car");
        assertTrue(car.contains("allOf:"));
        assertTrue(car.contains("- '$ref': '#/components/schemas/Vehicle'"));
        assertTrue(car.contains("doors"));
        assertFalse(car.contains("maxSpeed"));
        assertFalse(schema(yaml, "Vehicle").contains("doors"));

        // no discriminator marker -> plain structural inheritance
        assertFalse(yaml.contains("discriminator"));
    }

    @Test
    void discriminatorMarkerMakesTheHierarchyPolymorphic() {
        String yaml = generate(
                "response (1) : Garage",
                "    vehicles (0 - *) : Vehicle",
                "        vehicleType (1) : discriminator",
                "        maxSpeed (1) : int",
                "        extended by Car",
                "            doors (1) : int",
                "            extended by SportsCar",
                "                topSpeed (1) : int",
                "        extended by Bike",
                "            electric (1) : boolean");

        String vehicle = schema(yaml, "Vehicle");
        // the marker becomes a required string property plus the discriminator section
        assertTrue(vehicle.contains("- vehicleType"));
        assertTrue(vehicle.contains("propertyName: vehicleType"));
        // the mapping covers ALL transitive subtypes
        assertTrue(vehicle.contains("Car: '#/components/schemas/Car'"));
        assertTrue(vehicle.contains("SportsCar: '#/components/schemas/SportsCar'"));
        assertTrue(vehicle.contains("Bike: '#/components/schemas/Bike'"));
        // nested hierarchies chain their allOf refs
        assertTrue(schema(yaml, "SportsCar").contains("- '$ref': '#/components/schemas/Car'"));
    }

    @Test
    void inheritanceRuleViolationsAreRejected() {
        // discriminator without subtypes
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    kind (1) : discriminator",
                "    x (1) : string"), "no subtypes");
        // more than one discriminator
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    a (1) : discriminator",
                "    b (1) : discriminator",
                "    extended by Sub",
                "        x (1) : string"), "more than one discriminator");
        // discriminator must be exactly (1)
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    kind (0 - 1) : discriminator",
                "    extended by Sub",
                "        x (1) : string"), "occurrence (1)");
        // 'extended by' outside a type definition
        assertSpecFailure(() -> generate(
                "extended by Car",
                "response (1) : Res",
                "    x (1) : string"), "only allowed inside a type definition");
        // subtype names join the define-once registry
        assertSpecFailure(() -> generate(
                "response (1) : Res",
                "    a (1) : Base",
                "        extended by Sub",
                "            x (1) : string",
                "    b (1) : Other",
                "        extended by Sub",
                "            y (1) : string"), "already defined");
    }

    @Test
    void extendedAsPropertyNameStillWorks() {
        // only the marker form 'extended by <SubType>' is special - normal properties may use these words
        String yaml = generate(
                "response (1) : Res",
                "    extended (1) : boolean",
                "    by (0 - 1) : string");

        String res = schema(yaml, "Res");
        assertTrue(res.contains("extended"));
        assertTrue(res.contains("by"));
    }

    // -------------------------------------------------------- type reuse

    @Test
    void repeatedTypeUsagesShareOneSchemaDefinition() {
        String yaml = generate(
                "response (1) : TreeDTO",
                "    left (0 - 1) : NodeDTO",
                "        value (1) : string",
                "        children (0 - *) : NodeDTO",
                "    right (0 - 1) : NodeDTO");

        // one schema definition, three $refs (left, right and the recursive children items)
        assertEquals(1, countOccurrences(yaml, "    NodeDTO:\n"));
        assertEquals(3, countOccurrences(yaml, "'#/components/schemas/NodeDTO'"));
        assertTrue(schema(yaml, "NodeDTO").contains("children"));
    }

    // ------------------------------------------------------- nesting rules

    @Test
    void deepNestingAndImplicitDedentLandOnTheProperLevels() {
        String yaml = generate(
                "response (1) : RootDTO",
                "    l1 (1) : L1",
                "        l2 (1) : L2",
                "            l3 (1) : L3",
                "                l4 (1) : L4",
                "                    deepest (1) : string",
                "            backOnLevelThree (1) : string",
                "    backOnLevelOne (1) : string",
                "    reused (0 - 1) : L1");

        // every nesting level became its own schema
        assertTrue(schema(yaml, "L4").contains("deepest"));
        assertTrue(schema(yaml, "L3").contains("l4"));

        // the dedent from level 5 to level 3 makes backOnLevelThree a sibling of l3 (property of L2)
        String l2 = schema(yaml, "L2");
        assertTrue(l2.contains("l3"));
        assertTrue(l2.contains("backOnLevelThree"));
        assertFalse(schema(yaml, "L3").contains("backOnLevelThree"));

        // the dedent from level 3 to level 1 lands back in the root type
        String root = schema(yaml, "RootDTO");
        assertTrue(root.contains("l1"));
        assertTrue(root.contains("backOnLevelOne"));

        // a type defined once can be reused by name
        assertTrue(property(root, "reused").contains("'#/components/schemas/L1'"));
    }

    @Test
    void oneTabCountsAsFourSpaces() {
        String yaml = generate(
                "response (1) : TabDTO",
                "\touter (1) : Outer",
                "\t\tinner (1) : string");

        assertTrue(schema(yaml, "TabDTO").contains("outer"));
        assertTrue(schema(yaml, "Outer").contains("inner"));
    }

    // ------------------------------------------------------------ error cases

    @Test
    void brokenIndentationIsRejectedWithLineNumber() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "   bad (1) : string"), "line 2");
    }

    @Test
    void skippingANestingLevelIsRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "        tooDeep (1) : string"), "line 2");
    }

    @Test
    void undefinedTypeIsRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    x (1) : Undefined"), "Undefined");
    }

    @Test
    void typeDetailsMayOnlyBeDefinedAtTheFirstOccurrence() {
        // even an identical second definition is rejected - later usages must be plain references
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    a (1) : Dup",
                "        x (1) : string",
                "    b (1) : Dup",
                "        x (1) : string"), "already defined at line 2");
    }

    @Test
    void builtInTypeWithPropertiesIsRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : string",
                "    x (1) : string"), "built-in");
    }

    @Test
    void invalidRangeIsRejected() {
        assertSpecFailure(() -> generate(
                "response (1) : T",
                "    x (5 - 2) : string"), "line 2");
    }

    @Test
    void missingResponseIsRejected() {
        assertSpecFailure(() -> generate(
                "request (1) : T",
                "    x (1) : string"), "response");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static void assertSpecFailure(org.junit.jupiter.api.function.Executable executable, String messagePart) {
        SpecSketchGenerator.SpecException e = assertThrows(SpecSketchGenerator.SpecException.class, executable);
        assertTrue(e.getMessage().contains(messagePart),
                () -> "expected message to contain '" + messagePart + "' but was: " + e.getMessage());
    }
}
