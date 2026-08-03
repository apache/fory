---
title: Custom Codecs
sidebar_position: 5
id: custom-codecs
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

`JsonValueCodec<T>` is Fory JSON's streaming codec SPI for one complete JSON value. It writes
directly to Fory's String or UTF-8 writer and reads directly from Fory's Latin-1, UTF-16, or UTF-8
reader. It is not a JSON abstract syntax tree (AST) or `JsonNode` codec. It owns the complete value,
including JSON null, but never handles a Map key; `MapKeyCodec` remains responsible for JSON object
member names.

For an application codec with the same semantics in every representation, extend
`AbstractJsonValueCodec<T>` and implement the JSON shape once:

```java
import java.math.BigDecimal;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;

public final class MoneyCodec extends AbstractJsonValueCodec<Money> {
  @Override
  public void write(JsonWriter writer, Money value) {
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeBigDecimal(value.amount);
    }
  }

  @Override
  public Money read(JsonReader reader) {
    return reader.tryReadNullToken() ? null : new Money(reader.readBigDecimal());
  }
}

final class Money {
  final BigDecimal amount;

  Money(BigDecimal amount) {
    this.amount = amount;
  }
}
```

`AbstractJsonValueCodec` adds one virtual method call per operation. For a
performance-sensitive codec, or when behavior depends on a concrete reader or writer, implement
`JsonValueCodec<T>` directly and provide all five representation-specific methods.

```java
import org.apache.fory.json.ForyJson;

ForyJson json =
    ForyJson.builder()
        .registerCodec(Money.class, new MoneyCodec())
        .build();
```

The containing property still controls its name, ignore direction, and null-inclusion policy. If a
null property is omitted, the value codec is not called. If the property is emitted, or the value
is an array element, collection element, map value, Optional value, or atomic-reference value, the
codec receives and owns null. The registered instance is shared across concurrent operations and
must be thread-safe.

A custom codec that materializes composite graph owners must call `JsonReader.reserveGraphMemory`
with its application-defined byte estimate before creating each owner. This applies to composite
application objects, collections, maps, and reference arrays. Reserve collection and map reference
storage before the mutation that may grow it. A custom scalar or other dedicated leaf
representation, such as `MoneyCodec` above, does not reserve graph memory. Complete codecs also
perform their own application validation; Fory JSON does not wrap a complete custom representation
with the target type's `JsonValidator` methods.

Registering a custom codec for a `JsonSubTypes` base replaces that base's subtype annotation.
Registering one for a listed subtype is supported by the two wrapper inclusions but not by inline
property inclusion.

## Selecting Codecs with `JsonCodec`

Use `@JsonCodec` on a class, record, enum, or interface to declare its default complete-value
codec. The positional form is shorthand for `value`:

```java
import org.apache.fory.json.annotation.JsonCodec;

@JsonCodec(MoneyCodec.class)
public final class Money {}

@JsonCodec(AccountCodec.class)
public interface Account {}

public final class RetailAccount implements Account {}
```

Type declarations are inherited through both superclasses and interfaces. The most-specific
declaration wins. Unrelated declarations using the same codec are consistent; unrelated
declarations using different codecs fail instead of depending on reflection order.

On a field or effective ordinary getter, `value` replaces the complete property value. The same
annotation is supported on an effective setter value parameter, a `JsonCreator` constructor or
factory parameter, and a record component through Java's field, accessor, and constructor-parameter
propagation:

```java
public final class Invoice {
  @JsonCodec(MoneyCodec.class)
  public Money total;
  private Money tax;
  private Money discount;

  @JsonCodec(MoneyCodec.class)
  public Money getTax() {
    return tax;
  }

  public void setDiscount(@JsonCodec(MoneyCodec.class) Money discount) {
    this.discount = discount;
  }

  @JsonCreator
  public Invoice(@JsonProperty("total") @JsonCodec(MoneyCodec.class) Money total) {
    this.total = total;
  }
}
```

Use a child member when the standard container should remain in control and only its direct child
needs a custom codec:

```java
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class InvoiceGroup {
  @JsonCodec(elementCodec = MoneyCodec.class)
  public List<Money> items;

  @JsonCodec(elementCodec = MoneyCodec.class)
  public Money[] itemArray;

  @JsonCodec(elementCodec = MoneyCodec.class)
  public AtomicReferenceArray<Money> atomicItems;

  @JsonCodec(contentCodec = MoneyCodec.class)
  public Optional<Money> optional;

  @JsonCodec(contentCodec = MoneyCodec.class)
  public AtomicReference<Money> current;

  @JsonCodec(keyCodec = CurrencyKeyCodec.class, valueCodec = MoneyCodec.class)
  public Map<Currency, Money> byCurrency;
}
```

The child members have these meanings:

| Member         | Supported current value                           | Direct child handled by the codec |
| -------------- | ------------------------------------------------- | --------------------------------- |
| `elementCodec` | `Collection<E>`, `E[]`, `AtomicReferenceArray<E>` | `E`                               |
| `contentCodec` | `Optional<T>`, `AtomicReference<T>`               | `T`                               |
| `keyCodec`     | `Map<K, V>`                                       | JSON member name for `K`          |
| `valueCodec`   | `Map<K, V>`                                       | direct `V` value                  |

A custom Map-key codec converts between the declared key and a JSON member name:

```java
import java.util.Locale;
import org.apache.fory.json.codec.MapKeyCodec;

public final class CurrencyKeyCodec implements MapKeyCodec {
  @Override
  public String toName(Object key) {
    return ((Currency) key).name().toLowerCase(Locale.ROOT);
  }

  @Override
  public Object fromName(String name) {
    return Currency.valueOf(name.toUpperCase(Locale.ROOT));
  }
}
```

Code that used the removed type-use form should move the codec to the owning declaration:

```java
// Before
List<@JsonCodec(MoneyCodec.class) Money> items;

// Now
@JsonCodec(elementCodec = MoneyCodec.class)
List<Money> items;
```

Use `contentCodec` for an `Optional` or `AtomicReference`, `valueCodec` for a Map value, and
`elementCodec` for an array or `AtomicReferenceArray` element.

`Iterable<E>` values that are not `Collection<E>` do not support `elementCodec`. Use `value` when a
complete codec should own such a value.

Child configuration is intentionally one level deep. For `List<List<Money>>`, `elementCodec`
handles each complete `List<Money>`. For `Money[][]`, it handles each `Money[]`. To customize a
deeper descendant, implement a codec for the complete current value and select it with `value`.

`value` is mutually exclusive with every child member because it already owns the complete current
value. An empty annotation, an unsupported child member, or an outer complete codec combined with
a child member fails during model construction. A configured direct child must resolve to a
concrete type; raw containers, direct wildcards, and unresolved direct type variables are rejected.

`JsonAnyProperty` and `JsonAnyGetter` flatten their Map into the enclosing object. Configure their
dynamic values with `valueCodec`:

```java
@JsonAnyProperty
@JsonCodec(valueCodec = MoneyCodec.class)
public Map<String, Money> extra;
```

The first `JsonAnySetter` parameter is the String property name. Its second parameter may use
`@JsonCodec(value = ...)` or another configuration valid for that parameter's own shape.

## Codec Precedence and Repeated Declarations

Fory resolves each current value in this order:

| Priority | Source                                            |
| -------: | ------------------------------------------------- |
|        1 | Current property or parameter `JsonCodec`         |
|        2 | Exact `registerCodec` registration                |
|        3 | Exact-target type `JsonCodec` after Mixin overlay |
|        4 | Inherited-frontier type `JsonCodec` declaration   |
|        5 | Built-in or default JSON mapping                  |

One logical property may expose the annotation from its field, getter, setter parameter, creator
parameter, or record propagation. Repeated configurations must be identical; Fory does not merge
partial configurations from different declarations. An unannotated effective override suppresses
an inherited method annotation.

A child member replaces only that direct child. Unconfigured Map siblings continue through the
normal precedence. If an exact registration or type declaration supplies a complete codec for the
outer container, a property child member is unreachable and therefore rejected.

Map keys are JSON object member names and use `MapKeyCodec`, not `JsonValueCodec`. A custom key
codec class follows the same construction rules as a value codec. Null Map keys are rejected, and
decoded keys must match the declared key type.

## Codec Construction and Platform Support

An annotation codec class must be public, concrete, top-level or static nested, and have a public
no-argument constructor. One instance is shared by all annotated sites and concurrent operations of
the built `ForyJson`, so it must be thread-safe. Use `registerCodec(Target.class, instance)` when a
complete-value codec needs configuration.

Outside GraalVM Native Image, a named Java module must export or open the codec package to
`org.apache.fory.json`. Native Image prepares annotation-codec constructors during image
construction and does not require that package access. When an inherited type-declaration codec is
used for a more specific target, every decoded value must be null or assignable to that target.

The annotation has the same FIELD, METHOD, and PARAMETER behavior on the JVM, Android, and GraalVM
Native Image. Ordinary Android classes may omit `JsonType` and provide equivalent exact rules.
Android-desugared Records, including `JsonValue` Records, require processor-generated operations
from either a direct `JsonType` declaration or a compiled exact `JsonMixin` pair. GraalVM object
models follow the build-time workflow in the
[GraalVM guide](graalvm.md).
