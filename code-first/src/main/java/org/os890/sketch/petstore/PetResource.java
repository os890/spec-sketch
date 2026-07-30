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

import java.util.List;
import java.util.UUID;

import org.os890.sketch.petstore.model.NewPet;
import org.os890.sketch.petstore.model.Pet;
import org.os890.sketch.petstore.model.PetStatus;
import org.os890.sketch.petstore.model.Shipment;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The code-first source of truth: a hand-written JAX-RS 3.1 resource. JavaSketchGenerator reads
 * this class plus the model it references and emits one .sketch (and its .yaml) per endpoint.
 *
 * The three ways a response payload becomes known are all present:
 * <ul>
 *   <li>{@link #listPets} declares it in the signature ({@code List<Pet>}),</li>
 *   <li>{@link #getPet} returns the generic {@code Response} and declares it via
 *       {@code @ApiResponse},</li>
 *   <li>{@link #createPet} returns {@code Response} without any hint, so the type is passed to
 *       the generator explicitly (see the --response argument in this module's pom.xml).</li>
 * </ul>
 */
@Path("/pets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PetResource {

    /** Payload type is right there in the signature. */
    @GET
    public List<Pet> listPets(@QueryParam("status") PetStatus status) {
        throw new UnsupportedOperationException("demo resource: only its signatures are read");
    }

    /** Returns Response, but @ApiResponse says what is in it - including a response header. */
    @GET
    @Path("/{petId}")
    @APIResponse(
            content = @Content(schema = @Schema(implementation = Pet.class)),
            headers = @Header(name = "X-Cache-Hit", schema = @Schema(implementation = Boolean.class)))
    public Response getPet(@PathParam("petId") long petId,
                           @HeaderParam("X-Request-Id") UUID requestId) {
        throw new UnsupportedOperationException("demo resource: only its signatures are read");
    }

    /** Returns Response with no hint at all - the type comes from --response createPet=... */
    @POST
    public Response createPet(@Valid NewPet newPet,
                              @HeaderParam("X-Request-Id") UUID requestId) {
        throw new UnsupportedOperationException("demo resource: only its signatures are read");
    }

    /** Polymorphic payload: the Shipment hierarchy is emitted with discriminator and subtypes. */
    @GET
    @Path("/{petId}/shipments")
    @APIResponse(headers = @Header(name = "X-Total-Count", schema = @Schema(implementation = Integer.class)))
    public List<Shipment> listShipments(@PathParam("petId") long petId) {
        throw new UnsupportedOperationException("demo resource: only its signatures are read");
    }
}
