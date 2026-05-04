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

import Fory, { Type } from '../packages/core/index';
import { TypeMeta } from '../packages/core/lib/meta/TypeMeta';
import { BinaryReader } from '../packages/core/lib/reader';
import { describe, expect, test } from '@jest/globals';

const HAS_FIELDS_META_FLAG = 1n << 8n;
const COMPRESS_META_FLAG = 1n << 9n;
const META_SIZE_MASK = 0xFFn;
const HASH_SHIFT_BITS = 14n;

describe('typemeta', () => {
  test('writes TypeMeta header bits in the xlang layout', () => {
    const typeInfo = Type.struct(7001, {
      fullName: Type.string().setId(1),
      age: Type.int32().setId(2),
    });

    const bytes = TypeMeta.fromTypeInfo(typeInfo).toBytes();
    const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getBigUint64(0, true);

    expect(Number(header & META_SIZE_MASK)).toBe(bytes.length - 8);
    expect((header & HAS_FIELDS_META_FLAG) !== 0n).toBe(true);
    expect((header & COMPRESS_META_FLAG) !== 0n).toBe(false);
    expect(header >> HASH_SHIFT_BITS).toBeGreaterThan(0n);
  });

  test('keeps tagged direct payload order grouped instead of field-id ordered', () => {
    const typeMeta = TypeMeta.fromTypeInfo(Type.struct(7005, {
      stringValue: Type.string().setId(1),
      mapValue: Type.map(Type.string(), Type.int32()).setId(2),
      intValue: Type.int32().setId(10),
    }));

    expect(typeMeta.getFieldInfo().map((field) => field.fieldName)).toEqual([
      'intValue',
      'stringValue',
      'mapValue',
    ]);
  });

  test('writes the zero size extension when the TypeMeta body is exactly 0xFF bytes', () => {
    const typeMeta = TypeMeta.fromTypeInfo(Type.struct(7003, {})) as any;
    const body = new Uint8Array(0xFF);
    const bytes = typeMeta.prependHeader(body, false, false) as Uint8Array;
    const reader = new BinaryReader({});

    expect(bytes).toHaveLength(8 + 1 + body.length);
    expect(bytes[8]).toBe(0);

    reader.reset(bytes);
    const header = TypeMeta.readHeader(reader);
    TypeMeta.skipBody(reader, header);
    expect(reader.readGetCursor()).toBe(bytes.length);
  });

  test('regenerates compatible named serializers when schema changes but field count stays the same', () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct('example.item', {
      value: Type.string(),
    });
    const readerType = Type.struct('example.item', {
      value: Type.int32(),
    });

    const bytes = writerFory.register(writerType).serialize({ value: 'hello' });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(result).toEqual({ value: 'hello' });
  });

  test('remaps compatible tag-id fields onto local property names during regeneration', () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct(7002, {
      fullName: Type.string().setId(1),
      note: Type.string().setId(2),
    });
    const readerType = Type.struct(7002, {
      name: Type.string().setId(1),
      alias: Type.int32().setId(2),
    });

    const bytes = writerFory.register(writerType).serialize({
      fullName: 'Alice',
      note: 'ally',
    });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(result).toEqual({
      name: 'Alice',
      alias: 'ally',
    });
  });

  test('keeps compatible named schema evolution working when field count differs', () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const writerType = Type.struct('example.foo', {
      bar: Type.string(),
      bar2: Type.int32(),
    });
    const readerType = Type.struct('example.foo', {
      bar: Type.string(),
    });

    const bytes = writerFory.register(writerType).serialize({
      bar: 'hello',
      bar2: 123,
    });
    const result = readerFory.register(readerType).deserialize(bytes);

    expect(result).toEqual({
      bar: 'hello',
      bar2: 123,
    });
  });

  test('remaps regenerated compatible field names onto local snake_case properties', () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    class WriterHolder {
      animalMap = new Map<string, number>();
      marker = 0;
    }
    Type.struct(7004, {
      animalMap: Type.map(Type.string(), Type.any()),
      marker: Type.int32(),
    })(WriterHolder);

    class ReaderHolder {
      animal_map = new Map<string, number>();
    }
    Type.struct(7004, {
      animal_map: Type.map(Type.string(), Type.any()),
    })(ReaderHolder);

    const writerReg = writerFory.register(WriterHolder);
    const readerReg = readerFory.register(ReaderHolder);

    const value = new WriterHolder();
    value.animalMap.set('dog', 7);
    value.marker = 99;

    const result = readerReg.deserialize(writerReg.serialize(value));

    expect(result).toBeInstanceOf(ReaderHolder);
    expect(result.animal_map.get('dog')).toBe(7);
    expect((result as ReaderHolder & { animalMap?: Map<string, number> }).animalMap).toBeUndefined();
    expect((result as ReaderHolder & { marker?: number }).marker).toBe(99);
  });

  test('skips unknown named custom fields by falling back to any when no local field exists', () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    class MyExt {
      id = 0;
    }
    Type.ext('my_ext')(MyExt);

    const customSerializer = {
      write: (writeContext: any, value: MyExt) => {
        writeContext.writeVarInt32(value.id);
      },
      read: (readContext: any, result: MyExt) => {
        result.id = readContext.readVarInt32();
      },
    };

    writerFory.register(MyExt, customSerializer);
    readerFory.register(MyExt, customSerializer);

    class WriterWrapper {
      note = '';
      myExt = new MyExt();
    }
    Type.struct('example.wrapper', {
      note: Type.string(),
      myExt: Type.ext('my_ext'),
    })(WriterWrapper);

    class EmptyWrapper {}
    Type.struct('example.wrapper', {})(EmptyWrapper);

    const writerReg = writerFory.register(WriterWrapper);
    const readerReg = readerFory.register(EmptyWrapper);

    const value = new WriterWrapper();
    value.note = 'hello';
    value.myExt.id = 42;

    const result = readerReg.deserialize(writerReg.serialize(value));

    expect(result).toBeInstanceOf(EmptyWrapper);
  });

  test('skips unknown compatible enum fields when regenerating an empty reader', () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const TestEnum = {
      VALUE_A: 0,
      VALUE_B: 1,
      VALUE_C: 2,
    };
    writerFory.register(Type.enum(7101, TestEnum));
    readerFory.register(Type.enum(7101, TestEnum));

    class WriterStruct {
      f1 = TestEnum.VALUE_A;
      f2 = TestEnum.VALUE_B;
    }
    Type.struct(7102, {
      f1: Type.enum(7101, TestEnum),
      f2: Type.enum(7101, TestEnum),
    })(WriterStruct);

    class EmptyStruct {}
    Type.struct(7102, {})(EmptyStruct);

    const writerReg = writerFory.register(WriterStruct);
    const readerReg = readerFory.register(EmptyStruct);

    const value = new WriterStruct();
    const result = readerReg.deserialize(writerReg.serialize(value));

    expect(result).toBeInstanceOf(EmptyStruct);
  });

  test('skips unknown enum and named custom fields together during compatible regeneration', () => {
    const writerFory = new Fory({ compatible: true });
    const readerFory = new Fory({ compatible: true });

    const Color = {
      Green: 0,
      Red: 1,
      Blue: 2,
      White: 3,
    };
    writerFory.register(Type.enum('color', Color));
    readerFory.register(Type.enum('color', Color));

    class MyExt {
      id = 0;
    }
    Type.ext('my_ext')(MyExt);

    const customSerializer = {
      write: (writeContext: any, value: MyExt) => {
        writeContext.writeVarInt32(value.id);
      },
      read: (readContext: any, result: MyExt) => {
        result.id = readContext.readVarInt32();
      },
    };

    writerFory.register(MyExt, customSerializer);
    readerFory.register(MyExt, customSerializer);

    class MyStruct {
      id = 0;
    }
    Type.struct('my_struct', {
      id: Type.int32(),
    })(MyStruct);

    writerFory.register(MyStruct);
    readerFory.register(MyStruct);

    class WriterWrapper {
      color = Color.White;
      myStruct = new MyStruct();
      myExt = new MyExt();
    }
    Type.struct('my_wrapper', {
      color: Type.enum('color', Color),
      myStruct: Type.struct('my_struct'),
      myExt: Type.ext('my_ext'),
    })(WriterWrapper);

    class EmptyWrapper {}
    Type.struct('my_wrapper', {})(EmptyWrapper);

    const writerReg = writerFory.register(WriterWrapper);
    const readerReg = readerFory.register(EmptyWrapper);

    const value = new WriterWrapper();
    value.myStruct.id = 42;
    value.myExt.id = 43;

    const result = readerReg.deserialize(writerReg.serialize(value));

    expect(result).toBeInstanceOf(EmptyWrapper);
  });
});
