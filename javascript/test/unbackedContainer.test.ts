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

import Fory, { Type } from "../packages/core";
import { describe, expect, test } from "@jest/globals";

class EmptyExtension {}
class PartialExtension {}

Type.ext(8101)(EmptyExtension);
Type.ext(8105)(PartialExtension);

const emptyCodec = {
  write() {},
  read() {},
};

function emptyContainerFory(limit: number) {
  const fory = new Fory({
    compatible: false,
    ref: false,
    maxUnbackedContainerItems: limit,
  });
  fory.register(EmptyExtension, emptyCodec);
  return fory;
}

function partialContainerFory(limit: number) {
  let index = 0;
  const fory = new Fory({
    compatible: false,
    ref: false,
    maxUnbackedContainerItems: limit,
  });
  fory.register(PartialExtension, {
    write(writeContext) {
      if ((index++ & 1) === 0) {
        writeContext.writeUint8(1);
      }
    },
    read(readContext) {
      if ((index++ & 1) === 0) {
        readContext.readUint8();
      }
    },
  });
  return fory;
}

describe("unbacked container budget", () => {
  test("validates configuration", () => {
    expect(() => new Fory({ maxUnbackedContainerItems: -1 })).toThrow("maxUnbackedContainerItems");
    expect(() => new Fory({ maxUnbackedContainerItems: 1.5 })).toThrow("maxUnbackedContainerItems");
    expect(new Fory().config.maxUnbackedContainerItems).toBe(8192);
    expect(new Fory({ maxUnbackedContainerItems: 0 }).config.maxUnbackedContainerItems).toBe(0);
  });

  test("bounds custom collection bodies and resets after failure", () => {
    const writer = emptyContainerFory(8192);
    const writerList = writer.register(Type.list(Type.ext(8101)));
    const rejected = Uint8Array.from(
      writerList.serialize([new EmptyExtension(), new EmptyExtension(), new EmptyExtension()]),
    );
    const accepted = Uint8Array.from(
      writerList.serialize([new EmptyExtension(), new EmptyExtension()]),
    );

    const reader = emptyContainerFory(2);
    const readerList = reader.register(Type.list(Type.ext(8101)));
    expect(() => readerList.deserialize(rejected)).toThrow();
    expect(readerList.deserialize(accepted)).toHaveLength(2);
  });

  test("settles the final partial collection window", () => {
    const writer = emptyContainerFory(8192);
    const writerList = writer.register(Type.list(Type.ext(8101)));
    const bytes = Uint8Array.from([
      ...writerList.serialize(Array.from({ length: 1025 }, () => new EmptyExtension())),
      0x7f,
    ]);

    const reader = emptyContainerFory(1024);
    const readerList = reader.register(Type.list(Type.ext(8101)));
    expect(() => readerList.deserialize(bytes)).toThrow();
  });

  test("updates item and byte checkpoints together", () => {
    const writer = partialContainerFory(8192);
    const writerList = writer.register(Type.list(Type.ext(8105)));
    const values = Array.from({ length: 2048 }, () => new PartialExtension());
    const encoded = writerList.serialize(values);

    const exactReader = partialContainerFory(1024);
    const exactList = exactReader.register(Type.list(Type.ext(8105)));
    expect(exactList.deserialize(encoded)).toHaveLength(values.length);

    const rejected = Uint8Array.from([...encoded, 0x7f]);
    const strictReader = partialContainerFory(1023);
    const strictList = strictReader.register(Type.list(Type.ext(8105)));
    expect(() => strictList.deserialize(rejected)).toThrow();
  });

  test("settles map entries at protocol chunk boundaries", () => {
    const writer = emptyContainerFory(8192);
    const writerMap = writer.register(Type.map(Type.ext(8101), Type.ext(8101)));
    const values = new Map([
      [new EmptyExtension(), new EmptyExtension()],
      [new EmptyExtension(), new EmptyExtension()],
      [new EmptyExtension(), new EmptyExtension()],
    ]);
    const bytes = writerMap.serialize(values);

    const reader = emptyContainerFory(2);
    const readerMap = reader.register(Type.map(Type.ext(8101), Type.ext(8101)));
    expect(() => readerMap.deserialize(bytes)).toThrow();
  });

  test("keeps generated positive collection loops unguarded", () => {
    let generated = "";
    const fory = new Fory({
      compatible: true,
      ref: false,
      maxUnbackedContainerItems: 0,
      hooks: {
        afterCodeGenerated(code) {
          generated += code;
          return code;
        },
      },
    });
    const serializer = fory.register(
      Type.struct(8106, {
        values: Type.list(Type.int32({ encoding: "fixed" })),
        mapping: Type.map(Type.int32({ encoding: "fixed" }), Type.string()),
      }),
    );
    const value = {
      values: [1, 2, 3],
      mapping: new Map([[1, "one"]]),
    };
    const bytes = serializer.serialize(value);

    expect(serializer.deserialize(bytes)).toEqual(value);
    expect(generated).not.toContain("settleUnbackedContainerItems");
  });

  test("guards generated empty Struct collections", () => {
    const emptyType = Type.struct(8102, {});
    class EmptyStruct {}
    emptyType(EmptyStruct);

    const writer = new Fory({ compatible: true, ref: false });
    writer.register(EmptyStruct);
    const wrapperType = Type.struct(8107, { values: Type.list(emptyType) });
    const writerWrapper = writer.register(wrapperType);
    const bytes = writerWrapper.serialize({
      values: [new EmptyStruct(), new EmptyStruct(), new EmptyStruct()],
    });

    const reader = new Fory({
      compatible: true,
      ref: false,
      maxUnbackedContainerItems: 2,
    });
    reader.register(EmptyStruct);
    const readerWrapper = reader.register(wrapperType);
    expect(() => readerWrapper.deserialize(bytes)).toThrow();
  });

  test("charges compatible missing-field collection reads", () => {
    const childType = Type.struct(8104, {});
    class RemovedChild {}
    childType(RemovedChild);
    const writer = new Fory({ compatible: true, ref: false });
    writer.register(RemovedChild);
    const writerRoot = writer.register(
      Type.struct(8103, {
        removed: Type.list(childType),
      }),
    );
    const rejected = Uint8Array.from([
      ...writerRoot.serialize({
        removed: [new RemovedChild(), new RemovedChild(), new RemovedChild()],
      }),
      0x7f,
    ]);
    const accepted = Uint8Array.from(
      writerRoot.serialize({ removed: [new RemovedChild(), new RemovedChild()] }),
    );

    const reader = new Fory({
      compatible: true,
      ref: false,
      maxUnbackedContainerItems: 2,
    });
    reader.register(RemovedChild);
    const readerRoot = reader.register(Type.struct(8103, {}));
    expect(() => readerRoot.deserialize(rejected)).toThrow();
    expect(readerRoot.deserialize(accepted)).toEqual({});
  });
});
