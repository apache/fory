---
title: C#
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

## Output Layout

C# output is one `.cs` file per schema, for example:

- `<csharp_out>/addressbook/Addressbook.cs`

The C# model file name uses the normalized PascalCase source file stem. For
example, `service.fdl` generates `Service.cs`, `order-events.fdl` generates
`OrderEvents.cs`, and `123-schema.fdl` generates `Schema123Schema.cs`.

## Type Generation

Messages generate `[ForyStruct]` classes with C# properties and byte helpers:

```csharp
[ForyStruct]
public sealed partial class Person
{
    public string Name { get; set; } = string.Empty;
    public int Id { get; set; }
    public List<Person.PhoneNumber> Phones { get; set; } = new();
    public Animal Pet { get; set; } = null!;

    public byte[] ToBytes() { ... }
    public static Person FromBytes(byte[] data) { ... }
}
```

Unions generate `[ForyUnion]` ADTs. `Unknown(UnknownCase)` is the
Fory-provided forward-compatibility carrier marked with `[ForyUnknownCase]`.
The marker only selects the carrier and does not add an entry to the schema case
table. Schema-defined cases use non-negative `[ForyCase]` IDs. If a case needs
non-default schema encoding, the generated `[ForyCase]` carries `Type`. Known
case record names are PascalCase FDL case names; payload types are emitted as
qualified references when needed to avoid name conflicts. A typed union must
have at least one non-`Unknown` case.

```csharp
[ForyUnion]
public abstract partial record Animal
{
    private Animal() {}

    [ForyUnknownCase]
    public sealed partial record Unknown(UnknownCase Value) : Animal;

    [ForyCase(0)]
    public sealed partial record Dog(global::addressbook.Dog Value) : Animal;

    [ForyCase(1)]
    public sealed partial record Cat(global::addressbook.Cat Value) : Animal;
}
```

## Module Installation

Each schema generates a module class that installs imported modules first and
then registers the local schema types:

```csharp
public static class AddressbookForyModule
{
    public static void Install(Fory fory)
    {
        fory.Register<addressbook.Animal>((uint)106);
        fory.Register<addressbook.Person>((uint)100);
        // ...
    }
}
```

The C# model file basename and module class both use the normalized source file
stem. They do not use `csharp_namespace` and they do not use gRPC service names.
For example, `service.fdl` generates `Service.cs` and `ServiceForyModule`,
while `order-events.fdl` generates `OrderEvents.cs` and
`OrderEventsForyModule`. A gRPC service named `Greeter` generates the service
companion `GreeterGrpc.cs`; it does not change the schema module name. To get
`GreeterForyModule`, name the schema file `greeter.fdl` or `Greeter.fdl`.

This source-file rule lets several schemas target the same C# namespace without
colliding. No namespace-derived or service-derived module alias is generated.

When explicit type IDs are not provided, generated installation uses computed
numeric IDs (same behavior as other targets).

## gRPC Service Companions

With `--grpc`, C# emits one `<ServiceName>Grpc.cs` per service. Its static service holder contains descriptors, `<ServiceName>Base`, `<ServiceName>Client`, and both `BindService` overloads. See [C# gRPC](../../grpc/csharp.md) for hosting, clients, dependencies, and streaming.
