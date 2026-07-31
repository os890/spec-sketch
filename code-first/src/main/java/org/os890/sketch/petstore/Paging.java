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

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

/**
 * The nested layer of {@link PetSearch}: paging parameters, reusable across endpoints.
 *
 * Both are primitives with a @DefaultValue, which is the one case where a primitive parameter is
 * NOT required: the server fills the value in, so the client may leave it out. (A primitive DTO
 * field is the opposite - it can never be null, so it is always on the wire.)
 */
public class Paging {

    @QueryParam("page")
    @DefaultValue("0")
    private int page;

    @QueryParam("size")
    @DefaultValue("20")
    private int size;

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }
}
