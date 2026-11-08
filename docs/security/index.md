---
title: Security
sidebar_position: 1
---

<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

This directory documents Apache Fory security models and security invariants. It
is not a vulnerability disclosure area and does not list CVE details, exploit
samples, issue timelines, or implementation history.

Security model documents describe how Fory should classify and prevent security
risks while preserving the performance characteristics expected from Fory
serialization implementations.

## Models

- [Threat Model](threat-model.md): project-level trust boundaries, non-goals,
  and downstream responsibilities.
- [Deserialization Security Model](deserialization.md): concrete rules for
  classifying and preventing untrusted deserialization risks.

For vulnerability reporting, see the repository
[security policy](../../SECURITY.md).
