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

import org.os890.sketch.petstore.model.PetStatus;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.QueryParam;

/**
 * A @BeanParam holder: not a payload, but the parameters of one endpoint gathered into a POJO -
 * the usual way to keep a signature readable once an endpoint takes more than a handful of them.
 *
 * The generator flattens the whole tree into the sketch's parameter lines, which is why the mix
 * matters here: a query parameter, a repeatable one, a header (invisible to a generator that stops
 * at the @BeanParam itself) and a nested holder for the paging parameters.
 */
public class PetSearch {

    @QueryParam("status")
    private PetStatus status;

    @QueryParam("tag")
    private List<String> tags;

    @HeaderParam("X-Request-Id")
    private UUID requestId;

    @BeanParam
    private Paging paging;

    public PetStatus getStatus() {
        return status;
    }

    public List<String> getTags() {
        return tags;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public Paging getPaging() {
        return paging;
    }
}
