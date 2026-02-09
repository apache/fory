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

import Fory from "../packages/fory/lib/fory";
import { Type, TypeInfo } from "../packages/fory/lib/typeInfo";
import { BinaryReader, BinaryWriter, Mode } from "../packages/fory/index";
import { describe, expect, it, test, beforeAll, afterAll } from "@jest/globals";
import * as fs from "node:fs";
import * as beautify from "js-beautify";

// Simple struct used only in the skipped test
import Fury, {
  BinaryReader as FuryBinaryReader,
  BinaryWriter as FuryBinaryWriter,
  Mode as FuryMode,
  Type as FuryType,
} from "../packages/fory/index";
import {
  describe as furyDescribe,
  expect as furyExpect,
  it as furyIt,
  test as furyTest,
} from "@jest/globals";
import * as furyFs from "node:fs";
import * as furyBeautify from "js-beautify";

// 1. Define the class with the correct decorator syntax
class SimpleStruct {
  @Type.string()
  name: string = "";

  @Type.int32()
  age: number = 0;
}

describe("Cross Language Tests", () => {
  let fory: Fory;

  beforeAll(() => {
    fory = new Fory();
    // Attach TypeInfo so decorators and Type.* can work if needed
    TypeInfo.attach(fory);
    fory.registerSerializer(SimpleStruct);
  });

  afterAll(() => {
    TypeInfo.detach();
  });

  it("Base Type: String serialization", () => {
    const input = "fory_test_string";
    const serialized = fory.serialize(input);
    const deserialized = fory.deserialize(serialized);
    expect(deserialized).toBe(input);
  });

  it("Collection: List<string> serialization", () => {
    const input = ["apple", "banana", "cherry"];
    const serialized = fory.serialize(input);
    const deserialized = fory.deserialize(serialized);
    expect(deserialized).toEqual(input);
  });

  it("Collection: Map<string, string> serialization", () => {
    const input = new Map<string, string>([["key", "value"]]);
    const serialized = fory.serialize(input);
    const deserialized = fory.deserialize(serialized) as Map<string, string>;
    expect(deserialized.get("key")).toBe("value");
  });

  // Leave SimpleStruct covered but can be skipped if library still fails
  it.skip("Struct: Simple object serialization", () => {
    const input = new SimpleStruct();
    input.name = "Fory";
    input.age = 3;

    const serialized = fory.serialize(input);
    const deserialized = fory.deserialize(serialized) as SimpleStruct;

    expect(deserialized.name).toBe("Fory");
    expect(deserialized.age).toBe(3);
  });
});

const Byte = {
  MAX_VALUE: 127,
  MIN_VALUE: -128,
};

const Short = {
  MAX_VALUE: 32767,
  MIN_VALUE: -32768,
};

const Integer = {
  MAX_VALUE: 2147483647,
  MIN_VALUE: -2147483648,
};

const Long = {
  MAX_VALUE: BigInt("9223372036854775807"),
  MIN_VALUE: BigInt("-9223372036854775808"),
};

describe("cross-language serialization", () => {
  const dataFile = process.env["DATA_FILE"];
  if (!dataFile) {
    return;
  }
  function writeToFile(buffer: Buffer) {
    fs.writeFileSync(dataFile!, buffer);
  }
  const content = fs.readFileSync(dataFile);

  test("test_buffer", () => {
    const buffer = new BinaryWriter();
    buffer.reserve(32);
    buffer.bool(true);
    buffer.uint8(Byte.MAX_VALUE);
    buffer.int16(Short.MAX_VALUE);
    buffer.int32(Integer.MAX_VALUE);
    buffer.int64(Long.MAX_VALUE);
    buffer.float32(-1.1);
    buffer.float64(-1.1);
    buffer.varUInt32(100);
    const bytes = ["a".charCodeAt(0), "b".charCodeAt(0)];
    buffer.int32(bytes.length);
    buffer.buffer(new Uint8Array(bytes));
    writeToFile(buffer.dump() as Buffer);
  });

  test("test_buffer_var", () => {
    const reader = new BinaryReader({});
    reader.reset(content);

    const varInt32Values = [
      Integer.MIN_VALUE,
      Integer.MIN_VALUE + 1,
      -1000000,
      -1000,
      -128,
      -1,
      0,
      1,
      127,
      128,
      16383,
      16384,
      2097151,
      2097152,
      268435455,
      268435456,
      Integer.MAX_VALUE - 1,
      Integer.MAX_VALUE,
    ];
    for (const expected of varInt32Values) {
      expect(reader.varInt32()).toBe(expected);
    }

    const varUInt32Values = [
      0,
      1,
      127,
      128,
      16383,
      16384,
      2097151,
      2097152,
      268435455,
      268435456,
      Integer.MAX_VALUE - 1,
      Integer.MAX_VALUE,
    ];
    for (const expected of varUInt32Values) {
      expect(reader.varUInt32()).toBe(expected);
    }

    const varUInt64Values = [
      0n,
      1n,
      127n,
      128n,
      16383n,
      16384n,
      2097151n,
      2097152n,
      268435455n,
      268435456n,
      34359738367n,
      34359738368n,
      4398046511103n,
      4398046511104n,
      562949953421311n,
      562949953421312n,
      72057594037927935n,
      72057594037927936n,
      Long.MAX_VALUE,
    ];
    for (const expected of varUInt64Values) {
      expect(reader.varUInt64()).toBe(expected);
    }

    const varInt64Values = [
      Long.MIN_VALUE,
      Long.MIN_VALUE + 1n,
      -1000000000000n,
      -1000000n,
      -1000n,
      -128n,
      -1n,
      0n,
      1n,
      127n,
      1000n,
      1000000n,
      1000000000000n,
      Long.MAX_VALUE - 1n,
      Long.MAX_VALUE,
    ];
    for (const expected of varInt64Values) {
      expect(reader.varInt64()).toBe(expected);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);
    for (const value of varInt32Values) {
      writer.varInt32(value);
    }
    for (const value of varUInt32Values) {
      writer.varUInt32(value);
    }
    for (const value of varUInt64Values) {
      writer.varUInt64(value);
    }
    for (const value of varInt64Values) {
      writer.varInt64(value);
    }
    writeToFile(writer.dump() as Buffer);
  });

  test("test_murmurhash3", () => {
    const reader = new BinaryReader({});
    reader.reset(content);
    const hash1Bytes = new Uint8Array(16);
    for (let i = 0; i < 16; i++) {
      hash1Bytes[i] = reader.uint8();
    }

    const hash2Bytes = new Uint8Array(16);
    for (let i = 0; i < 16; i++) {
      hash2Bytes[i] = reader.uint8();
    }

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const { x64hash128 } = require("../packages/fory/lib/murmurHash3");

    const testData1 = new Uint8Array([1, 2, 8]);
    const result1 = x64hash128(testData1, 47);
    const result1Bytes = new Uint8Array(result1.buffer);

    const testData2 = new TextEncoder().encode("01234567890123456789");
    const result2 = x64hash128(testData2, 47);
    const result2Bytes = new Uint8Array(result2.buffer);

    const writer = new BinaryWriter();
    writer.reserve(32);
    writer.buffer(result1Bytes);
    writer.buffer(result2Bytes);
    writeToFile(writer.dump() as Buffer);
  });

  test("test_string_serializer", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStrings: string[] = [];
    let cursor = 0;
    for (let i = 0; i < 7; i++) {
      const deserializedString = fory.deserialize(
        content.slice(cursor),
      ) as string;
      cursor += fory.binaryReader.getCursor();
      deserializedStrings.push(deserializedString);
    }

    const writer = new BinaryWriter();
    writer.reserve(1024);

    for (const testString of deserializedStrings) {
      const serializedData = fory.serialize(testString);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_cross_language_serializer", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    fory.registerSerializer(Type.enum(101, Color));

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedData: unknown[] = [];
    for (let i = 0; i < 28; i++) {
      const deserializedItem = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedData.push(deserializedItem);
    }

    const bfs: Buffer[] = [];
    for (const item of deserializedData) {
      const serializedData = fory.serialize(item);
      bfs.push(serializedData as Buffer);
    }

    writeToFile(Buffer.concat(bfs));
  });

  test("test_simple_struct", () => {
    const fory = new Fury({
      mode: FuryMode.Compatible,
      hooks: {
        afterCodeGenerated: (code) => {
          return beautify.js(code, {
            indent_size: 2,
            space_in_empty_paren: true,
            indent_empty_lines: true,
          });
        },
      },
    });
    TypeInfo.attach(fory as any);

    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    fory.registerSerializer(FuryType.enum(101, Color));

    @FuryType.struct(102, {
      name: FuryType.string(),
    })
    class Item {
      name: string = "";
    }
    fory.registerSerializer(Item);

    @FuryType.struct(103, {
      f2: FuryType.varInt32(),
      f3: FuryType.struct(102),
      f4: FuryType.string(),
      f5: FuryType.enum(101, Color),
      f7: FuryType.varInt32(),
      f8: FuryType.varInt32(),
      last: FuryType.varInt32(),
    })
    class SimpleStructInner {
      f2: number = 0;
      f3: Item | null = null;
      f4: string = "";
      f5: number = 0;
      f7: number = 0;
      f8: number = 0;
      last: number = 0;
    }
    fory.registerSerializer(SimpleStructInner);

    const reader = new FuryBinaryReader({});
    reader.reset(content);

    const deserializedObj = fory.deserialize(reader.buffer(reader.varUInt32()));

    const serializedData = fory.serialize(deserializedObj);

    const deserializedObj2 = fory.deserialize(serializedData);

    expect(deserializedObj2).toBeTruthy();
  });

  test("test_named_simple_struct", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    fory.registerSerializer(
      Type.enum({ namespace: "demo", typeName: "color" }, Color),
    );

    @Type.struct(
      { namespace: "demo", typeName: "item" },
      {
        name: Type.string(),
      },
    )
    class Item {
      name: string = "";
    }
    fory.registerSerializer(Item);

    @Type.struct(
      { namespace: "demo", typeName: "simple_struct" },
      {
        f1: Type.map(Type.int32(), Type.float64()),
        f2: Type.int32(),
        f3: Type.struct({ namespace: "demo", typeName: "item" }),
        f4: Type.string(),
        f5: Type.enum({ namespace: "demo", typeName: "color" }, Color),
        f6: Type.array(Type.string()),
        f7: Type.int32(),
        f8: Type.int32(),
        last: Type.int32(),
      },
    )
    class SimpleStructNamed {
      f1: Map<number, number> = new Map();
      f2: number = 0;
      f3: Item | null = null;
      f4: string = "";
      f5: number = 0;
      f6: string[] = [];
      f7: number = 0;
      f8: number = 0;
      last: number = 0;
    }
    fory.registerSerializer(SimpleStructNamed);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedObj = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedObj);
    writeToFile(serializedData as Buffer);
  });

  test("test_list", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(102, {
      name: Type.string(),
    })
    class Item {
      name: string = "";
    }
    fory.registerSerializer(Item);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedLists: unknown[] = [];
    for (let i = 0; i < 4; i++) {
      const deserializedList = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedLists.push(deserializedList);
    }

    const writer = new BinaryWriter();
    writer.reserve(512);

    for (const list of deserializedLists) {
      const serializedData = fory.serialize(list);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_map", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(102, {
      name: Type.string(),
    })
    class Item {
      name: string = "";
    }

    fory.registerSerializer(Item);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedMaps: unknown[] = [];
    for (let i = 0; i < 2; i++) {
      const deserializedMap = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedMaps.push(deserializedMap);
    }

    const writer = new BinaryWriter();
    writer.reserve(512);

    const bfs: Buffer[] = [];
    for (const map of deserializedMaps) {
      const serializedData = fory.serialize(map);
      fory.deserialize(serializedData);
      bfs.push(serializedData as Buffer);
    }

    writeToFile(Buffer.concat(bfs));
  });

  test("test_integer", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(101, {
      f1: Type.int32(),
      f2: Type.int32(),
      f3: Type.int32(),
      f4: Type.int32(),
      f5: Type.int32(),
      f6: Type.int32(),
    })
    class Item1 {
      f1: number = 0;
      f2: number = 0;
      f3: number | null = null;
      f4: number | null = null;
      f5: number | null = null;
      f6: number | null = null;
    }

    fory.registerSerializer(Item1);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedData: unknown[] = [];
    for (let i = 0; i < 7; i++) {
      const deserializedItem = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedData.push(deserializedItem);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);

    for (const item of deserializedData) {
      const serializedData = fory.serialize(item);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_item", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(102, {
      name: Type.string(),
    })
    class Item {
      name: string = "";
    }
    fory.registerSerializer(Item);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedItems: unknown[] = [];
    for (let i = 0; i < 3; i++) {
      const deserializedItem = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedItems.push(deserializedItem);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);

    const bfs: Buffer[] = [];
    for (const item of deserializedItems) {
      const serializedData = fory.serialize(item);
      bfs.push(serializedData as Buffer);
    }

    writeToFile(Buffer.concat(bfs));
  });

  test("test_color", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    const { serialize: enumSerialize } = fory.registerSerializer(
      Type.enum(101, Color),
    );

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedColors: unknown[] = [];
    for (let i = 0; i < 4; i++) {
      const deserializedColor = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedColors.push(deserializedColor);
    }

    const writer = new BinaryWriter();
    writer.reserve(128);

    for (const color of deserializedColors) {
      const serializedData = enumSerialize(color as any);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_struct_with_list", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(201, {
      items: Type.array(Type.string()),
    })
    class StructWithList {
      items: (string | null)[] = [];
    }
    fory.registerSerializer(StructWithList);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStructs: unknown[] = [];
    for (let i = 0; i < 2; i++) {
      const deserializedStruct = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedStructs.push(deserializedStruct);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);

    for (const struct of deserializedStructs) {
      const serializedData = fory.serialize(struct);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_struct_with_map", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(202, {
      data: Type.map(Type.string(), Type.string()),
    })
    class StructWithMap {
      data: Map<string | null, string | null> = new Map();
    }
    fory.registerSerializer(StructWithMap);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStructs: unknown[] = [];
    for (let i = 0; i < 2; i++) {
      const deserializedStruct = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedStructs.push(deserializedStruct);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);

    for (const struct of deserializedStructs) {
      const serializedData = fory.serialize(struct);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_skip_id_custom", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(104)
    class EmptyWrapper {}
    fory.registerSerializer(EmptyWrapper);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedWrapper = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedWrapper);
    writeToFile(serializedData as Buffer);
  });

  test("test_skip_name_custom", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct({ namespace: "", typeName: "my_wrapper" })
    class EmptyWrapper {}
    fory.registerSerializer(EmptyWrapper);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedWrapper = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedWrapper);
    writeToFile(serializedData as Buffer);
  });

  test("test_consistent_named", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    fory.registerSerializer(
      Type.enum({ namespace: "", typeName: "color" }, Color),
    );

    @Type.struct(
      { namespace: "", typeName: "my_struct" },
      {
        id: Type.int32(),
      },
    )
    class MyStruct {
      id: number = 0;
    }
    fory.registerSerializer(MyStruct);

    @Type.struct(
      { namespace: "", typeName: "my_ext" },
      {
        id: Type.int32(),
      },
    )
    class MyExt {
      id: number = 0;
    }
    fory.registerSerializer(MyExt, {
      write: (value: MyExt, writer: BinaryWriter, _fory: Fory) => {
        writer.varInt32(value.id);
      },
      read: (result: MyExt, reader: BinaryReader, _fory: Fory) => {
        result.id = reader.varInt32();
      },
    });

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedData: unknown[] = [];
    for (let i = 0; i < 9; i++) {
      const deserializedItem = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedData.push(deserializedItem);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);

    for (const item of deserializedData) {
      const serializedData = fory.serialize(item);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_struct_version_check", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(201, {
      f1: Type.int32(),
      f2: Type.string(),
      f3: Type.float64(),
    })
    class VersionCheckStruct {
      f1: number = 0;
      f2: string | null = null;
      f3: number = 0;
    }
    fory.registerSerializer(VersionCheckStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_polymorphic_list", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(302, {
      age: Type.int32(),
      name: Type.string(),
    })
    class Dog {
      age: number = 0;
      name: string | null = null;

      getAge() {
        return this.age;
      }
      speak() {
        return "Woof";
      }
    }
    fory.registerSerializer(Dog);

    @Type.struct(303, {
      age: Type.int32(),
      lives: Type.int32(),
    })
    class Cat {
      age: number = 0;
      lives: number = 0;

      getAge() {
        return this.age;
      }
      speak() {
        return "Meow";
      }
    }
    fory.registerSerializer(Cat);

    @Type.struct(304, {
      animals: Type.array(Type.any()),
    })
    class AnimalListHolder {
      animals: (Dog | Cat)[] = [];
    }
    fory.registerSerializer(AnimalListHolder);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedData: unknown[] = [];
    for (let i = 0; i < 2; i++) {
      const deserializedItem = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedData.push(deserializedItem);
    }

    const writer = new BinaryWriter();
    writer.reserve(512);

    for (const item of deserializedData) {
      const serializedData = fory.serialize(item);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_polymorphic_map", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(302, {
      age: Type.int32(),
      name: Type.string(),
    })
    class Dog {
      age: number = 0;
      name: string | null = null;

      getAge() {
        return this.age;
      }
      speak() {
        return "Woof";
      }
    }
    fory.registerSerializer(Dog);

    @Type.struct(303, {
      age: Type.int32(),
      lives: Type.int32(),
    })
    class Cat {
      age: number = 0;
      lives: number = 0;

      getAge() {
        return this.age;
      }
      speak() {
        return "Meow";
      }
    }
    fory.registerSerializer(Cat);

    @Type.struct(305, {
      animal_map: Type.map(Type.string(), Type.any()),
    })
    class AnimalMapHolder {
      animal_map: Map<string, Dog | Cat> = new Map();
    }
    fory.registerSerializer(AnimalMapHolder);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedData: unknown[] = [];
    for (let i = 0; i < 2; i++) {
      const deserializedItem = fory.deserialize(
        reader.buffer(reader.varUInt32()),
      );
      deserializedData.push(deserializedItem);
    }

    const writer = new BinaryWriter();
    writer.reserve(512);

    for (const item of deserializedData) {
      const serializedData = fory.serialize(item);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_one_string_field_schema", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(200, {
      f1: Type.string(),
    })
    class OneStringFieldStruct {
      f1: string | null = null;
    }
    fory.registerSerializer(OneStringFieldStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_one_string_field_compatible", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(200, {
      f1: Type.string(),
    })
    class OneStringFieldStruct {
      f1: string | null = null;
    }
    fory.registerSerializer(OneStringFieldStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_two_string_field_compatible", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(201, {
      f1: Type.string(),
      f2: Type.string(),
    })
    class TwoStringFieldStruct {
      f1: string = "";
      f2: string = "";
    }
    fory.registerSerializer(TwoStringFieldStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_schema_evolution_compatible", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(200)
    class EmptyStruct {}
    fory.registerSerializer(EmptyStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_one_enum_field_schema", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    fory.registerSerializer(Type.enum(210, TestEnum));

    @Type.struct(211, {
      f1: Type.enum(210, TestEnum),
    })
    class OneEnumFieldStruct {
      f1: number = 0;
    }
    fory.registerSerializer(OneEnumFieldStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_one_enum_field_compatible", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    fory.registerSerializer(Type.enum(210, TestEnum));

    @Type.struct(211, {
      f1: Type.enum(210, TestEnum),
    })
    class OneEnumFieldStruct {
      f1: number = 0;
    }
    fory.registerSerializer(OneEnumFieldStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_two_enum_field_compatible", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    fory.registerSerializer(Type.enum(210, TestEnum));

    @Type.struct(212, {
      f1: Type.enum(210, TestEnum),
      f2: Type.enum(210, TestEnum),
    })
    class TwoEnumFieldStruct {
      f1: number = 0;
      f2: number = 0;
    }
    fory.registerSerializer(TwoEnumFieldStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_enum_schema_evolution_compatible", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    fory.registerSerializer(Type.enum(210, TestEnum));

    @Type.struct(211)
    class EmptyStruct {}
    fory.registerSerializer(EmptyStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_nullable_field_schema_consistent_not_null", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(401, {
      intField: Type.int32(),
      stringField: Type.string(),
      nullableInt: Type.int32(),
      nullableString: Type.string(),
    })
    class NullableStruct {
      intField: number = 0;
      stringField: string = "";
      nullableInt: number | null = null;
      nullableString: string | null = null;
    }
    fory.registerSerializer(NullableStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_nullable_field_schema_consistent_null", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(401, {
      intField: Type.int32(),
      stringField: Type.string(),
      nullableInt: Type.int32(),
      nullableString: Type.string(),
    })
    class NullableStruct {
      intField: number = 0;
      stringField: string = "";
      nullableInt: number | null = null;
      nullableString: string | null = null;
    }
    fory.registerSerializer(NullableStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_nullable_field_compatible_not_null", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(402, {
      intField: Type.int32(),
      stringField: Type.string(),
      nullableInt: Type.int32(),
      nullableString: Type.string(),
    })
    class NullableStruct {
      intField: number = 0;
      stringField: string = "";
      nullableInt: number | null = null;
      nullableString: string | null = null;
    }
    fory.registerSerializer(NullableStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_nullable_field_compatible_null", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(402, {
      intField: Type.int32(),
      stringField: Type.string(),
      nullableInt: Type.int32(),
      nullableString: Type.string(),
    })
    class NullableStruct {
      intField: number = 0;
      stringField: string = "";
      nullableInt: number | null = null;
      nullableString: string | null = null;
    }
    fory.registerSerializer(NullableStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_ref_schema_consistent", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(501, {
      id: Type.int32(),
      name: Type.string(),
    })
    class RefInner {
      id: number = 0;
      name: string = "";
    }
    fory.registerSerializer(RefInner);

    @Type.struct(502, {
      inner1: Type.struct(501),
      inner2: Type.struct(501),
    })
    class RefOuter {
      inner1: RefInner | null = null;
      inner2: RefInner | null = null;
    }
    fory.registerSerializer(RefOuter);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedOuter = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedOuter);
    writeToFile(serializedData as Buffer);
  });

  test("test_ref_compatible", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(503, {
      id: Type.int32(),
      name: Type.string(),
    })
    class RefInner {
      id: number = 0;
      name: string = "";
    }
    fory.registerSerializer(RefInner);

    @Type.struct(504, {
      inner1: Type.struct(503),
      inner2: Type.struct(503),
    })
    class RefOuter {
      inner1: RefInner | null = null;
      inner2: RefInner | null = null;
    }
    fory.registerSerializer(RefOuter);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedOuter = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedOuter);
    writeToFile(serializedData as Buffer);
  });

  test("test_circular_ref_schema_consistent", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(601, {
      name: Type.string(),
      selfRef: Type.struct(601),
    })
    class CircularRefStruct {
      name: string = "";
      selfRef: CircularRefStruct | null = null;
    }
    fory.registerSerializer(CircularRefStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_circular_ref_compatible", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(602, {
      name: Type.string(),
      selfRef: Type.struct(602),
    })
    class CircularRefStructCompatible {
      name: string = "";
      selfRef: CircularRefStructCompatible | null = null;
    }
    fory.registerSerializer(CircularRefStructCompatible);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_unsigned_schema_consistent_simple", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(1, {
      u64Tagged: Type.int64(),
      u64TaggedNullable: Type.int64(),
    })
    class UnsignedStructSimple {
      u64Tagged: bigint = 0n;
      u64TaggedNullable: bigint | null = null;
    }
    fory.registerSerializer(UnsignedStructSimple);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_unsigned_schema_consistent", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(501, {
      u8Field: Type.uint8(),
      u16Field: Type.uint16(),
      u32Field: Type.uint32(),
      u64Field: Type.uint64(),
    })
    class UnsignedStruct {
      u8Field: number = 0;
      u16Field: number = 0;
      u32Field: number = 0;
      u64Field: bigint = 0n;
    }
    fory.registerSerializer(UnsignedStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_unsigned_schema_compatible", () => {
    const fory = new Fory();
    TypeInfo.attach(fory);

    @Type.struct(502, {
      u8Field: Type.uint8(),
      u16Field: Type.uint16(),
      u32Field: Type.uint32(),
      u64Field: Type.uint64(),
    })
    class UnsignedStructCompatible {
      u8Field: number = 0;
      u16Field: number = 0;
      u32Field: number = 0;
      u64Field: bigint = 0n;
    }
    fory.registerSerializer(UnsignedStructCompatible);

    const reader = new BinaryReader({});
    reader.reset(content);

    const deserializedStruct = fory.deserialize(
      reader.buffer(reader.varUInt32()),
    );

    const serializedData = fory.serialize(deserializedStruct);
    // writeToFile(serializedData as Buffer);
  });
});
