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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request payload - reuses Category and PetStatus, which the response part defines as well. */
public class NewPet {

    @NotNull
    @Size(min = 1, max = 100)
    private String name;

    @Pattern(regexp = "^[A-Z]{2}-[0-9]{4}$")
    private String voucherCode;

    private PetStatus status;

    private Category category;

    public String getName() {
        return name;
    }

    public String getVoucherCode() {
        return voucherCode;
    }

    public PetStatus getStatus() {
        return status;
    }

    public Category getCategory() {
        return category;
    }
}
