/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import Fory, { BinaryReader, Type } from "../packages/core/index";
import { ConfigFlags, RefFlags, TypeId } from "../packages/core/lib/type";
import { describe, expect, test } from "@jest/globals";

describe("enum", () => {
  test("should javascript number enum work", () => {
    const Foo = {
      f1: 1,
      f2: 2,
    };
    const fory = new Fory({ compatible: false, ref: true });
    const { serialize, deserialize } = fory.register(Type.enum("example.foo", Foo));
    const input = serialize(Foo.f1);
    const result = deserialize(input);
    expect(result).toEqual(Foo.f1);
  });

  test("should javascript string enum work", () => {
    const Foo = {
      f1: "hello",
      f2: "world",
    };
    const fory = new Fory({ compatible: false, ref: true });
    fory.register(Type.enum("example.foo", Foo));
    const input = fory.serialize(Foo.f1);
    const result = fory.deserialize(input);
    expect(result).toEqual(Foo.f1);
  });
  test("should typescript number enum work", () => {
    enum Foo {
      f1 = 1,
      f2 = 2,
    }
    const fory = new Fory({ compatible: false, ref: true });
    const { serialize, deserialize } = fory.register(Type.enum("example.foo", Foo));
    const input = serialize(Foo.f1);
    const result = deserialize(input);
    expect(result).toEqual(Foo.f1);
  });

  test("should preserve sparse numeric enum values", () => {
    const Foo = {
      unknown: 4096,
      ok: 8192,
    };
    const fory = new Fory({ compatible: false, ref: true });
    const { serialize, deserialize } = fory.register(Type.enum("example.foo", Foo));
    const input = serialize(Foo.ok);
    const result = deserialize(input);
    expect(result).toEqual(Foo.ok);
  });

  test("keeps enums out of reference tracking", () => {
    const Foo = {
      first: 1,
      second: 2,
    };
    const fory = new Fory({ compatible: false, ref: true });
    const enumType = Type.enum(101, Foo).setTrackingRef(true);
    const enumSerializer = fory.register(enumType);
    expect(enumSerializer.serializer.needToWriteRef()).toBe(false);

    const rootBytes = enumSerializer.serialize(Foo.first);
    const reader = new BinaryReader({});
    reader.reset(rootBytes);
    expect(reader.readUint8()).toBe(ConfigFlags.isCrossLanguageFlag);
    expect(reader.readInt8()).toBe(RefFlags.NotNullValueFlag);
    expect(reader.readUint8()).toBe(TypeId.ENUM);

    const nodeType = Type.struct(102, {
      value: Type.int32(),
    });
    const sequenceSerializer = fory.register(
      Type.struct(103, {
        marker: enumType.clone().setTrackingRef(true).setId(1),
        first: nodeType.clone().setTrackingRef(true).setId(2),
        second: nodeType.clone().setTrackingRef(true).setId(3),
      }),
    );
    const shared = { value: 7 };
    const result = sequenceSerializer.deserialize(
      sequenceSerializer.serialize({
        marker: Foo.first,
        first: shared,
        second: shared,
      }),
    ) as {
      marker: number;
      first: { value: number };
      second: { value: number };
    };

    expect(result.marker).toBe(Foo.first);
    expect(result.first).toBe(result.second);
    expect(result.first).toEqual(shared);
  });

  test("should typescript string enum work", () => {
    enum Foo {
      f1 = "hello",
      f2 = "world",
    }
    const fory = new Fory({ compatible: false, ref: true });
    fory.register(Type.enum("example.foo", Foo));
    const input = fory.serialize(Foo.f1);
    const result = fory.deserialize(input);
    expect(result).toEqual(Foo.f1);
  });
});
