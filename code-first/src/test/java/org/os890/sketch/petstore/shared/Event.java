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

package org.os890.sketch.petstore.shared;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/**
 * Plays the shared-library base: it knows nothing about its subtypes, and they do not live in
 * this package. Only scanning the package of the DTO that uses it can find them.
 */
public abstract class Event {

    @NotNull
    private Instant occurredAt;

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
