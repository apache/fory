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

import { MetaStringReader, MetaStringWriter } from "../packages/core/lib/context";
import {
  Encoding,
  MetaStringDecoder,
  MetaStringEncoder,
} from "../packages/core/lib/meta/MetaString";
import { BinaryReader } from "../packages/core/lib/reader";
import { BinaryWriter } from "../packages/core/lib/writer";
import { describe, expect, test } from "@jest/globals";

function readerFor(bytes: Uint8Array): BinaryReader {
  const reader = new BinaryReader({});
  reader.reset(bytes);
  return reader;
}

describe("meta string", () => {
  test("rejects a trailing uppercase marker", () => {
    const encoded = new MetaStringEncoder("$", "_").encodeByEncoding(
      "|",
      Encoding.ALL_TO_LOWER_SPECIAL,
    );
    const reader = readerFor(encoded.getBytes());
    const decoder = new MetaStringDecoder("$", "_");

    expect(() =>
      decoder.decode(reader, encoded.getBytes().length, Encoding.ALL_TO_LOWER_SPECIAL),
    ).toThrow("Malformed ALL_TO_LOWER_SPECIAL encoding: trailing uppercase marker");
  });

  test("round trips dynamic references", () => {
    const metaStringWriter = new MetaStringWriter();
    const first = metaStringWriter.encodeTypeName("first");
    const second = metaStringWriter.encodeTypeName("second");
    const writer = new BinaryWriter({});
    metaStringWriter.writeBytes(writer, first);
    metaStringWriter.writeBytes(writer, second);
    metaStringWriter.writeBytes(writer, first);

    const reader = readerFor(writer.dump());
    const metaStringReader = new MetaStringReader();
    expect(metaStringReader.readTypeName(reader)).toBe("first");
    expect(metaStringReader.readTypeName(reader)).toBe("second");
    expect(metaStringReader.readTypeName(reader)).toBe("first");
  });
});
