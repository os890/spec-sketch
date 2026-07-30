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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Self-referencing: 'children' closes a cycle onto Category itself. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Category {

    @NotNull
    private Long id;

    @NotNull
    @Size(min = 1, max = 60)
    private String name;

    private List<Category> children;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<Category> getChildren() {
        return children;
    }
}
