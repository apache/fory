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

import Fory, { Decimal, Type } from "../packages/core/index";
import { describe, expect, test } from "@jest/globals";

function decimal(unscaledValue: string | bigint | number, scale: number): Decimal {
  return new Decimal(unscaledValue, scale);
}

describe("decimal", () => {
  test("round-trips root decimal edge cases", () => {
    const fory = new Fory();
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
    const fory = new Fory();
    const serializer = fory.register(Type.struct({
      typeName: "example.DecimalEnvelope",
    }, {
      amount: Type.decimal(),
      note: Type.string(),
    })).serializer;
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

  test("rejects non-canonical big decimal payloads", () => {
    const fory = new Fory();
    const zeroBigEncoding = Buffer.from([
      0x02,
      0xff,
      0x28,
      0x00,
      0x01,
    ]);
    const trailingZeroPayload = Buffer.from([
      0x02,
      0xff,
      0x28,
      0x00,
      0x09,
      0x01,
      0x00,
    ]);

    expect(() => fory.deserialize(zeroBigEncoding)).toThrow(/Invalid decimal magnitude length/);
    expect(() => fory.deserialize(trailingZeroPayload)).toThrow(/trailing zero byte/);
  });
});
