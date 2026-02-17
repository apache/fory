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

import Fory, {
  BinaryReader,
  BinaryWriter,
  Type,
  Dynamic,
} from "../packages/fory/index";
import { describe, expect, test } from "@jest/globals";
import * as fs from "node:fs";
import * as beautify from 'js-beautify';
import { TypeId } from "../packages/fory/lib/type";

const Byte = {
  MAX_VALUE: 127,
  MIN_VALUE: -128,
}

const Short = {
  MAX_VALUE: 32767,
  MIN_VALUE: -32768,
}

const Integer = {
  MAX_VALUE: 2147483647,
  MIN_VALUE: -2147483648,
}

const Long = {
  MAX_VALUE: BigInt("9223372036854775807"),
  MIN_VALUE: BigInt("-9223372036854775808"),
}

describe("bool", () => {
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
    buffer.writeUint8(Byte.MAX_VALUE);
    buffer.writeInt16(Short.MAX_VALUE);
    buffer.writeInt32(Integer.MAX_VALUE);
    buffer.writeInt64(Long.MAX_VALUE);
    buffer.writeFloat32(-1.1);
    buffer.writeFloat64(-1.1);
    buffer.writeVarUInt32(100);
    const bytes = ['a'.charCodeAt(0), 'b'.charCodeAt(0)];
    buffer.writeInt32(bytes.length);
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
      expect(reader.readVarInt32()).toBe(expected);
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
      expect(reader.readVarUInt32()).toBe(expected);
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
      expect(reader.readVarUInt64()).toBe(expected);
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
      expect(reader.readVarInt64()).toBe(expected);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);
    for (const value of varInt32Values) {
      writer.writeVarInt32(value);
    }
    for (const value of varUInt32Values) {
      writer.writeVarUInt32(value);
    }
    for (const value of varUInt64Values) {
      writer.writeVarUInt64(value);
    }
    for (const value of varInt64Values) {
      writer.writeVarInt64(value);
    }
    writeToFile(writer.dump() as Buffer);
  });
  test("test_murmurhash3", () => {
    const { x64hash128 } = require("../packages/fory/lib/murmurHash3");
    const reader = new BinaryReader({});
    reader.reset(content);
    let dataview = x64hash128(new Uint8Array([1, 2, 8]), 47);
    expect(reader.readInt64()).toEqual(dataview.getBigInt64(0));
    expect(reader.readInt64()).toEqual(dataview.getBigInt64(8));
  });
  test("test_string_serializer", () => {
    const fory = new Fory({
      compatible: true
    });
    // Deserialize strings from Java
    const deserializedStrings = [];
    let cursor = 0;
    for (let i = 0; i < 7; i++) { // 7 test strings
      const deserializedString = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedStrings.push(deserializedString);
    }
    const bfs = []
    // Serialize each deserialized string back
    for (const testString of deserializedStrings) {
      const serializedData = fory.serialize(testString);
      bfs.push(serializedData);
    }

    writeToFile(Buffer.concat(bfs));
  });
  test("test_cross_language_serializer", () => {
    const fory = new Fory({
      compatible: true
    });

    // Define and register Color enum
    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    const { serialize: colorSerialize } = fory.registerSerializer(Type.enum(101, Color));
    // Deserialize various data types from Java
    const deserializedData = [];
    let cursor = 0;
    for (let i = 0; i < 27; i++) { // 28 serialized items from Java
      const deserializedItem = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedData.push(deserializedItem);
    }


    const bfs = []
    // Serialize each deserialized item back
    for (let index = 0; index < deserializedData.length; index++) {
      const item = deserializedData[index];
      let serializedData;
      if (index === 11) {
        serializedData = fory.serialize(item, fory.typeResolver.getSerializerById(TypeId.FLOAT32));
      } else if (index === 12) {
        serializedData = fory.serialize(item, fory.typeResolver.getSerializerById(TypeId.FLOAT64));
      } else if (index === 14) {
        serializedData = fory.serialize(item, fory.typeResolver.getSerializerById(TypeId.DATE));
      } else if (index === 15) {
        serializedData = fory.serialize(item, fory.typeResolver.getSerializerById(TypeId.TIMESTAMP));
      } else if (index === 16) {
        serializedData = fory.serialize(item, fory.typeResolver.getSerializerById(TypeId.BOOL_ARRAY));
      } else if (index === 17) {
        serializedData = fory.serialize(item, fory.typeResolver.getSerializerById(TypeId.BINARY));
      } else if (index === 26) {
        serializedData = colorSerialize(item);
      } else {
        serializedData = fory.serialize(item);
      }
      bfs.push(serializedData);
    }

    writeToFile(Buffer.concat(bfs));
  });
  test("test_simple_struct", () => {
    const fory = new Fory({
      compatible: true,
      hooks: {
        afterCodeGenerated: (code) => {
          return beautify.js(code, { indent_size: 2, space_in_empty_paren: true, indent_empty_lines: true });
        }
      }
    });

    // Define Color enum
    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    fory.registerSerializer(Type.enum(101, Color));

    // Define Item class with field type registration
    @Type.struct(102, {
      name: Type.string()
    })
    class Item {
      name: string = "";
    }
    fory.registerSerializer(Item);

    // Define SimpleStruct class with field type registration
    @Type.struct(103, {
      f1: Type.map(Type.varInt32(), Type.float64()),
      f2: Type.varInt32(),
      f3: Type.struct(102),
      f4: Type.string(),
      f5: Type.enum(101, Color),
      f6: Type.array(Type.string()),
      f7: Type.varInt32(),
      f8: Type.varInt32(),
      last: Type.varInt32()
    })
    class SimpleStruct {
      f2: number = 0;
      f3: Item | null = null;
      f4: string = "";
      f5: number = 0; // Color enum value
      f1 = new Map([[1, 1.0], [2, 2.0]])
      f6 = ["f6"]
      f7: number = 0;
      f8: number = 0;
      last: number = 0;
    }
    fory.registerSerializer(SimpleStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize the object from Java
    const deserializedObj = fory.deserialize(content);

    // Serialize the deserialized object back
    const serializedData = fory.serialize(deserializedObj);

    writeToFile(serializedData as Buffer);
  });
  test("test_named_simple_struct", () => {
    // Same as test_simple_struct but with named registration
    const fory = new Fory({
      compatible: true
    });

    // Define Color enum
    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    fory.registerSerializer(Type.enum({ namespace: 'demo', typeName: "color" }, Color));

    // Define Item class with field type registration
    @Type.struct({ namespace: "demo", typeName: "item" }, {
      name: Type.string()
    })
    class Item {
      name: string = "";
    }
    fory.registerSerializer(Item);

    // Define SimpleStruct class with field type registration
    @Type.struct({ namespace: "demo", typeName: "simple_struct" }, {
      f1: Type.map(Type.varInt32(), Type.float64()),
      f2: Type.varInt32(),
      f3: Type.struct({ namespace: "demo", typeName: "item" }),
      f4: Type.string(),
      f5: Type.enum({ namespace: 'demo', typeName: "color" }, Color),
      f6: Type.array(Type.string()),
      f7: Type.varInt32(),
      f8: Type.varInt32(),
      last: Type.varInt32()
    })
    class SimpleStruct {
      f1: Map<number, number> = new Map();
      f2: number = 0;
      f3: Item | null = null;
      f4: string = "";
      f5: number = 0; // Color enum value
      f6: string[] = [];
      f7: number = 0;
      f8: number = 0;
      last: number = 0;
    }
    fory.registerSerializer(SimpleStruct);

    // Deserialize the object from Java
    const deserializedObj = fory.deserialize(content);
    // Serialize the deserialized object back
    const serializedData = fory.serialize(deserializedObj);
    writeToFile(serializedData as Buffer);
  });

  test("test_list", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(102, {
      name: Type.string()
    })
    class Item {
      name: string = "";
    }
    fory.registerSerializer(Item);


    // Deserialize all lists from Java
    const deserializedLists = [];
    let cursor = 0;
    for (let i = 0; i < 4; i++) { // 4 lists
      const deserializedList = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedLists.push(deserializedList);
    }

    const bfs = [];

    // Serialize each deserialized list back
    for (const list of deserializedLists) {
      const serializedData = fory.serialize(list);
      bfs.push(serializedData)
    }
    writeToFile(Buffer.concat(bfs));
  });

  test("test_map", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(102, {
      name: Type.string()
    })
    class Item {
      name: string = "";
    }

    fory.registerSerializer(Item);

    // Deserialize maps from Java
    const deserializedMaps = [];
    let cursor = 0;
    for (let i = 0; i < 2; i++) { // 2 maps
      const deserializedMap = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedMaps.push(deserializedMap);
    }

    const bfs = []
    // Serialize each deserialized map back
    for (const map of deserializedMaps) {
      const serializedData = fory.serialize(map);
      fory.deserialize(serializedData);
      bfs.push(serializedData);
    }

    writeToFile(Buffer.concat(bfs));
  });

  test("test_integer", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(101, {
      f1: Type.varInt32(),
      f2: Type.varInt32(),
      f3: Type.varInt32(),
      f4: Type.varInt32(),
      f5: Type.varInt32(),
      f6: Type.varInt32()
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


    // Deserialize item and individual integers from Java
    const deserializedData = [];
    let cursor = 0;
    for (let i = 0; i < 7; i++) { // 1 item + 6 integers
      const deserializedItem = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedData.push(deserializedItem);
    }

    const bfs = []
    // Serialize each deserialized item back
    for (const item of deserializedData) {
      const serializedData = fory.serialize(item);
      bfs.push(serializedData);
    }

    writeToFile(Buffer.concat(bfs));
  });

  test("test_item", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(102, {
      name: Type.string()
    })
    class Item {
      name: string = "";
    }
    fory.registerSerializer(Item);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize items from Java
    const deserializedItems = [];
    let cursor = 0;
    for (let i = 0; i < 3; i++) { // 3 items
      const deserializedItem = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedItems.push(deserializedItem);
    }

    const bfs = []
    // Serialize each deserialized item back
    for (const item of deserializedItems) {
      const serializedData = fory.serialize(item);
      bfs.push(serializedData);
    }

    writeToFile(Buffer.concat(bfs));
  });

  test("test_color", () => {
    const fory = new Fory({
      compatible: true
    });

    // Define and register Color enum
    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    const { serialize: enumSerialize } = fory.registerSerializer(Type.enum(101, Color));

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize colors from Java
    const deserializedColors = [];
    let cursor = 0;
    for (let i = 0; i < 4; i++) { // 4 colors
      const deserializedColor = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedColors.push(deserializedColor);
    }

    const writer = new BinaryWriter();
    writer.reserve(128);

    // Serialize each deserialized color back
    for (const color of deserializedColors) {
      const serializedData = enumSerialize(color);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });
  test("test_struct_with_list", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(201, {
      items: Type.array(Type.string())
    })
    class StructWithList {
      items: (string | null)[] = [];
    }
    fory.registerSerializer(StructWithList);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize structs from Java
    const deserializedStructs = [];
    let cursor = 0;
    for (let i = 0; i < 2; i++) { // 2 structs
      const deserializedStruct = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedStructs.push(deserializedStruct);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);

    // Serialize each deserialized struct back
    for (const struct of deserializedStructs) {
      const serializedData = fory.serialize(struct);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_struct_with_map", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(202, {
      data: Type.map(Type.string(), Type.string())
    })
    class StructWithMap {
      data: Map<string | null, string | null> = new Map();
    }
    fory.registerSerializer(StructWithMap);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize structs from Java
    const deserializedStructs = [];
    let cursor = 0;
    for (let i = 0; i < 2; i++) { // 2 structs
      const deserializedStruct = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedStructs.push(deserializedStruct);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);

    // Serialize each deserialized struct back
    for (const struct of deserializedStructs) {
      const serializedData = fory.serialize(struct);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_collection_element_ref_override", () => {
    const fory = new Fory({
      compatible: false,
      refTracking: true,
      hooks: {
        afterCodeGenerated: (code) => {
          return beautify.js(code, { indent_size: 2, space_in_empty_paren: true, indent_empty_lines: true });
        }
      }
    });

    const Type701 = Type.struct(701, {
      id: Type.varInt32(),
      name: Type.string()
    });

    @Type701
    class RefOverrideElement {
      id: number = 0;
      name: string = "";
    }

    @Type.struct(702, {
      list_field: Type.array(Type701.setTrackingRef(true)),
      map_field: Type.map(Type.string(), Type701.setTrackingRef(true))
    })
    class RefOverrideContainer {
      list_field: RefOverrideElement[] = [];
      map_field: Map<string, RefOverrideElement> = new Map();
    }

    fory.registerSerializer(RefOverrideElement);
    fory.registerSerializer(RefOverrideContainer);

    const outer = fory.deserialize(content);
    console.log("Deserialized:", outer);

    expect(outer.list_field).toBeTruthy();
    expect(outer.list_field.length).toBeGreaterThan(0);
    const shared = outer.list_field[0];
    const newOuter = new RefOverrideContainer();
    newOuter.list_field = [shared, shared];
    newOuter.map_field = new Map([
      ["k1", shared],
      ["k2", shared]
    ]);

    const newBytes = fory.serialize(newOuter);
    writeToFile(newBytes as Buffer)
  });

  test("test_skip_id_custom", () => {
    const fory1 = new Fory({
      compatible: true
    });

    @Type.ext(103)
    class MyExt {
      id: number = 0;
    }
    fory1.registerSerializer(MyExt, {
      write: (value: MyExt, writer: BinaryWriter, fory: Fory) => {
        writer.writeVarInt32(value.id);
      },
      read: (result: MyExt, reader: BinaryReader, fory: Fory) => {
        result.id = reader.readVarInt32();
      }
    });

    // Define empty wrapper for deserialization
    @Type.struct(104)
    class Empty { }
    fory1.registerSerializer(Empty);

    const fory2 = new Fory({
      compatible: true
    });

    // Define Color enum
    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    fory2.registerSerializer(Type.enum(101, Color));

    @Type.struct(102, {
      id: Type.varInt32()
    })
    class MyStruct {
      id: number = 0;
    }
    fory2.registerSerializer(MyStruct);

    fory2.registerSerializer(MyExt, {
      write: (value: MyExt, writer: BinaryWriter, fory: Fory) => {
        writer.writeVarInt32(value.id);
      },
      read: (result: MyExt, reader: BinaryReader, fory: Fory) => {
        result.id = reader.readVarInt32();
      }
    });

    @Type.struct(104, {
      color: Type.enum(101, Color),
      myStruct: Type.struct(102),
      myExt: Type.ext(103)
    })
    class MyWrapper {
      color: number = 0;
      myStruct: MyStruct = new MyStruct();
      myExt: MyExt = new MyExt();
    }
    fory2.registerSerializer(MyWrapper);


    // Deserialize empty from Java
    let cursor = 0;
    const deserializedEmpty = fory1.deserialize(content.subarray(cursor));
    cursor += fory1.binaryReader.readGetCursor();
    expect(deserializedEmpty instanceof Empty).toEqual(true);

    // Create wrapper object
    const wrapper = new MyWrapper();
    wrapper.color = Color.White;
    wrapper.myStruct = new MyStruct();
    wrapper.myStruct.id = 42;
    wrapper.myExt = new MyExt();
    wrapper.myExt.id = 43;

    // Serialize wrapper
    const serializedData = fory2.serialize(wrapper);
    writeToFile(serializedData as Buffer);
  });

  test("test_skip_name_custom", () => {
    const fory1 = new Fory({
      compatible: true
    });

    @Type.ext("my_ext")
    class MyExt {
      id: number = 0;
    }
    fory1.registerSerializer(MyExt, {
      write: (value: MyExt, writer: BinaryWriter, fory: Fory) => {
        writer.writeVarInt32(value.id);
      },
      read: (result: MyExt, reader: BinaryReader, fory: Fory) => {
        result.id = reader.readVarInt32();
      }
    });

    // Define empty wrapper for deserialization
    @Type.struct("my_wrapper")
    class Empty { }
    fory1.registerSerializer(Empty);

    const fory2 = new Fory({
      compatible: true
    });

    // Define Color enum
    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    fory2.registerSerializer(Type.enum("color", Color));

    @Type.struct("my_struct", {
      id: Type.varInt32()
    })
    class MyStruct {
      id: number = 0;
    }
    fory2.registerSerializer(MyStruct);

    fory2.registerSerializer(MyExt, {
      write: (value: MyExt, writer: BinaryWriter, fory: Fory) => {
        writer.writeVarInt32(value.id);
      },
      read: (result: MyExt, reader: BinaryReader, fory: Fory) => {
        result.id = reader.readVarInt32();
      }
    });

    @Type.struct("my_wrapper", {
      color: Type.enum("color", Color),
      myStruct: Type.struct("my_struct"),
      myExt: Type.ext("my_ext")
    })
    class MyWrapper {
      color: number = 0;
      myStruct: MyStruct = new MyStruct();
      myExt: MyExt = new MyExt();
    }
    fory2.registerSerializer(MyWrapper);


    // Deserialize empty from Java
    let cursor = 0;
    const deserializedEmpty = fory1.deserialize(content.subarray(cursor));
    cursor += fory1.binaryReader.readGetCursor();
    expect(deserializedEmpty instanceof Empty).toEqual(true);

    // Create wrapper object
    const wrapper = new MyWrapper();
    wrapper.color = Color.White;
    wrapper.myStruct = new MyStruct();
    wrapper.myStruct.id = 42;
    wrapper.myExt = new MyExt();
    wrapper.myExt.id = 43;

    // Serialize wrapper
    const serializedData = fory2.serialize(wrapper);
    writeToFile(serializedData as Buffer);
  });

  test("test_consistent_named", () => {
    const fory = new Fory({
      compatible: false,
    });

    // Define and register Color enum
    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    const { serialize: enumSerialize } = fory.registerSerializer(Type.enum({ namespace: "", typeName: "color" }, Color));

    @Type.struct({ namespace: "", typeName: "my_struct" }, {
      id: Type.varInt32()
    })
    class MyStruct {
      id: number = 0;
    }
    fory.registerSerializer(MyStruct);

    @Type.ext({ namespace: "", typeName: "my_ext" })
    class MyExt {
      id: number = 0;
    }
    fory.registerSerializer(MyExt, {
      write: (value: MyExt, writer: BinaryWriter, fory: Fory) => {
        writer.writeVarInt32(value.id);
      },
      read: (result: MyExt, reader: BinaryReader, fory: Fory) => {
        result.id = reader.readVarInt32();
      }
    });

    // Deserialize multiple instances from Java
    const deserializedData = [];
    let cursor = 0;
    for (let i = 0; i < 9; i++) { // 3 colors + 3 structs + 3 exts
      const deserializedItem = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedData.push(deserializedItem);
    }

    const writer = new BinaryWriter();
    writer.reserve(256);

    for (let index = 0; index < 3; index++) {
      const element = deserializedData[index];
      const serializedData = enumSerialize(element);
      writer.buffer(serializedData);
    }
    for (let index = 3; index < deserializedData.length; index++) {
      const item = deserializedData[index];
      const serializedData = fory.serialize(item);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_struct_version_check", () => {
    const fory = new Fory({
      compatible: false,
    });

    @Type.struct(201, {
      f1: Type.varInt32(),
      f2: Type.string().setNullable(true),
      f3: Type.float64()
    })
    class VersionCheckStruct {
      f1: number = 0;
      f2: string | null = null;
      f3: number = 0;
    }
    fory.registerSerializer(VersionCheckStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_polymorphic_list", () => {
    const fory = new Fory({
      compatible: true
    });

    // Define Animal interface implementations
    @Type.struct(302, {
      age: Type.varInt32(),
      name: Type.string().setNullable(true)
    })
    class Dog {
      age: number = 0;
      name: string | null = null;
    }
    fory.registerSerializer(Dog);

    @Type.struct(303, {
      age: Type.varInt32(),
      lives: Type.varInt32()
    })
    class Cat {
      age: number = 0;
      lives: number = 0;
    }
    fory.registerSerializer(Cat);

    @Type.struct(304, {
      animals: Type.array(Type.any()) // Polymorphic array
    })
    class AnimalListHolder {
      animals: (Dog | Cat)[] = [];
    }
    fory.registerSerializer(AnimalListHolder);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize polymorphic data from Java
    const deserializedData = [];
    let cursor = 0;
    for (let i = 0; i < 2; i++) { // animals array + holder
      const deserializedItem = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedData.push(deserializedItem);
    }

    const writer = new BinaryWriter();
    writer.reserve(512);

    // Serialize each deserialized item back
    for (const item of deserializedData) {
      const serializedData = fory.serialize(item);
      const s = fory.deserialize(serializedData)
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });

  test("test_polymorphic_map", () => {
    const fory = new Fory({
      compatible: true
    });

    // Define Animal interface implementations
    @Type.struct(302, {
      age: Type.varInt32(),
      name: Type.string().setNullable(true)
    })
    class Dog {
      age: number = 0;
      name: string | null = null;
    }
    fory.registerSerializer(Dog);

    @Type.struct(303, {
      age: Type.varInt32(),
      lives: Type.varInt32()
    })
    class Cat {
      age: number = 0;
      lives: number = 0;
    }
    fory.registerSerializer(Cat);

    @Type.struct(305, {
      animal_map: Type.map(Type.string(), Type.any()) // Polymorphic map
    })
    class AnimalMapHolder {
      animal_map: Map<string, Dog | Cat> = new Map();
    }
    fory.registerSerializer(AnimalMapHolder);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize polymorphic data from Java
    const deserializedData = [];
    let cursor = 0;
    for (let i = 0; i < 2; i++) { // animal map + holder
      const deserializedItem = fory.deserialize(content.subarray(cursor));
      cursor += fory.binaryReader.readGetCursor();
      deserializedData.push(deserializedItem);
    }

    const writer = new BinaryWriter();
    writer.reserve(512);

    // Serialize each deserialized item back
    for (const item of deserializedData) {
      const serializedData = fory.serialize(item);
      writer.buffer(serializedData);
    }

    writeToFile(writer.dump() as Buffer);
  });
  test("test_one_string_field_schema", () => {
    const fory = new Fory({
      compatible: false
    });

    @Type.struct(200, {
      f1: Type.string()
    })
    class OneStringFieldStruct {
      @Type.string().setNullable(true)
      f1: string | null = null;
    }
    fory.registerSerializer(OneStringFieldStruct);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });
  test("test_one_string_field_compatible", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(200, {
      f1: Type.string().setNullable(true)
    })
    class OneStringFieldStruct {
      f1: string | null = null;
    }
    fory.registerSerializer(OneStringFieldStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_two_string_field_compatible", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(201, {
      f1: Type.string(),
      f2: Type.string()
    })
    class TwoStringFieldStruct {
      f1: string = "";
      f2: string = "";
    }
    fory.registerSerializer(TwoStringFieldStruct);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_schema_evolution_compatible_reverse", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(200)
    class TwoStringFieldStruct {
      @Type.string()
      f1: string = "";
      @Type.string()
      f2: string = "";
    }
    fory.registerSerializer(TwoStringFieldStruct);

    // Deserialize empty struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_schema_evolution_compatible", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(200)
    class EmptyStruct { }
    fory.registerSerializer(EmptyStruct);

    // Deserialize empty struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });
  test("test_one_enum_field_schema", () => {
    const fory = new Fory({
      compatible: false
    });

    // Define and register TestEnum
    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    fory.registerSerializer(Type.enum(210, TestEnum));

    @Type.struct(211, {
      f1: Type.enum(210, TestEnum)
    })
    class OneEnumFieldStruct {
      f1: number = 0; // enum value
    }
    fory.registerSerializer(OneEnumFieldStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_one_enum_field_compatible", () => {
    const fory = new Fory({
      compatible: true
    });

    // Define and register TestEnum
    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    fory.registerSerializer(Type.enum(210, TestEnum));

    @Type.struct(211, {
      f1: Type.enum(210, TestEnum)
    })
    class OneEnumFieldStruct {
      f1: number = 0; // enum value
    }
    fory.registerSerializer(OneEnumFieldStruct);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_two_enum_field_compatible", () => {
    const fory = new Fory({
      compatible: true
    });

    // Define and register TestEnum
    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    fory.registerSerializer(Type.enum(210, TestEnum));

    @Type.struct(212, {
      f1: Type.enum(210, TestEnum),
      f2: Type.enum(210, TestEnum)
    })
    class TwoEnumFieldStruct {
      f1: number = 0; // enum value
      f2: number = 0; // enum value
    }
    fory.registerSerializer(TwoEnumFieldStruct);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_enum_schema_evolution_compatible_reverse", () => {
    const fory = new Fory({
      compatible: true
    });

    // Define and register TestEnum
    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    fory.registerSerializer(Type.enum(210, TestEnum));

    @Type.struct(211, {
      f1: Type.enum(210, TestEnum),
      f2: Type.enum(210, TestEnum)
    })
    class TwoEnumFieldStruct {
      f1: number = 0; // enum value
      f2: number = 0; // enum value
    }
    fory.registerSerializer(TwoEnumFieldStruct);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });


  test("test_enum_schema_evolution_compatible", () => {
    const fory = new Fory({
      compatible: true
    });

    // Register TestEnum
    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    fory.registerSerializer(Type.enum(210, TestEnum));

    @Type.struct(211)
    class EmptyStruct { }
    fory.registerSerializer(EmptyStruct);

    // Deserialize empty struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  const buildClass = (id = 402) => {
    @Type.struct({ typeId: id })
    class NullableComprehensiveCompatible {
      // Base non-nullable primitive fields
      @Type.int8()
      byteField: number = 0;
      @Type.int16()
      shortField: number = 0;
      @Type.varInt32()
      intField: number = 0;
      @Type.varInt64()
      longField: number = 0;
      @Type.float32()
      floatField: number = 0;
      @Type.float64()
      doubleField: number = 0;
      @Type.bool()
      boolField: boolean = false;

      // Base non-nullable boxed fields (not nullable by default in xlang)
      @Type.varInt32()
      boxedInt: number = 0;
      @Type.varInt64()
      boxedLong: number = 0;
      @Type.float32()
      boxedFloat: number = 0;
      @Type.float64()
      boxedDouble: number = 0;
      @Type.bool()
      boxedBool = false;

      // Base non-nullable reference fields
      @Type.string()
      stringField: string = '';
      @Type.array(Type.string())
      listField: string[] = [];
      @Type.set(Type.string())
      setField: Set<string> = new Set();
      @Type.map(Type.string(), Type.string())
      mapField: Map<string, string> = new Map();

      // Nullable group 1 - boxed types with nullable type decorators
      @(Type.varInt32().setNullable(true))
      nullableInt1: number | null = null;

      @(Type.varInt64().setNullable(true))
      nullableLong1: number | null = null;

      @(Type.float32().setNullable(true))
      nullableFloat1: number | null = null;

      @(Type.float64().setNullable(true))
      nullableDouble1: number | null = null;

      @(Type.bool().setNullable(true))
      nullableBool1: boolean | null = null;

      // Nullable group 2 - reference types with nullable type decorators
      @(Type.string().setNullable(true))
      nullableString2: string | null = null;

      @(Type.array(Type.string()).setNullable(true))
      nullableList2: string[] | null = null;

      @(Type.set(Type.string()).setNullable(true))
      nullableSet2: Set<string> | null = null;

      @(Type.map(Type.string(), Type.string()).setNullable(true))
      nullableMap2: Map<string, string> | null = null;
    }
    return NullableComprehensiveCompatible;
  }

  const buildClassConsistent = (id = 401) => {
    @Type.struct({ typeId: id })
    class NullableComprehensiveConsistent {
      // Base non-nullable primitive fields
      @Type.int8()
      byteField: number = 0;
      @Type.int16()
      shortField: number = 0;
      @Type.varInt32()
      intField: number = 0;
      @Type.varInt64()
      longField: number = 0;
      @Type.float32()
      floatField: number = 0;
      @Type.float64()
      doubleField: number = 0;
      @Type.bool()
      boolField: boolean = false;

      // Base non-nullable reference fields
      @Type.string()
      stringField: string = '';
      @Type.array(Type.string())
      listField: string[] = [];
      @Type.set(Type.string())
      setField: Set<string> = new Set();
      @Type.map(Type.string(), Type.string())
      mapField: Map<string, string> = new Map();

      // Nullable group 1 - boxed types with nullable type decorators
      @(Type.varInt32().setNullable(true))
      nullableInt: number | null = null;

      @(Type.varInt64().setNullable(true))
      nullableLong: number | null = null;

      @(Type.float32().setNullable(true))
      nullableFloat: number | null = null;

      @(Type.float64().setNullable(true))
      nullableDouble: number | null = null;

      @(Type.bool().setNullable(true))
      nullableBool: boolean | null = null;

      // Nullable group 2 - reference types with nullable type decorators
      @(Type.string().setNullable(true))
      nullableString: string | null = null;

      @(Type.array(Type.string()).setNullable(true))
      nullableList: string[] | null = null;

      @(Type.set(Type.string()).setNullable(true))
      nullableSet: Set<string> | null = null;

      @(Type.map(Type.string(), Type.string()).setNullable(true))
      nullableMap: Map<string, string> | null = null;
    }
    return NullableComprehensiveConsistent

  }

  test("test_nullable_field_schema_consistent_not_null", () => {
    const fory = new Fory({
      compatible: false
    });

    fory.registerSerializer(buildClassConsistent(401));

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });


  test("test_nullable_field_schema_consistent_null", () => {
    const fory = new Fory({
      compatible: false
    });
    fory.registerSerializer(buildClassConsistent());

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });


  test("test_nullable_field_compatible_not_null", () => {
    const fory = new Fory({
      compatible: true
    });

    fory.registerSerializer(buildClass());

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_nullable_field_compatible_null", () => {
    const fory = new Fory({
      compatible: true
    });

    fory.registerSerializer(buildClass());

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct: InstanceType<ReturnType<typeof buildClass>> | null = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    if (deserializedStruct === null) {
      throw new Error("deserializedStruct is null");
    }
    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_ref_schema_consistent", () => {
    const fory = new Fory({
      compatible: false,
      refTracking: true,
    });

    @Type.struct(501, {
      id: Type.varInt32(),
      name: Type.string()
    })
    class RefInner {
      id: number = 0;
      name: string = "";
    }
    fory.registerSerializer(RefInner);

    @Type.struct(502, {
      inner1: Type.struct(501).setTrackingRef(true).setNullable(true).setDynamic(Dynamic.FALSE),
      inner2: Type.struct(501).setTrackingRef(true).setNullable(true).setDynamic(Dynamic.FALSE),
    })
    class RefOuter {
      inner1: RefInner | null = null;

      inner2: RefInner | null = null;
    }
    fory.registerSerializer(RefOuter);

    // Deserialize outer struct from Java
    let cursor = 0;
    const deserializedOuter = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized outer struct back
    const serializedData = fory.serialize(deserializedOuter);
    writeToFile(serializedData as Buffer);
  });

  test("test_ref_compatible", () => {
    const fory = new Fory({
      compatible: true,
      refTracking: true,
    });

    @Type.struct(503, {
      id: Type.varInt32(),
      name: Type.string()
    })
    class RefInner {
      id: number = 0;
      name: string = "";
    }
    fory.registerSerializer(RefInner);

    @Type.struct(504, {
      inner1: Type.struct(503).setTrackingRef(true).setNullable(true),
      inner2: Type.struct(503).setTrackingRef(true).setNullable(true),
    })
    class RefOuter {
      inner1: RefInner | null = null;
      inner2: RefInner | null = null;
    }
    fory.registerSerializer(RefOuter);


    // Deserialize outer struct from Java
    let cursor = 0;
    const deserializedOuter = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized outer struct back
    const serializedData = fory.serialize(deserializedOuter);
    writeToFile(serializedData as Buffer);
  });

  test("test_circular_ref_schema_consistent", () => {
    const fory = new Fory({
      compatible: false,
      refTracking: true,
    });

    @Type.struct(601, {
      name: Type.string(),
      selfRef: Type.struct(601).setNullable(true).setTrackingRef(true)
    })
    class CircularRefStruct {
      name: string = "";
      selfRef: CircularRefStruct | null = null;
    }
    fory.registerSerializer(CircularRefStruct);

    const reader = new BinaryReader({});
    reader.reset(content);

    // Deserialize circular struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_circular_ref_compatible", () => {
    const fory = new Fory({
      compatible: true,
      refTracking: true,
    });

    @Type.struct(602, {
      name: Type.string(),
      selfRef: Type.struct(602).setNullable(true).setTrackingRef(true)
    })
    class CircularRefStruct {
      name: string = "";
      selfRef: CircularRefStruct | null = null;
    }
    fory.registerSerializer(CircularRefStruct);

    // Deserialize circular struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_unsigned_schema_consistent_simple", () => {
    const fory = new Fory({
      compatible: false
    });

    @Type.struct(1, {
      u64Tagged: Type.taggedUInt64(),
      u64TaggedNullable: Type.taggedUInt64().setNullable(true)
    })
    class UnsignedSchemaConsistentSimple {
      u64Tagged: bigint = 0n;

      u64TaggedNullable: bigint | null = null;
    }
    fory.registerSerializer(UnsignedSchemaConsistentSimple);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });

  test("test_unsigned_schema_consistent", () => {
    const fory = new Fory({
      compatible: false
    });

    @Type.struct(501, {
      u8Field: Type.uint8(),
      u16Field: Type.uint16(),
      u32VarField: Type.varUInt32(),
      u32FixedField: Type.uint32(),
      u64VarField: Type.varUInt64(),
      u64FixedField: Type.uint64(),
      u64TaggedField: Type.taggedUInt64(),
    })
    class UnsignedSchemaConsistent {
      u8Field: number = 0;
      u16Field: number = 0;
      u32VarField: number = 0;
      u32FixedField: bigint = 0n;
      u64VarField: bigint = 0n;
      u64FixedField: bigint = 0n;
      u64TaggedField: bigint = 0n;

      @(Type.uint8().setNullable(true))
      u8NullableField: number = 0;

      @(Type.uint16().setNullable(true))
      u16NullableField: number = 0;

      @(Type.varUInt32().setNullable(true))
      u32VarNullableField: number = 0;

      @(Type.uint32().setNullable(true))
      u32FixedNullableField: number = 0;

      @(Type.varUInt64().setNullable(true))
      u64VarNullableField: bigint = 0n;

      @(Type.uint64().setNullable(true))
      u64FixedNullableField: bigint = 0n;

      @(Type.taggedUInt64().setNullable(true))
      u64TaggedNullableField: bigint = 0n;
    }
    fory.registerSerializer(UnsignedSchemaConsistent);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedBackData = fory.serialize(deserializedStruct);
    writeToFile(serializedBackData as Buffer);
  });

  test("test_unsigned_schema_compatible", () => {
    const fory = new Fory({
      compatible: true
    });

    @Type.struct(502, {
      u8Field1: Type.uint8(),
      u16Field1: Type.uint16(),
      u32VarField1: Type.varUInt32(),
      u32FixedField1: Type.uint32(),
      u64VarField1: Type.varUInt64(),
      u64FixedField1: Type.uint64(),
      u64TaggedField1: Type.taggedUInt64(),
    })
    class UnsignedSchemaCompatible {
      u8Field1: number = 0;
      u16Field1: number = 0;
      u32VarField1: number = 0;
      u32FixedField1: bigint = 0n;
      u64VarField1: bigint = 0n;
      u64FixedField1: bigint = 0n;
      u64TaggedField1: bigint = 0n;

      @(Type.uint8().setNullable(true))
      u8Field2: number = 0;

      @(Type.uint16().setNullable(true))
      u16Field2: number = 0;

      @(Type.varUInt32().setNullable(true))
      u32VarField2: number = 0;

      @(Type.uint32().setNullable(true))
      u32FixedField2: number = 0;

      @(Type.varUInt64().setNullable(true))
      u64VarField2: bigint = 0n;

      @(Type.uint64().setNullable(true))
      u64FixedField2: bigint = 0n;

      @(Type.taggedUInt64().setNullable(true))
      u64TaggedField2: bigint = 0n;
    }
    fory.registerSerializer(UnsignedSchemaCompatible);

    // Deserialize struct from Java
    let cursor = 0;
    const deserializedStruct = fory.deserialize(content.subarray(cursor));
    cursor += fory.binaryReader.readGetCursor();

    // Serialize the deserialized struct back
    const serializedData = fory.serialize(deserializedStruct);
    writeToFile(serializedData as Buffer);
  });
});
