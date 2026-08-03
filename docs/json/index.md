---
title: Fory JSON
sidebar_position: 1
id: index
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

Fory JSON is Apache Fory's thread-safe Java JSON codec. It provides interpreted and
runtime-generated codecs for Java objects, records, immutable creator-based classes, common JDK
types, generic containers, custom complete-value codecs, and finite annotation-declared
polymorphism.

Fory JSON is a separate data format from Fory's binary native and xlang protocols. Use it when a
system must exchange ordinary JSON with browsers, APIs, logs, configuration, or another JSON
implementation. Use the Fory binary protocol when you need cross-language schema metadata,
reference identity, circular graphs, or Fory's binary-only features.

## Documentation map

| Goal                                                             | Page                                  |
| ---------------------------------------------------------------- | ------------------------------------- |
| First runnable JSON round trip                                   | [Getting Started](getting-started.md) |
| Understand Java object mapping and configuration                 | [Object Mapping](object-mapping.md)   |
| Configure properties, creators, values, validators, and subtypes | [Annotations](annotations.md)         |
| Extend complete values, children, and map keys                   | [Custom Codecs](custom-codecs.md)     |
| Deploy on Android                                                | [Android](android.md)                 |
| Build a GraalVM native image                                     | [GraalVM Native Image](graalvm.md)    |
| Decode input safely                                              | [Security](security.md)               |
| Diagnose failures                                                | [Troubleshooting](troubleshooting.md) |

## Limits and unsupported features

Fory JSON intentionally has a smaller semantic surface than the Fory binary protocol and general
Jackson object mapping:

- no shared-reference identity or circular-reference protocol;
- no open polymorphism, JSON class-name IDs, runtime subtype discovery, or runtime subtype table
  extension;
- no `InputStream` parser or incremental `OutputStream` writer on the `ForyJson` root API;
- no pretty-print configuration;
- no Jackson/Gson annotation compatibility layer;
- no aliases, views, filters, injection, managed/back references, object identity annotations, or
  root wrapping;
- no Fory core `Expose` processing.

Circular graphs eventually fail `maxDepth`; they are not reconstructed. Use Fory core's binary
native or xlang protocol when reference identity or cycles are required.

## Related Java guides

For binary serialization, start with [Java Object Serialization](../object-serialization/java/index.md)
and choose [xlang](../object-serialization/java/xlang.md) or
[native](../object-serialization/java/native.md). Binary builder options are documented separately
in [Java Configuration](../object-serialization/java/configuration.md).
