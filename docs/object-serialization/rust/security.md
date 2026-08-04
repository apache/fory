---
title: Security
sidebar_position: 99
id: security
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

Use this page when a Rust reader accepts bytes from outside the application's trust boundary.
Fory reconstructs application values; it does not authenticate the sender, protect transport
integrity, or decide whether a valid value is authorized for a business operation.

## Application boundary

Before deserialization:

- Authenticate the sender and protect message integrity at the transport or storage layer.
- Enforce request or file size, timeout, and concurrency limits outside Fory.
- Register only the application types the endpoint accepts and configure the reader before its
  first root operation.
- Validate the deserialized value against application authorization and domain rules before use.

## Built-in safeguards

Security-related configuration:

- Register application structs and trait-object implementations before deserializing untrusted
  payloads.
- Use `max_dyn_depth(...)` to reject unexpectedly deep dynamic object graphs.
- Keep `max_graph_memory_bytes(...)` at the fixed `128 MiB` default for most inputs, or set a
  positive byte gate for trusted workloads with different legitimate collection/map/struct sizes.
- Keep `max_unbacked_container_items(...)` at `8192` unless trusted compact codecs require a
  larger root allowance. Zero rejects every unbacked item.
- Keep the remote schema metadata limits at their defaults unless the data is not malicious and a
  trusted peer sends larger metadata or many schema versions.
- Prefer concrete typed fields over `dyn Any` or broad trait-object fields for untrusted input.

## Verification

Add negative tests for the boundary as well as normal round trips. Verify that the configured reader
rejects unexpected application types, excessive nesting, resource-limit violations, and malformed
input. After a failed read, verify that a valid root can still be read with the same Fory instance.

See [Configuration](configuration.md) for the complete option reference and
[Type Registration](type-registration.md) for the Fory registration API.
