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

package org.os890.sketch.petstore.scan;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/vehicles")
public class VehicleResource {

    @GET
    public List<Vehicle> vehicles() {
        throw new UnsupportedOperationException("only the signature is read");
    }

    /** The response DTO is in this package, the base of its hierarchy is not. */
    @GET
    @Path("/events")
    public EventFeed events() {
        throw new UnsupportedOperationException("only the signature is read");
    }
}
