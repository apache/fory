---
title: Benchmark Methodology
sidebar_position: 2
id: methodology
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

A useful serialization benchmark records enough context to reproduce the workload and avoid
comparing different semantics as if they were equivalent.

## Required context

- repository commit and dependency versions;
- hardware, operating system, runtime version, and relevant environment variables;
- schema and object population;
- xlang or native mode, compatibility mode, reference tracking, and registration;
- warm-up, forks/processes, iterations, duration, and concurrency;
- serialized representation and operation measured;
- exact command and raw result location.

## Interpret the reports

Compare one operation and equivalent data semantics. Do not compare an xlang payload with a native
payload or a full object reconstruction with Row Format field access without naming that semantic
difference. JIT- and codegen-based implementations require representative warm-up.

Run the active benchmark harness for decisions that depend on current code. Checked-in reports are
evidence for the commit and environment they record.
