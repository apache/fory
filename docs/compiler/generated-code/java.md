---
title: Java
sidebar_position: 2
id: java
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

For `package addressbook`, Java output is generated under:

- `<java_out>/addressbook/`
- Type files: `AddressBook.java`, `Person.java`, `Dog.java`, `Cat.java`, `Animal.java`
- Schema module: `AddressbookForyModule.java`

For schemas without a Java package, the schema module name is derived from the
source file stem, for example `main.fdl` generates `MainForyModule.java`.
Java import graphs cannot mix default-package schemas with named Java packages.

## Type Generation

Messages generate Java classes with `@ForyField`, default constructors, getters/setters, and byte helpers:

```java
public class Person {
    public static enum PhoneType {
        MOBILE,
        HOME,
        WORK;
    }

    public static class PhoneNumber {
        @ForyField(id = 1)
        private String number;

        @ForyField(id = 2)
        private PhoneType phoneType;

        public byte[] toBytes() { ... }
        public static PhoneNumber fromBytes(byte[] bytes) { ... }
    }

    @ForyField(id = 1)
    private String name;

    @ForyField(id = 8)
    private Animal pet;

    public byte[] toBytes() { ... }
    public static Person fromBytes(byte[] bytes) { ... }
}
```

Messages with `evolving=false` are generated with Java fixed-schema struct encoding.

Unions generate classes extending `org.apache.fory.type.union.Union`:

```java
public final class Animal extends Union {
    public enum AnimalCase {
        DOG(1),
        CAT(2);
        public final int id;
        AnimalCase(int id) { this.id = id; }
    }

    public static Animal ofDog(Dog v) { ... }
    public AnimalCase getAnimalCase() { ... }
    public int getAnimalCaseId() { ... }

    public boolean hasDog() { ... }
    public Dog getDog() { ... }
    public void setDog(Dog v) { ... }
}
```

## Schema Module

Each JVM schema generates a `ForyModule`. Imported schema modules are installed
through `fory.register(...)`, so shared imports are deduplicated by the Fory instance.

```java
public final class AddressbookForyModule implements org.apache.fory.ForyModule {
  public static final AddressbookForyModule INSTANCE = new AddressbookForyModule();

  static ThreadSafeFory getFory() { ... }

  @Override
  public void install(Fory fory) {
    org.apache.fory.resolver.TypeResolver resolver = fory.getTypeResolver();
    resolver.registerUnion(Animal.class, 106L, new org.apache.fory.serializer.UnionSerializer(resolver, Animal.class));
    resolver.register(Person.class, 100L);
    resolver.register(Person.PhoneType.class, 101L);
    resolver.register(Person.PhoneNumber.class, 102L);
    resolver.register(Dog.class, 104L);
    resolver.register(Cat.class, 105L);
    resolver.register(AddressBook.class, 103L);
  }
}
```

For schemas without explicit `[id=...]`, generated registration uses computed numeric IDs (for example from `auto_id.fdl`):

```java
resolver.register(Status.class, 1124725126L);
resolver.registerUnion(Wrapper.class, 1471345060L, new org.apache.fory.serializer.UnionSerializer(resolver, Wrapper.class));
resolver.register(Envelope.class, 3022445236L);
resolver.registerUnion(Envelope.Detail.class, 1609214087L, new org.apache.fory.serializer.UnionSerializer(resolver, Envelope.Detail.class));
resolver.register(Envelope.Payload.class, 2862577837L);
```

If `option enable_auto_type_id = false;` is set, registration uses symbolic names:

```java
resolver.register(Config.class, "myapp.models", "Config");
resolver.registerUnion(
    Holder.class,
    "myapp.models",
    "Holder",
    new org.apache.fory.serializer.UnionSerializer(resolver, Holder.class));
```

## Usage

```java
Person person = new Person();
person.setName("Alice");
person.setPet(Animal.ofDog(new Dog()));

byte[] data = person.toBytes();
Person restored = Person.fromBytes(data);
```

## gRPC Service Companions

With `--grpc`, Java emits one `<ServiceName>Grpc.java` per service. It exposes `SERVICE_NAME`, service and method descriptors, `<ServiceName>ImplBase`, and async, blocking, and future stub factories. See [Java gRPC](../../grpc/java.md) for dependencies and server, client, and streaming usage.
