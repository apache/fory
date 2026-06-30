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
import { describe, expect, test } from '@jest/globals';

const KNOWN_SLACK_BYTES = 64 * 1024;

function serializeAny(value: unknown) {
  return new Fory({ compatible: false, ref: true }).serialize(value);
}

function deserializeAny(bytes: Uint8Array, maxContainerMemoryBytes: number) {
  return new Fory({
    compatible: false,
    ref: true,
    maxContainerMemoryBytes,
  }).deserialize(bytes);
}

describe('container memory budget', () => {
  test('uses known length auto budget', () => {
    const inputBytes = 17;
    const fory = new Fory({ compatible: false });
    const budget = inputBytes * 8 + KNOWN_SLACK_BYTES;

    fory.readContext.reset(new Uint8Array(inputBytes));
    expect(() => fory.readContext.reserveContainerMemory(budget)).not.toThrow();
    expect(() => fory.readContext.reserveContainerMemory(1)).toThrow(
      /maxContainerMemoryBytes/,
    );
  });

  test('validates explicit config', () => {
    expect(() => new Fory({ maxContainerMemoryBytes: 0 })).toThrow(
      /maxContainerMemoryBytes/,
    );
    expect(() => new Fory({ maxContainerMemoryBytes: -2 })).toThrow(
      /maxContainerMemoryBytes/,
    );

    const fory = new Fory({ maxContainerMemoryBytes: 24 });
    fory.readContext.reset(new Uint8Array(1));
    expect(() => fory.readContext.reserveContainerMemory(0)).not.toThrow();
    expect(() => fory.readContext.reserveContainerMemory(24)).not.toThrow();
    expect(() => fory.readContext.reserveContainerMemory(1)).toThrow(
      /maxContainerMemoryBytes/,
    );
  });

  test('uses parent storage for nested empty containers', () => {
    const typeInfo = Type.struct('budget.nested.empty', {
      values: Type.list(Type.list(Type.int32({ encoding: 'fixed' }))).setId(1),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({ values: [[]] });
    const passingReader = new Fory({
      compatible: false,
      ref: true,
      maxContainerMemoryBytes: 4,
    }).register(typeInfo);
    const failingReader = new Fory({
      compatible: false,
      ref: true,
      maxContainerMemoryBytes: 3,
    }).register(typeInfo);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxContainerMemoryBytes/,
    );
    expect(passingReader.deserialize(bytes)).toEqual({ values: [[]] });
  });

  test('reserves sibling containers cumulatively', () => {
    const typeInfo = Type.struct('budget.sibling.empty', {
      values: Type.list(Type.list(Type.int32({ encoding: 'fixed' }))).setId(1),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({
      values: [[], [], []],
    });
    const passingReader = new Fory({
      compatible: false,
      ref: true,
      maxContainerMemoryBytes: 12,
    }).register(typeInfo);
    const failingReader = new Fory({
      compatible: false,
      ref: true,
      maxContainerMemoryBytes: 11,
    }).register(typeInfo);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxContainerMemoryBytes/,
    );
    expect(passingReader.deserialize(bytes)).toEqual({
      values: [[], [], []],
    });
  });

  test('reserves map entries', () => {
    const bytes = serializeAny(new Map([[1, 2]]));

    expect(() => deserializeAny(bytes, 7)).toThrow(/maxContainerMemoryBytes/);
    expect(deserializeAny(bytes, 8)).toEqual(new Map([[1, 2]]));
  });

  test('reserves generated containers', () => {
    const typeInfo = Type.struct('budget.generated', {
      list: Type.list(Type.int32({ encoding: 'fixed' })).setId(1),
      set: Type.set(Type.string()).setId(2),
      map: Type.map(Type.string(), Type.int32({ encoding: 'fixed' })).setId(3),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({
      list: [1],
      set: new Set(['a']),
      map: new Map([['k', 1]]),
    });
    const passingReader = new Fory({
      compatible: false,
      ref: true,
      maxContainerMemoryBytes: 16,
    }).register(typeInfo);
    const failingReader = new Fory({
      compatible: false,
      ref: true,
      maxContainerMemoryBytes: 15,
    }).register(typeInfo);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxContainerMemoryBytes/,
    );
    expect(passingReader.deserialize(bytes)).toEqual({
      list: [1],
      set: new Set(['a']),
      map: new Map([['k', 1]]),
    });
  });

  test('reserves compatible typed arrays', () => {
    const writerType = Type.struct(9010, {
      values: Type.list(Type.int32({ encoding: 'fixed' })).setId(1),
    });
    const readerType = Type.struct(9010, {
      values: Type.int32Array().setId(1),
    });
    const writer = new Fory({ compatible: true });
    const bytes = writer.register(writerType).serialize({ values: [1, 2, 3] });
    const passingReader = new Fory({
      compatible: true,
      maxContainerMemoryBytes: 12,
    }).register(readerType);
    const failingReader = new Fory({
      compatible: true,
      maxContainerMemoryBytes: 11,
    }).register(readerType);

    expect(() => failingReader.deserialize(bytes)).toThrow(
      /maxContainerMemoryBytes/,
    );
    expect(Array.from(passingReader.deserialize(bytes).values)).toEqual([
      1,
      2,
      3,
    ]);
  });

  test('skips scalar dense owners', () => {
    const typeInfo = Type.struct('budget.skipped', {
      text: Type.string().setId(1),
      binary: Type.binary().setId(2),
      values: Type.int32Array().setId(3),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({
      text: 'hello',
      binary: new Uint8Array([1, 2, 3]),
      values: new Int32Array([1, 2, 3]),
    });
    const reader = new Fory({
      compatible: false,
      ref: true,
      maxContainerMemoryBytes: 1,
    }).register(typeInfo);

    expect(reader.deserialize(bytes)).toEqual({
      text: 'hello',
      binary: new Uint8Array([1, 2, 3]),
      values: new Int32Array([1, 2, 3]),
    });
  });

  test('keeps byte checks', () => {
    const typeInfo = Type.struct('budget.bytecheck', {
      values: Type.int32Array().setId(1),
    });
    const writer = new Fory({ compatible: false, ref: true });
    const bytes = writer.register(typeInfo).serialize({
      values: new Int32Array([1, 2, 3]),
    });
    const reader = new Fory({
      compatible: false,
      ref: true,
      maxContainerMemoryBytes: 1024 * 1024,
    }).register(typeInfo);

    expect(() => reader.deserialize(bytes.slice(0, bytes.length - 1))).toThrow();
  });
});
