// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import "@typespec/http";

using Http;

/**
 * Same petstore API as yaml-first/src/main/openapi/petstore.yaml,
 * but written in TypeSpec and compiled to OpenAPI during the Maven build.
 */
@service(#{ title: "Petstore Demo API" })
namespace Petstore;

enum PetStatus {
  AVAILABLE,
  PENDING,
  SOLD,
}

model Category {
  id: int64;
  name: string;
}

model Pet {
  id: int64;

  @minLength(1)
  @maxLength(100)
  name: string;

  status: PetStatus;
  category?: Category;
  tags?: string[];
  createdAt?: utcDateTime;
}

// reuse instead of copy/paste: NewPet = Pet without server-managed fields,
// and status becomes optional (the server defaults it)
model NewPet {
  ...OmitProperties<Pet, "id" | "createdAt" | "status">;
  status?: PetStatus;
}

model ApiError {
  code: int32;
  message: string;
}

model NotFoundError {
  @statusCode statusCode: 404;
  @body error: ApiError;
}

model BadRequestError {
  @statusCode statusCode: 400;
  @body error: ApiError;
}

model PetCreated {
  @statusCode statusCode: 201;
  @body pet: Pet;
}

@route("/pets")
interface Pets {
  @get list(@query status?: PetStatus): Pet[];

  @get read(@path petId: int64): Pet | NotFoundError;

  @post create(@body pet: NewPet): PetCreated | BadRequestError;
}
