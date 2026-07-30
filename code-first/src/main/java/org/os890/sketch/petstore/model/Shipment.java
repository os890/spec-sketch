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

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;

/**
 * Polymorphic base. @JsonTypeInfo names the discriminator property, @JsonSubTypes lists the
 * direct subtypes - ParcelShipment adds a third level, so the hierarchy is walked recursively.
 * The discriminator has no field of its own here; the generator synthesizes the line.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "shipmentType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ParcelShipment.class, name = "ParcelShipment"),
        @JsonSubTypes.Type(value = PickupShipment.class, name = "PickupShipment")
})
public abstract class Shipment {

    @NotNull
    private UUID trackingId;

    public UUID getTrackingId() {
        return trackingId;
    }
}
