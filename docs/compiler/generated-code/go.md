---
title: Go
sidebar_position: 6
id: go
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

Go output path depends on schema options and `--go_out`.

For `addressbook.fdl`, `go_package` is configured and generated output follows the configured import path/package (for example under your `--go_out` root).

Without `go_package`, output uses the requested `--go_out` directory and package-derived file naming.

## Type Generation

Nested types use underscore naming by default (`Person_PhoneType`, `Person_PhoneNumber`):

```go
type Person_PhoneType int32

const (
    Person_PhoneTypeMobile Person_PhoneType = 0
    Person_PhoneTypeHome   Person_PhoneType = 1
    Person_PhoneTypeWork   Person_PhoneType = 2
)

type Person_PhoneNumber struct {
    Number    string           `fory:"id=1"`
    PhoneType Person_PhoneType `fory:"id=2"`
}
```

Messages generate structs with `fory` tags and byte helpers:

```go
type Person struct {
    Name   string               `fory:"id=1"`
    Id     int32                `fory:"id=2"`
    Phones []Person_PhoneNumber `fory:"id=7,type=list"`
    Pet    Animal               `fory:"id=8"`
}

func (m *Person) ToBytes() ([]byte, error) { ... }
func (m *Person) FromBytes(data []byte) error { ... }
```

Unions generate typed case structs with constructors/accessors/visitor APIs:

```go
type AnimalCase uint32

type Animal struct {
    case_ AnimalCase
    value any
}

func DogAnimal(v *Dog) Animal { ... }
func CatAnimal(v *Cat) Animal { ... }

func (u Animal) Case() AnimalCase { ... }
func (u Animal) AsDog() (*Dog, bool) { ... }
func (u Animal) Visit(visitor AnimalVisitor) error { ... }
```

## Registration

Generated registration function:

```go
func RegisterTypes(f *fory.Fory) error {
    if err := f.RegisterUnion(Animal{}, 106, fory.NewUnionSerializer(...)); err != nil {
        return err
    }
    if err := f.RegisterEnum(Person_PhoneType(0), 101); err != nil {
        return err
    }
    if err := f.RegisterStruct(Person_PhoneNumber{}, 102); err != nil {
        return err
    }
    if err := f.RegisterStruct(Person{}, 100); err != nil {
        return err
    }
    return nil
}
```

For schemas without explicit `[id=...]`, generated registration uses computed numeric IDs:

```go
if err := f.RegisterEnum(Status(0), 1124725126); err != nil { ... }
if err := f.RegisterUnion(Wrapper{}, 1471345060, fory.NewUnionSerializer(...)); err != nil { ... }
if err := f.RegisterStruct(Envelope{}, 3022445236); err != nil { ... }
if err := f.RegisterUnion(Envelope_Detail{}, 1609214087, fory.NewUnionSerializer(...)); err != nil { ... }
if err := f.RegisterStruct(Envelope_Payload{}, 2862577837); err != nil { ... }
```

If `option enable_auto_type_id = false;` is set:

```go
if err := f.RegisterStructByName(Config{}, "myapp.models.Config"); err != nil { ... }
if err := f.RegisterUnionByName(Holder{}, "myapp.models.Holder", fory.NewUnionSerializer(...)); err != nil { ... }
```

`go_nested_type_style` controls nested type naming:

```protobuf
option go_nested_type_style = "camelcase";
```

The CLI flag `--go_nested_type_style` overrides this schema option when both are set.

## Usage

```go
person := &Person{
    Name: "Alice",
    Pet:  DogAnimal(&Dog{Name: "Rex"}),
}

data, err := person.ToBytes()
if err != nil {
    panic(err)
}
var restored Person
if err := restored.FromBytes(data); err != nil {
    panic(err)
}
```

## gRPC Service Companions

With `--grpc`, Go emits `<module>_grpc.go` containing the generated `CodecV2`, client and server interfaces, stream types, descriptors, and registration helpers. See [Go gRPC](../../grpc/go.md) for codec configuration and usage.
