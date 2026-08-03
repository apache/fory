---
title: Polymorphism
sidebar_position: 6
id: polymorphism
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

Xlang polymorphism preserves a value's concrete registered type when a field, element, or root is
declared through a broader type. Every peer must agree on the concrete type identity and compatible
field schema.

## Runtime rules

Use the selected runtime's schema metadata, registration, and polymorphism pages for exact syntax.
The [xlang serialization specification](../../specification/xlang_serialization_spec.md) defines
the shared behavior and limitations.

Do not infer xlang support from host-language inheritance alone. A concrete subtype must have a
portable xlang mapping and coordinated registration on every peer that can receive it.
