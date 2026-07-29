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

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import org.os890.sketch.petstore.dto.Category;
import org.os890.sketch.petstore.dto.Pet;
import org.os890.sketch.petstore.dto.PetStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves that the generated DTOs work as plain Jackson-annotated beans:
 * builder-style setters, enum mapping, JSON round-trip.
 */
class GeneratedDtoTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

    @Test
    void petSurvivesJsonRoundTrip() throws Exception {
        Pet pet = new Pet()
                .id(42L)
                .name("Rex")
                .status(PetStatus.AVAILABLE)
                .category(new Category().id(1L).name("dog"))
                .tags(List.of("friendly", "trained"))
                .createdAt(OffsetDateTime.parse("2026-07-29T10:00:00+02:00"));

        String json = mapper.writeValueAsString(pet);
        Pet parsed = mapper.readValue(json, Pet.class);

        assertEquals(pet, parsed);
        assertEquals(PetStatus.AVAILABLE, parsed.getStatus());
        assertEquals("dog", parsed.getCategory().getName());
    }
}
