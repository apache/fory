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

import Fory, { BinaryReader, Decimal, Type } from "../packages/core/index";
import { CompatibleScalarConverter } from "../packages/core/lib/compatible/scalar";
import { ConfigFlags, RefFlags, TypeId } from "../packages/core/lib/type";
import { BinaryWriter } from "../packages/core/lib/writer";
import { describe, expect, test } from "@jest/globals";

function decimal(unscaledValue: string | bigint | number, scale: number): Decimal {
  return new Decimal(unscaledValue, scale);
}

function decimalMagnitude(byteLength: number): bigint {
  return 1n << BigInt((byteLength - 1) * 8);
}

function decimalPayload(scale: number, magnitudeLength = 0) {
  const writer = new BinaryWriter();
  writer.writeUint8(ConfigFlags.isCrossLanguageFlag);
  writer.writeInt8(RefFlags.NotNullValueFlag);
  writer.writeUint8(TypeId.DECIMAL);
  const bodyOffset = writer.writeGetCursor();
  writer.writeVarInt32(scale);
  const scaleEnd = writer.writeGetCursor();
  if (magnitudeLength === 0) {
    writer.writeVarUInt64(4n);
    const magnitudeOffset = writer.writeGetCursor();
    return {
      bytes: writer.dump(),
      bodyOffset,
      scaleEnd,
      magnitudeOffset,
    };
  }
  const meta = BigInt(magnitudeLength) << 1n;
  writer.writeVarUInt64((meta << 1n) | 1n);
  const magnitudeOffset = writer.writeGetCursor();
  const magnitude = new Uint8Array(magnitudeLength);
  magnitude[magnitudeLength - 1] = 1;
  writer.buffer(magnitude);
  return { bytes: writer.dump(), bodyOffset, scaleEnd, magnitudeOffset };
}

describe("decimal", () => {
  test("round-trips root decimal edge cases", () => {
    const fory = new Fory({ compatible: false });
    const values = [
      decimal(0n, 0),
      decimal(0n, 3),
      decimal(1n, 0),
      decimal(-1n, 0),
      decimal(12345n, 2),
      decimal("9223372036854775807", 0),
      decimal("-9223372036854775808", 0),
      decimal("4611686018427387903", 0),
      decimal("-4611686018427387904", 0),
      decimal("9223372036854775808", 0),
      decimal("-9223372036854775809", 0),
      decimal("123456789012345678901234567890123456789", 37),
      decimal("-123456789012345678901234567890123456789", -17),
    ];

    for (const value of values) {
      const roundTrip = fory.deserialize(fory.serialize(value)) as Decimal;
      expect(roundTrip).toBeInstanceOf(Decimal);
      expect(roundTrip.equals(value)).toBe(true);
    }
  });

  test("round-trips struct decimal fields", () => {
    const fory = new Fory({ compatible: false });
    const serializer = fory.register(
      Type.struct(
        {
          typeName: "example.DecimalEnvelope",
        },
        {
          amount: Type.decimal(),
          note: Type.string(),
        },
      ),
    ).serializer;
    const value = {
      amount: decimal("123456789012345678901234567890123456789", 37),
      note: "principal",
    };

    const roundTrip = fory.deserialize(fory.serialize(value, serializer), serializer) as {
      amount: Decimal;
      note: string;
    };

    expect(roundTrip.amount).toBeInstanceOf(Decimal);
    expect(roundTrip.amount.equals(value.amount)).toBe(true);
    expect(roundTrip.note).toBe("principal");
  });

  test("keeps decimals out of reference tracking", () => {
    const fory = new Fory({ compatible: false, ref: true });
    const decimalType = Type.decimal().setTrackingRef(true);
    const decimalSerializer = fory.register(decimalType);
    expect(decimalSerializer.serializer.needToWriteRef()).toBe(false);

    const value = decimal(12345, 2);
    const rootBytes = fory.serialize(value);
    const reader = new BinaryReader({});
    reader.reset(rootBytes);
    expect(reader.readUint8()).toBe(ConfigFlags.isCrossLanguageFlag);
    expect(reader.readInt8()).toBe(RefFlags.NotNullValueFlag);
    expect(reader.readUint8()).toBe(TypeId.DECIMAL);

    const shared = ["shared"];
    const result = fory.deserialize(fory.serialize([value, shared, shared])) as [
      Decimal,
      string[],
      string[],
    ];

    expect(result[0].equals(value)).toBe(true);
    expect(result[1]).toBe(result[2]);
  });

  test("rejects non-canonical big decimal payloads", () => {
    const fory = new Fory({ compatible: false });
    const zeroBigEncoding = Buffer.from([0x01, 0xff, 0x28, 0x00, 0x01]);
    const trailingZeroPayload = Buffer.from([0x01, 0xff, 0x28, 0x00, 0x09, 0x01, 0x00]);

    expect(() => fory.deserialize(zeroBigEncoding)).toThrow(/Invalid decimal magnitude length/);
    expect(() => fory.deserialize(trailingZeroPayload)).toThrow(/trailing zero byte/);
  });

  test("round-trips a large sparse magnitude", () => {
    const fory = new Fory({ compatible: false });
    const highShift = 4096n * 8n;
    const middleShift = 2048n * 8n;
    const magnitude = (1n << highShift) | (0xabn << middleShift) | 0x5an;
    const value = decimal(-magnitude, 19);

    const roundTrip = fory.deserialize(fory.serialize(value)) as Decimal;

    expect(roundTrip.equals(value)).toBe(true);
  });

  test("enforces the scale limit", () => {
    const fory = new Fory({ compatible: false });
    const bodyOffset = decimalPayload(0).bodyOffset;
    const cases = [
      { scale: -2_147_483_648, accepted: false },
      { scale: -10_001, accepted: false },
      { scale: -10_000, accepted: true },
      { scale: 10_000, accepted: true },
      { scale: 10_001, accepted: false },
      { scale: 2_147_483_647, accepted: false },
    ];

    for (const { scale, accepted } of cases) {
      const value = decimal(1n, scale);
      if (accepted) {
        const roundTrip = fory.deserialize(fory.serialize(value)) as Decimal;
        expect(roundTrip.equals(value)).toBe(true);
      } else {
        expect(() => fory.serialize(value)).toThrow(/Decimal scale/);
        expect((fory as any).writeContext.writer.writeGetCursor()).toBe(bodyOffset);
      }

      const payload = decimalPayload(scale);
      if (accepted) {
        const decoded = fory.deserialize(payload.bytes) as Decimal;
        expect(decoded.equals(value)).toBe(true);
      } else {
        expect(() => fory.deserialize(payload.bytes)).toThrow(/Decimal scale/);
        expect((fory as any).readContext.reader.readGetCursor()).toBe(payload.scaleEnd);
      }
    }
  });

  test("enforces the magnitude byte limit", () => {
    const fory = new Fory({ compatible: false });
    const bodyOffset = decimalPayload(0).bodyOffset;
    const cases = [
      { magnitudeLength: 10_000, accepted: true },
      { magnitudeLength: 10_001, accepted: false },
    ];

    for (const { magnitudeLength, accepted } of cases) {
      const value = decimal(decimalMagnitude(magnitudeLength), accepted ? 0 : 7);
      if (accepted) {
        const roundTrip = fory.deserialize(fory.serialize(value)) as Decimal;
        expect(roundTrip.equals(value)).toBe(true);
      } else {
        const writer = (fory as any).writeContext.writer;
        const bodyBefore = Array.from(
          writer.getPlatformBuffer().subarray(bodyOffset, bodyOffset + 5),
        );
        expect(() => fory.serialize(value)).toThrow(/Decimal magnitude/);
        expect(writer.writeGetCursor()).toBe(bodyOffset);
        expect(Array.from(writer.getPlatformBuffer().subarray(bodyOffset, bodyOffset + 5))).toEqual(
          bodyBefore,
        );
      }

      const payload = decimalPayload(0, magnitudeLength);
      if (accepted) {
        const decoded = fory.deserialize(payload.bytes) as Decimal;
        expect(decoded.equals(value)).toBe(true);
      } else {
        expect(() => fory.deserialize(payload.bytes)).toThrow(/Decimal magnitude length/);
        expect((fory as any).readContext.reader.readGetCursor()).toBe(payload.magnitudeOffset);
      }
    }
  });

  test("enforces compatible wire limits", () => {
    const reader = new BinaryReader({});
    for (const scale of [-2_147_483_648, -10_001, -10_000, 10_000, 10_001, 2_147_483_647]) {
      const payload = decimalPayload(scale);
      reader.reset(payload.bytes.subarray(payload.bodyOffset));
      if (scale >= -10_000 && scale <= 10_000) {
        expect(CompatibleScalarConverter.readDecimal(reader).equals(decimal(1n, scale))).toBe(true);
      } else {
        expect(() => CompatibleScalarConverter.readDecimal(reader)).toThrow(/Decimal scale/);
        expect(reader.readGetCursor()).toBe(payload.scaleEnd - payload.bodyOffset);
      }
    }

    for (const magnitudeLength of [10_000, 10_001]) {
      const payload = decimalPayload(0, magnitudeLength);
      reader.reset(payload.bytes.subarray(payload.bodyOffset));
      if (magnitudeLength === 10_000) {
        expect(
          CompatibleScalarConverter.readDecimal(reader).equals(
            decimal(decimalMagnitude(magnitudeLength), 0),
          ),
        ).toBe(true);
      } else {
        expect(() => CompatibleScalarConverter.readDecimal(reader)).toThrow(
          /Decimal magnitude length/,
        );
        expect(reader.readGetCursor()).toBe(payload.magnitudeOffset - payload.bodyOffset);
      }
    }
  });
});
