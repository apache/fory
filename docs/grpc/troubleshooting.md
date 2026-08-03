---
title: Troubleshooting
sidebar_position: 20
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

Use the selected runtime guide for dependency- and API-specific diagnostics.

## `UNIMPLEMENTED`

Confirm that the client and server use the same generated service name and method descriptors and
that the generated service was registered with the server.

## A protobuf client cannot decode the service

Fory gRPC uses Fory message bytes. Generate a Fory gRPC peer from the same contract or expose a
separate ordinary protobuf service.

## A generated file does not compile

Check the runtime page's required gRPC packages and versions, then regenerate all model and service
files with the same compiler version.
