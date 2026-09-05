---
title: Troubleshooting
sidebar_position: 12
id: troubleshooting
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

| Symptom                                    | Likely cause and action                                                                                                                             |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ForyJsonException` while parsing          | Invalid JSON grammar, type mismatch, unsupported mapping, depth or graph-memory violation, validator failure, or trailing content                   |
| `InsecureException`                        | Fory's disallow list or the configured `JsonTypeChecker` rejected a class                                                                           |
| `IllegalArgumentException` from a builder  | Check the configured depth, graph-memory, concurrency, retained-buffer, and cached-field-name limits                                                |
| Declared write is rejected                 | The value is not assignable to the declared type, the type contains a wildcard/type variable, or null was supplied for a primitive                  |
| Immutable value is not populated           | Use a record, a valid `JsonCreator`, or an exact custom codec                                                                                       |
| `JsonValue` read fails                     | Add one plain `String` `JsonCreator`, or register an exact custom codec                                                                             |
| Raw JSON output is invalid                 | Supply exactly one trusted, complete JSON value to the `JsonRawValue` property                                                                      |
| Ordinary object cannot be constructed      | Add a usable no-argument constructor, use a record or `JsonCreator`, or register a custom codec; Android and GraalVM native image are stricter      |
| Ordinary accessor annotation fails         | The method is not an eligible public JavaBean accessor, or field mode is enabled                                                                    |
| Any annotation fails                       | Use exactly one field-backed form or one valid method-backed pair with resolved `Map<String, V>` types; method annotations require non-field mode   |
| Codec annotation fails                     | Resolve same-node or hierarchy conflicts, remove a hidden nested override, or use a public no-argument codec class                                  |
| Subtype is rejected                        | The base is not declared on the write, the runtime class is not an exact table entry, or the input wire shape differs from the configured inclusion |
| Collection cannot be read                  | Target a supported interface/common implementation or register a custom codec                                                                       |
| OutputStream write fails                   | The underlying `IOException` is wrapped as the cause of `ForyJsonException`                                                                         |
| Kotlin null or missing member fails        | Check the exact `jsonTypeRef`, constructor default, and nullable occurrence; null does not request a compiler default                               |
| Raw/star/projected Kotlin generic fails    | Supply a complete `jsonTypeRef<T>()`; `in` and star projections cannot reconstruct one exact schema                                                 |
| Unsupported Kotlin metadata                | Ensure the resolved `kotlin-metadata-jvm` supports the model compiler's metadata and that validated JVM members match it                            |
| Kotlin model fails after Android shrinking | Apply KSP; for an exact Mixin, use it when either its source or target is Kotlin, and verify that the generated rules are packaged                  |
| Kotlin model is absent in Native Image     | Install `ForyJsonKotlin` from a reachable `ForyJsonProvider`, enable code generation, and make the exact binding reachable from that configuration  |

Fory JSON mapping, syntax, codec, depth, graph-memory, validator, and output failures use
`ForyJsonException`. User codec code may still throw its own runtime exception. Creator and
validator failures other than `Error` are wrapped with their original cause.
