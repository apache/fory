---
title: C# Setup
sidebar_position: 7
id: csharp
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

Fory C# provides xlang Object Serialization, generated models, and Fory gRPC.
The `Apache.Fory` NuGet package requires .NET 8 or later and includes both the
serialization library and source generator.

## Verify the Toolchain

```bash
dotnet --version
```

## Object Serialization

Create a console project and add the released package:

```bash
dotnet new console -n ForyExample
cd ForyExample
dotnet add package Apache.Fory --version 1.6.1
```

Replace `Program.cs` with:

```csharp
using Apache.Fory;

[ForyStruct]
public sealed class User
{
    public long Id { get; set; }
    public string Name { get; set; } = string.Empty;
}

public static class Program
{
    public static void Main()
    {
        Fory fory = Fory.Builder().Build();
        fory.Register<User>(1);

        byte[] bytes = fory.Serialize(new User { Id = 1, Name = "Alice" });
        User decoded = fory.Deserialize<User>(bytes);
        Console.WriteLine(decoded.Name);
    }
}
```

```bash
dotnet run
```

C# uses xlang mode. Continue with
[C# Object Serialization](../object-serialization/csharp/index.md),
[xlang types](../object-serialization/csharp/basic-serialization.md#cross-language-interoperability),
[configuration](../object-serialization/csharp/configuration.md), and
[schema evolution](../object-serialization/csharp/schema-evolution.md).

## Other Capabilities

- **Fory IDL and Compiler** generates C# models and registration helpers. See [Compiler Getting Started](../compiler/getting-started.md) and the [C# generated-code guide](../compiler/generated-code/csharp.md).
- **Fory gRPC** uses normal .NET gRPC transports with Fory-encoded messages. See [C# gRPC](../grpc/csharp.md).
