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

import type { TypeInfo } from "../typeInfo";
import { TypeId } from "../type";
import type { BinaryReader } from "../reader";
import { Decimal, DecimalCodec } from "../types/decimal";
import { fromBFloat16Bits, toBFloat16Bits } from "../types/bfloat16";
import { fromFloat16Bits, toFloat16Bits } from "../types/float16";

export type CompatibleScalarReadAction = {
  remoteTypeId: number;
  localTypeId: number;
  remoteNullable?: boolean;
};

type ScalarKind = "bool" | "string" | "number";

type DecimalParts = {
  unscaled: bigint;
  scale: number;
  negativeZero: boolean;
};

const scalarReadActions = new WeakMap<TypeInfo, CompatibleScalarReadAction>();
const scalarSkipActions = new WeakSet<TypeInfo>();

const float32Array = new Float32Array(1);
const float64Buffer = new ArrayBuffer(8);
const float64DataView = new DataView(float64Buffer);

const INT8_MIN = -128n;
const INT8_MAX = 127n;
const INT16_MIN = -32768n;
const INT16_MAX = 32767n;
const INT32_MIN = -2147483648n;
const INT32_MAX = 2147483647n;
const MAX_COMPATIBLE_DECIMAL_DIGITS = 256;
const MAX_COMPATIBLE_NUMERIC_TEXT_LENGTH = 320;
const MAX_COMPATIBLE_DECIMAL_MAGNITUDE
  = 10n ** BigInt(MAX_COMPATIBLE_DECIMAL_DIGITS);
const INT64_MIN = -(1n << 63n);
const INT64_MAX = (1n << 63n) - 1n;
const UINT8_MAX = 255n;
const UINT16_MAX = 65535n;
const UINT32_MAX = 4294967295n;
const UINT64_MAX = (1n << 64n) - 1n;

export function markCompatibleScalarRead(
  typeInfo: TypeInfo,
  action: CompatibleScalarReadAction,
): TypeInfo {
  scalarReadActions.set(typeInfo, action);
  return typeInfo;
}

export function getCompatibleScalarReadAction(
  typeInfo: TypeInfo,
): CompatibleScalarReadAction | undefined {
  return scalarReadActions.get(typeInfo);
}

export function markCompatibleScalarSkipRead(typeInfo: TypeInfo): TypeInfo {
  scalarSkipActions.add(typeInfo);
  return typeInfo;
}

export function shouldSkipCompatibleScalarRead(typeInfo: TypeInfo): boolean {
  return scalarSkipActions.has(typeInfo);
}

export function isCompatibleScalarType(typeId: number): boolean {
  return scalarKind(typeId) !== undefined;
}

export function isCompatibleScalarPair(
  remoteTypeId: number,
  localTypeId: number,
): boolean {
  if (remoteTypeId === localTypeId) {
    return scalarKind(remoteTypeId) !== undefined;
  }
  const remoteKind = scalarKind(remoteTypeId);
  const localKind = scalarKind(localTypeId);
  if (remoteKind === undefined || localKind === undefined) {
    return false;
  }
  if (remoteKind === "string") {
    return localKind === "bool" || localKind === "number";
  }
  if (localKind === "string") {
    return remoteKind === "bool" || remoteKind === "number";
  }
  if (remoteKind === "bool" || localKind === "bool") {
    return remoteKind === "number" || localKind === "number";
  }
  return remoteKind === "number" && localKind === "number";
}

function canonicalScalarTypeId(typeId: number): number {
  switch (typeId) {
    case TypeId.VARINT32:
      return TypeId.INT32;
    case TypeId.VARINT64:
    case TypeId.TAGGED_INT64:
      return TypeId.INT64;
    case TypeId.VAR_UINT32:
      return TypeId.UINT32;
    case TypeId.VAR_UINT64:
    case TypeId.TAGGED_UINT64:
      return TypeId.UINT64;
    default:
      return typeId;
  }
}

function scalarKind(typeId: number): ScalarKind | undefined {
  switch (canonicalScalarTypeId(typeId)) {
    case TypeId.BOOL:
      return "bool";
    case TypeId.STRING:
      return "string";
    case TypeId.INT8:
    case TypeId.INT16:
    case TypeId.INT32:
    case TypeId.INT64:
    case TypeId.UINT8:
    case TypeId.UINT16:
    case TypeId.UINT32:
    case TypeId.UINT64:
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
    case TypeId.FLOAT32:
    case TypeId.FLOAT64:
    case TypeId.DECIMAL:
      return "number";
    default:
      return undefined;
  }
}

function isFloatType(typeId: number): boolean {
  switch (canonicalScalarTypeId(typeId)) {
    case TypeId.FLOAT16:
    case TypeId.BFLOAT16:
    case TypeId.FLOAT32:
    case TypeId.FLOAT64:
      return true;
    default:
      return false;
  }
}

function readDecimal(reader: BinaryReader): Decimal {
  const scale = reader.readVarInt32();
  const header = reader.readVarUInt64();
  if ((header & 1n) === 0n) {
    return new Decimal(DecimalCodec.decodeZigZag64(header >> 1n), scale);
  }
  const meta = header >> 1n;
  const length = Number(meta >> 1n);
  if (length <= 0 || length > 0x7fffffff) {
    throw new Error(`Invalid decimal magnitude length ${length}.`);
  }
  const payload = reader.buffer(length);
  if (payload[length - 1] === 0) {
    throw new Error("Non-canonical decimal payload: trailing zero byte.");
  }
  const magnitude = DecimalCodec.fromCanonicalLittleEndianMagnitude(payload);
  if (magnitude === 0n) {
    throw new Error("Big decimal encoding must not represent zero.");
  }
  return new Decimal((meta & 1n) === 0n ? magnitude : -magnitude, scale);
}

function readScalarPayload(
  reader: BinaryReader,
  remoteTypeId: number,
): unknown {
  switch (remoteTypeId) {
    case TypeId.BOOL: {
      const value = reader.readUint8();
      if (value !== 0 && value !== 1) {
        throw new Error(`Invalid boolean scalar value ${value}.`);
      }
      return value === 1;
    }
    case TypeId.STRING:
      return reader.stringWithHeader();
    case TypeId.INT8:
      return reader.readInt8();
    case TypeId.INT16:
      return reader.readInt16();
    case TypeId.INT32:
      return reader.readInt32();
    case TypeId.VARINT32:
      return reader.readVarInt32();
    case TypeId.INT64:
      return reader.readInt64();
    case TypeId.VARINT64:
      return reader.readVarInt64();
    case TypeId.TAGGED_INT64:
      return reader.readTaggedInt64();
    case TypeId.UINT8:
      return reader.readUint8();
    case TypeId.UINT16:
      return reader.readUint16();
    case TypeId.UINT32:
      return reader.readUint32();
    case TypeId.VAR_UINT32:
      return reader.readVarUInt32();
    case TypeId.UINT64:
      return reader.readUint64();
    case TypeId.VAR_UINT64:
      return reader.readVarUInt64();
    case TypeId.TAGGED_UINT64:
      return reader.readTaggedUInt64();
    case TypeId.FLOAT16:
      return reader.readFloat16();
    case TypeId.BFLOAT16:
      return reader.readBfloat16();
    case TypeId.FLOAT32:
      return reader.readFloat32();
    case TypeId.FLOAT64:
      return reader.readFloat64();
    case TypeId.DECIMAL:
      return readDecimal(reader);
    default:
      throw new Error(`Unsupported compatible scalar type ${remoteTypeId}.`);
  }
}

function pow10(exp: number): bigint {
  let result = 1n;
  for (let i = 0; i < exp; i++) {
    result *= 10n;
  }
  return result;
}

function pow5(exp: number): bigint {
  let result = 1n;
  for (let i = 0; i < exp; i++) {
    result *= 5n;
  }
  return result;
}

function normalizeParts(value: DecimalParts): DecimalParts {
  let { unscaled, scale } = value;
  if (unscaled === 0n) {
    return { unscaled: 0n, scale: 0, negativeZero: value.negativeZero };
  }
  while (scale > 0 && unscaled % 10n === 0n) {
    unscaled /= 10n;
    scale--;
  }
  const digits = decimalDigitCount(unscaled);
  if (
    scale > MAX_COMPATIBLE_DECIMAL_DIGITS
    || digits > MAX_COMPATIBLE_DECIMAL_DIGITS
  ) {
    throw new Error(
      "Scalar decimal magnitude exceeds compatible conversion limit.",
    );
  }
  return { unscaled, scale, negativeZero: false };
}

function decimalToParts(value: Decimal): DecimalParts {
  if (value.unscaledValue === 0n) {
    return { unscaled: 0n, scale: 0, negativeZero: false };
  }
  if (value.scale < 0) {
    const digits = decimalDigitCount(value.unscaledValue);
    if (digits - value.scale > MAX_COMPATIBLE_DECIMAL_DIGITS) {
      throw new Error(
        "Scalar decimal magnitude exceeds compatible conversion limit.",
      );
    }
    return normalizeParts({
      unscaled: value.unscaledValue * pow10(-value.scale),
      scale: 0,
      negativeZero: false,
    });
  }
  return normalizeParts({
    unscaled: value.unscaledValue,
    scale: value.scale,
    negativeZero: false,
  });
}

function integerToParts(value: number | bigint): DecimalParts {
  return {
    unscaled: typeof value === "bigint" ? value : BigInt(value),
    scale: 0,
    negativeZero: false,
  };
}

function floatToParts(value: number): DecimalParts {
  if (!Number.isFinite(value)) {
    throw new Error(`Non-finite scalar value ${value}.`);
  }
  if (Object.is(value, -0)) {
    return { unscaled: 0n, scale: 0, negativeZero: true };
  }
  if (value === 0) {
    return { unscaled: 0n, scale: 0, negativeZero: false };
  }
  float64DataView.setFloat64(0, value, false);
  const bits = float64DataView.getBigUint64(0, false);
  const negative = bits >> 63n !== 0n;
  const exponentBits = Number((bits >> 52n) & 0x7ffn);
  const fraction = bits & ((1n << 52n) - 1n);
  let mantissa: bigint;
  let exponent: number;
  if (exponentBits === 0) {
    mantissa = fraction;
    exponent = 1 - 1023 - 52;
  } else {
    mantissa = (1n << 52n) | fraction;
    exponent = exponentBits - 1023 - 52;
  }
  if (negative) {
    mantissa = -mantissa;
  }
  if (exponent >= 0) {
    return normalizeParts({
      unscaled: mantissa << BigInt(exponent),
      scale: 0,
      negativeZero: false,
    });
  }
  const scale = -exponent;
  if (scale > MAX_COMPATIBLE_DECIMAL_DIGITS) {
    throw new Error("Scalar float decimal expansion exceeds compatible limit.");
  }
  return normalizeParts({
    unscaled: mantissa * pow5(scale),
    scale,
    negativeZero: false,
  });
}

function parseDecimalString(value: string): DecimalParts {
  if (value.length === 0 || value.length > MAX_COMPATIBLE_NUMERIC_TEXT_LENGTH) {
    throw new Error(`Invalid scalar string "${value}".`);
  }

  let index = 0;
  const negative = value[index] === "-";
  if (negative) {
    index++;
    if (index === value.length) {
      throw new Error(`Invalid scalar string "${value}".`);
    }
  }

  const integerStart = index;
  let significantDigits = 0;
  let seenNonZero = false;
  if (value[index] === "0") {
    index++;
    if (index < value.length && isDigit(value[index])) {
      throw new Error(`Invalid scalar string "${value}".`);
    }
  } else if (value[index] >= "1" && value[index] <= "9") {
    while (index < value.length && isDigit(value[index])) {
      if (value[index] !== "0" || seenNonZero) {
        seenNonZero = true;
        significantDigits++;
      }
      index++;
    }
  } else {
    throw new Error(`Invalid scalar string "${value}".`);
  }
  const integerEnd = index;

  let fractionStart = index;
  let fractionEnd = index;
  if (index < value.length && value[index] === ".") {
    index++;
    fractionStart = index;
    while (index < value.length && isDigit(value[index])) {
      if (value[index] !== "0" || seenNonZero) {
        seenNonZero = true;
        significantDigits++;
      }
      index++;
    }
    if (index === fractionStart) {
      throw new Error(`Invalid scalar string "${value}".`);
    }
    fractionEnd = index;
  }

  let exponent = 0;
  if (index < value.length && (value[index] === "e" || value[index] === "E")) {
    index++;
    let exponentNegative = false;
    if (index < value.length && value[index] === "-") {
      exponentNegative = true;
      index++;
    }
    if (index === value.length) {
      throw new Error(`Invalid scalar string "${value}".`);
    }
    if (value[index] === "0") {
      index++;
      if (index < value.length && isDigit(value[index])) {
        throw new Error(`Invalid scalar string "${value}".`);
      }
    } else if (value[index] >= "1" && value[index] <= "9") {
      while (index < value.length && isDigit(value[index])) {
        exponent = exponent * 10 + value.charCodeAt(index) - 48;
        if (exponent > MAX_COMPATIBLE_DECIMAL_DIGITS) {
          throw new Error(`Invalid scalar string exponent in "${value}".`);
        }
        index++;
      }
    } else {
      throw new Error(`Invalid scalar string "${value}".`);
    }
    if (exponentNegative) {
      exponent = -exponent;
    }
  }
  if (
    index !== value.length
    || significantDigits > MAX_COMPATIBLE_DECIMAL_DIGITS
  ) {
    throw new Error(`Invalid scalar string magnitude in "${value}".`);
  }
  let scale = fractionEnd - fractionStart - exponent;
  if (!decimalShapeFits(significantDigits, scale)) {
    throw new Error(`Invalid scalar string magnitude in "${value}".`);
  }

  const digits = `${value.slice(integerStart, integerEnd)}${value.slice(fractionStart, fractionEnd)}`;
  let unscaled = BigInt(digits.length === 0 ? "0" : digits);
  if (negative) {
    unscaled = -unscaled;
  }
  if (unscaled === 0n) {
    return { unscaled: 0n, scale: 0, negativeZero: negative };
  }
  if (scale < 0) {
    unscaled *= pow10(-scale);
    scale = 0;
  }
  return {
    unscaled,
    scale,
    negativeZero: negative && unscaled === 0n,
  };
}

function decimalShapeFits(significantDigits: number, scale: number): boolean {
  if (scale > MAX_COMPATIBLE_DECIMAL_DIGITS) {
    return false;
  }
  return (
    scale >= 0 || significantDigits + -scale <= MAX_COMPATIBLE_DECIMAL_DIGITS
  );
}

function decimalDigitCount(value: bigint): number {
  const magnitude = value < 0n ? -value : value;
  if (magnitude >= MAX_COMPATIBLE_DECIMAL_MAGNITUDE) {
    return MAX_COMPATIBLE_DECIMAL_DIGITS + 1;
  }
  return magnitude.toString().length;
}

function isDigit(value: string): boolean {
  return value >= "0" && value <= "9";
}

function formatParts(value: DecimalParts, forceDecimal: boolean): string {
  const normalized = normalizeParts(value);
  if (normalized.unscaled === 0n) {
    if (normalized.negativeZero) {
      return forceDecimal ? "-0.0" : "-0";
    }
    return forceDecimal ? "0.0" : "0";
  }
  const negative = normalized.unscaled < 0n;
  const digits = (
    negative ? -normalized.unscaled : normalized.unscaled
  ).toString();
  let result: string;
  if (normalized.scale === 0) {
    result = forceDecimal ? `${digits}.0` : digits;
  } else if (digits.length > normalized.scale) {
    const split = digits.length - normalized.scale;
    result = `${digits.slice(0, split)}.${digits.slice(split)}`;
  } else {
    result = `0.${"0".repeat(normalized.scale - digits.length)}${digits}`;
  }
  return negative ? `-${result}` : result;
}

function partsEqual(left: DecimalParts, right: DecimalParts): boolean {
  const l = normalizeParts(left);
  const r = normalizeParts(right);
  return (
    l.unscaled === r.unscaled
    && l.scale === r.scale
    && l.negativeZero === r.negativeZero
  );
}

function partsToNumber(value: DecimalParts): number {
  return Number(formatParts(value, false));
}

function exactInteger(value: DecimalParts): bigint {
  const normalized = normalizeParts(value);
  if (normalized.scale !== 0) {
    throw new Error("Scalar value is not an integer.");
  }
  return normalized.unscaled;
}

function exactSafeNumber(value: bigint): number {
  const result = Number(value);
  if (!Number.isSafeInteger(result) || BigInt(result) !== value) {
    throw new Error(
      `Scalar integer ${value.toString()} is not exactly representable as a number.`,
    );
  }
  return result;
}

function exactFloat(value: DecimalParts, localTypeId: number): number {
  let candidate = partsToNumber(value);
  if (!Number.isFinite(candidate)) {
    throw new Error(
      "Scalar value is not exactly representable as a finite float.",
    );
  }
  switch (canonicalScalarTypeId(localTypeId)) {
    case TypeId.FLOAT16:
      candidate = fromFloat16Bits(toFloat16Bits(candidate));
      break;
    case TypeId.BFLOAT16:
      candidate = fromBFloat16Bits(toBFloat16Bits(candidate));
      break;
    case TypeId.FLOAT32:
      float32Array[0] = candidate;
      candidate = float32Array[0];
      break;
    default:
      break;
  }
  if (!partsEqual(value, floatToParts(candidate))) {
    throw new Error(
      "Scalar value is not exactly representable by the target float type.",
    );
  }
  return candidate;
}

function rangeCheckedInteger(
  value: bigint,
  localTypeId: number,
): number | bigint {
  switch (canonicalScalarTypeId(localTypeId)) {
    case TypeId.INT8:
      if (value < INT8_MIN || value > INT8_MAX)
        throw new Error("Scalar integer is outside int8 range.");
      return Number(value);
    case TypeId.INT16:
      if (value < INT16_MIN || value > INT16_MAX)
        throw new Error("Scalar integer is outside int16 range.");
      return Number(value);
    case TypeId.INT32:
      if (value < INT32_MIN || value > INT32_MAX)
        throw new Error("Scalar integer is outside int32 range.");
      return Number(value);
    case TypeId.INT64:
      if (value < INT64_MIN || value > INT64_MAX)
        throw new Error("Scalar integer is outside int64 range.");
      return value;
    case TypeId.UINT8:
      if (value < 0n || value > UINT8_MAX)
        throw new Error("Scalar integer is outside uint8 range.");
      return Number(value);
    case TypeId.UINT16:
      if (value < 0n || value > UINT16_MAX)
        throw new Error("Scalar integer is outside uint16 range.");
      return Number(value);
    case TypeId.UINT32:
      if (value < 0n || value > UINT32_MAX)
        throw new Error("Scalar integer is outside uint32 range.");
      return Number(value);
    case TypeId.UINT64:
      if (value < 0n || value > UINT64_MAX)
        throw new Error("Scalar integer is outside uint64 range.");
      return value;
    default:
      throw new Error("Target scalar type is not an integer.");
  }
}

function valueToParts(value: unknown, remoteTypeId: number): DecimalParts {
  if (value instanceof Decimal) {
    return decimalToParts(value);
  }
  if (typeof value === "bigint") {
    return integerToParts(value);
  }
  if (typeof value === "number") {
    return isFloatType(remoteTypeId)
      ? floatToParts(value)
      : integerToParts(value);
  }
  if (typeof value === "string") {
    return parseDecimalString(value);
  }
  if (typeof value === "boolean") {
    return integerToParts(value ? 1 : 0);
  }
  throw new Error("Unsupported scalar value.");
}

function convertToBool(value: unknown, remoteTypeId: number): boolean {
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "string") {
    switch (value) {
      case "0":
      case "false":
        return false;
      case "1":
      case "true":
        return true;
      default:
        throw new Error(`Scalar string "${value}" is not a boolean value.`);
    }
  }
  const integer = exactInteger(valueToParts(value, remoteTypeId));
  if (integer === 0n) {
    return false;
  }
  if (integer === 1n) {
    return true;
  }
  throw new Error("Scalar numeric value is not a boolean value.");
}

function convertToString(value: unknown, remoteTypeId: number): string {
  if (typeof value === "boolean") {
    return value ? "true" : "false";
  }
  const parts = valueToParts(value, remoteTypeId);
  return formatParts(parts, isFloatType(remoteTypeId));
}

function convertToDecimal(value: unknown, remoteTypeId: number): Decimal {
  const parts = valueToParts(value, remoteTypeId);
  const normalized = normalizeParts(parts);
  return new Decimal(normalized.unscaled, normalized.scale);
}

function convertToNumber(
  value: unknown,
  remoteTypeId: number,
  localTypeId: number,
): number | bigint | Decimal {
  if (canonicalScalarTypeId(localTypeId) === TypeId.DECIMAL) {
    return convertToDecimal(value, remoteTypeId);
  }
  if (isFloatType(localTypeId)) {
    if (typeof value === "number" && !Number.isFinite(value)) {
      if (Number.isNaN(value)) {
        throw new Error("Scalar NaN cannot be converted losslessly.");
      }
      return value;
    }
    return exactFloat(valueToParts(value, remoteTypeId), localTypeId);
  }
  const integer = exactInteger(valueToParts(value, remoteTypeId));
  const result = rangeCheckedInteger(integer, localTypeId);
  if (typeof result === "number") {
    return exactSafeNumber(BigInt(result));
  }
  return result;
}

export class CompatibleScalarConverter {
  static read(
    reader: BinaryReader,
    remoteTypeId: number,
    localTypeId: number,
    fieldName: string,
  ): unknown {
    try {
      const value = readScalarPayload(reader, remoteTypeId);
      if (remoteTypeId === localTypeId) {
        return value;
      }
      switch (scalarKind(localTypeId)) {
        case "bool":
          return convertToBool(value, remoteTypeId);
        case "string":
          return convertToString(value, remoteTypeId);
        case "number":
          return convertToNumber(value, remoteTypeId, localTypeId);
        default:
          throw new Error(`Unsupported target scalar type ${localTypeId}.`);
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new Error(
        `Failed to convert compatible field ${fieldName}: ${message}`,
      );
    }
  }
}
