---
title: Troubleshooting
sidebar_position: 90
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

## A Standard Row peer cannot read Compact Row bytes

Compact Row is a Java-only row family. Use Standard Row on Java, Python, C++, and Rust for shared
bytes.

## A field lookup fails

Verify that the reader uses the same schema and field type as the encoded row. Cache typed field
handles only for rows that share that schema.

## A nested value appears to require full deserialization

Use the row, array, and map accessors directly. Call the runtime encoder's object reconstruction API
only for the subtree that must become an object.
