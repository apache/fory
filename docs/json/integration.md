---
title: Integration
sidebar_position: 13
id: integration
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

## Spring Fory

[spring-fory](https://github.com/chaokunyang/spring-fory) provides Spring MVC message converters,
Spring WebFlux codecs, and Spring Boot auto-configuration starters for Fory JSON. It supports
ordinary JSON request and response bodies, with streaming JSON and NDJSON support in WebFlux.

See the project's [installation and usage guide](https://github.com/chaokunyang/spring-fory#installation)
to select the adapter or starter matching your Spring version and configure your application.

## Kotlin integration

Use `jsonTypeRef<Any?>(kType)` when a framework supplies a Kotlin `KType` at runtime and the
callback cannot use a reified type argument. The supplied `KType` determines the complete JSON
type, including nested generic arguments and nullability. `Any?` is only the callback's static
view of the value; it does not replace the supplied type with a dynamic JSON schema.

Obtaining a `KType` from a Kotlin function or a Java `Method` requires the application's
`kotlin-reflect` dependency. Match its version to the application's Kotlin version:

```kotlin
dependencies {
  implementation(kotlin("reflect"))
}
```

For example, discover and retain a controller method's response type:

```kotlin
import java.io.OutputStream
import kotlin.reflect.jvm.kotlinFunction
import org.apache.fory.json.kotlin.ForyJsonKotlin
import org.apache.fory.json.kotlin.jsonTypeRef

data class Employee(val id: Long, val name: String)
data class Response<T>(val flag: Boolean, val data: T? = null, val msg: String? = null)

class EmployeeController {
  fun employees(): Response<List<Employee>> =
    Response(true, listOf(Employee(1, "Alice")))
}

val json = ForyJsonKotlin.builder().build()
val method = EmployeeController::class.java.getMethod("employees")
val responseType = jsonTypeRef<Any?>(requireNotNull(method.kotlinFunction).returnType)

fun writeResponse(value: Any?, output: OutputStream) {
  json.writeJsonTo(value, responseType, output)
}

fun readResponse(bytes: ByteArray): Any? = json.fromJson(bytes, responseType)
```

Discover each declared type once and reuse its token. For request bodies, obtain the corresponding
Kotlin value parameter's `KType`. A method with unresolved type parameters still needs its concrete
type arguments before conversion; star projections and contravariant projections remain unsupported.
If you select a static type more specific than `Any?`, the caller must ensure it matches the
supplied `KType`.

For Spring MVC, retain the controller's Kotlin declaration when adapting the request or response
to a converter. A `SmartHttpMessageConverter` can receive application-provided read/write hints
containing that `KType` or its Fory type token. When an HTTP wrapper such as
`ResponseEntity<Response<List<Employee>>>` is present, select the body type
`Response<List<Employee>>` before constructing the token.

An `AbstractHttpMessageConverter` callback that supplies only the runtime object loses generic
arguments. `AbstractGenericHttpMessageConverter` preserves Java generics, but
`TypeRef.of(javaType)` does not restore Kotlin nullability. Neither a Java `Type` nor the runtime
value alone can recover the full Kotlin declaration. Use `jsonTypeRef(kType)` after obtaining that
declaration; this API does not automatically install a Spring converter.

## Token-level reading and writing

Frameworks that bring their own (de)serializers, for example generated at compile time, can use
Fory JSON's UTF-8 reader and writer directly instead of its object mapping. `readUtf8` and
`writeUtf8` lend a pooled reader or writer to an operation, using the `ForyJson` instance's limits,
caches and buffers:

```java
ForyJson json = ForyJson.builder().build();

byte[] bytes =
    json.writeUtf8(
        writer -> {
          writer.writeObjectStart();
          writer.writeFieldName("id");
          writer.writeInt(7);
          writer.writeObjectEnd();
        });

int id =
    json.readUtf8(
        bytes,
        0,
        bytes.length,
        reader -> {
          reader.expectNextToken('{');
          String name = reader.readFieldName(); // "id"
          reader.expect(':');
          int value = reader.readInt();
          reader.expectNextToken('}');
          return value;
        });
```

The reader and writer are only valid inside the operation. `readUtf8` rejects trailing
non-whitespace content after the operation returns.

Token methods such as `expectNextToken` do not track nesting or allocation on their own. Like a
custom codec, an external deserializer that reads untrusted input should call
`reader.enterDepth()` / `reader.exitDepth()` around each object or array and
`reader.reserveGraphMemory(bytes)` before allocating containers, so the instance's `maxDepth` and
graph-memory limits apply; see [Security](security.md). Checked exceptions thrown by the operation are
propagated unchanged. Like the other root APIs, these methods are not reentrant on the same
`ForyJson` instance.
