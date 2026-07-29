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

package org.os890.sketch.petstore;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import org.os890.sketch.petstore.dto.Category;
import org.os890.sketch.petstore.dto.Contact;
import org.os890.sketch.petstore.dto.CreatePetRequest;
import org.os890.sketch.petstore.dto.Money;
import org.os890.sketch.petstore.dto.AdoptionEvent;
import org.os890.sketch.petstore.dto.Owner;
import org.os890.sketch.petstore.dto.Pet;
import org.os890.sketch.petstore.dto.PetEvent;
import org.os890.sketch.petstore.dto.PetPageResponse;
import org.os890.sketch.petstore.dto.PetStatus;
import org.os890.sketch.petstore.dto.VaccinationEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Proves that the DTOs generated from the SpecSketch definition
 * (petstore.sketch -> OpenAPI YAML -> Java) work as plain Jackson-annotated beans.
 */
class GeneratedDtoTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

    @Test
    void responseSurvivesJsonRoundTrip() throws Exception {
        Pet rex = new Pet()
                .id(42L)
                .name("Rex")
                .status(PetStatus.AVAILABLE)
                .category(new Category().id(1L).name("dog"))
                .tags(List.of("friendly", "trained"))
                .createdAt(OffsetDateTime.parse("2026-07-29T10:00:00+02:00"))
                .birthday(LocalDate.parse("2020-05-01"))
                // Money is generated from the imported shared common-types.yaml
                .price(new Money().amount(new java.math.BigDecimal("149.90")).currency("EUR"));

        PetPageResponse response = new PetPageResponse()
                .pets(List.of(rex))
                .totalCount(1)
                .owner(new Owner()
                        .name("Alice")
                        .contact(new Contact().email("alice@example.org")))
                .favorite(rex);

        String json = mapper.writeValueAsString(response);
        PetPageResponse parsed = mapper.readValue(json, PetPageResponse.class);

        assertEquals(response, parsed);
        assertEquals("Rex", parsed.getPets().get(0).getName());
        // the named enum is one shared top-level class, not an inner enum per DTO
        assertEquals(PetStatus.AVAILABLE, parsed.getPets().get(0).getStatus());
        assertEquals("alice@example.org", parsed.getOwner().getContact().getEmail());
    }

    @Test
    void polymorphicSubtypesSurviveJsonRoundTrip() throws Exception {
        // the application assembles the real result from subtypes...
        VaccinationEvent vaccination = new VaccinationEvent();
        vaccination.setEventType("VaccinationEvent");
        vaccination.setOccurredAt(OffsetDateTime.parse("2026-07-01T09:00:00Z"));
        vaccination.setVaccine("rabies");
        AdoptionEvent adoption = new AdoptionEvent();
        adoption.setEventType("AdoptionEvent");
        adoption.setOccurredAt(OffsetDateTime.parse("2026-07-15T14:30:00Z"));
        adoption.setNewOwner("Alice");

        PetPageResponse response = new PetPageResponse()
                .totalCount(0)
                .events(List.of(vaccination, adoption));

        // ...and the discriminator brings the concrete types back out of the JSON
        String json = mapper.writeValueAsString(response);
        PetPageResponse parsed = mapper.readValue(json, PetPageResponse.class);

        List<PetEvent> events = parsed.getEvents();
        VaccinationEvent parsedVaccination = assertInstanceOf(VaccinationEvent.class, events.get(0));
        assertEquals("rabies", parsedVaccination.getVaccine());
        AdoptionEvent parsedAdoption = assertInstanceOf(AdoptionEvent.class, events.get(1));
        assertEquals("Alice", parsedAdoption.getNewOwner());
    }

    @Test
    void requestSurvivesJsonRoundTrip() throws Exception {
        CreatePetRequest request = new CreatePetRequest()
                .name("Bello")
                .status(PetStatus.PENDING)
                .category(new Category().id(2L).name("cat"))
                .tags(List.of("shy"));

        String json = mapper.writeValueAsString(request);
        CreatePetRequest parsed = mapper.readValue(json, CreatePetRequest.class);

        assertEquals(request, parsed);
        assertEquals("cat", parsed.getCategory().getName());
    }
}
