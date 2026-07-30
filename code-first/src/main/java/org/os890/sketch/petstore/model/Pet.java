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

package org.os890.sketch.petstore.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Response payload: primitives, java.time, a shared enum, a nested type and a cycle via Owner. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Pet {

    private long id;

    @NotNull
    @Size(min = 1, max = 100)
    private String name;

    @NotNull
    private PetStatus status;

    private Category category;

    @Size(max = 10)
    private List<String> tags;

    private OffsetDateTime createdAt;

    private LocalDate birthday;

    private Owner owner;

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public PetStatus getStatus() {
        return status;
    }

    public Category getCategory() {
        return category;
    }

    public List<String> getTags() {
        return tags;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDate getBirthday() {
        return birthday;
    }

    public Owner getOwner() {
        return owner;
    }
}
