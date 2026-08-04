---
title: Architecture
sidebar_position: 2
id: architecture
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

Generated service companions use normal gRPC servers, channels, method descriptors, deadlines,
status codes, interceptors, and streaming APIs. Fory-generated marshallers encode and decode the
generated request and response models.

## Ownership boundary

Fory can generate service companions for application-provided gRPC runtimes.
Those companions provide Fory serialization for request and response objects;
the application and gRPC stack still own listeners, channels, credentials,
authentication, authorization, deadlines, retries, and transport lifecycle.

Fory packages do not add a gRPC implementation as a hard dependency. The application selects and
configures the runtime's gRPC libraries.

## Generated service surface

The compiler emits runtime-idiomatic service bases, clients or stubs, method metadata, and Fory
marshallers. Model generation is documented under
[Generated Code](../compiler/generated-code/index.md); the runtime pages document server and client
integration.
