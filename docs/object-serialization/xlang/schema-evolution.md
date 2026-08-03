---
title: Schema Evolution
sidebar_position: 3
id: schema-evolution
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

Xlang compatible mode carries schema metadata so readers can tolerate supported field additions,
removals, and reordering. Same-schema mode reduces metadata only when every reader and writer uses
the same schema.

## Choose compatible or same-schema mode

Keep compatible mode for independently deployed peers. Use same-schema mode only after verifying
that every runtime uses the same field identities, nullability, reference metadata, and types.

Runtime APIs and examples live in each runtime's `schema-evolution.md` page. Normative schema
metadata and compatibility behavior are defined by the
[xlang serialization specification](../../specification/xlang_serialization_spec.md).
