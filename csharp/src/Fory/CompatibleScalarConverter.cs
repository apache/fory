// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

using System.Globalization;
using System.Numerics;
using System.Runtime.CompilerServices;

namespace Apache.Fory;

/// <summary>
/// Provides compatible scalar field conversion for generated serializers.
/// </summary>
/// <remarks>
/// This API is intended for Fory-generated compatible struct readers. User code should configure
/// compatible mode rather than calling it directly.
/// </remarks>
public static class CompatibleScalarConverter
{
    private static readonly BigInteger SByteMin = sbyte.MinValue;
    private static readonly BigInteger SByteMax = sbyte.MaxValue;
    private static readonly BigInteger Int16Min = short.MinValue;
    private static readonly BigInteger Int16Max = short.MaxValue;
    private static readonly BigInteger Int32Min = int.MinValue;
    private static readonly BigInteger Int32Max = int.MaxValue;
    private static readonly BigInteger Int64Min = long.MinValue;
    private static readonly BigInteger Int64Max = long.MaxValue;
    private static readonly BigInteger ByteMax = byte.MaxValue;
    private static readonly BigInteger UInt16Max = ushort.MaxValue;
    private static readonly BigInteger UInt32Max = uint.MaxValue;
    private static readonly BigInteger UInt64Max = ulong.MaxValue;
    private static readonly BigInteger DecimalMask = uint.MaxValue;
    private const int MaxCompatibleDecimalDigits = 256;
    private const int MaxCompatibleNumericTextLength = 320;
    private static readonly BigInteger MaxCompatibleDecimalMagnitude =
        BigInteger.Pow(10, MaxCompatibleDecimalDigits);

    /// <summary>
    /// Returns whether the field type id is in the compatible scalar family.
    /// </summary>
    public static bool IsScalarType(uint typeId)
    {
        return IsScalar(NormalizeScalarTypeId(typeId));
    }

    /// <summary>
    /// Returns whether a top-level compatible field can use scalar conversion.
    /// </summary>
    public static bool CanConvert(uint remoteTypeId, uint localTypeId)
    {
        TypeId remote = NormalizeScalarTypeId(remoteTypeId);
        TypeId local = NormalizeScalarTypeId(localTypeId);
        if (remote == local)
        {
            return remoteTypeId != localTypeId && IsScalar(remote);
        }

        if (!IsScalar(remote) || !IsScalar(local))
        {
            return false;
        }

        if (remote == TypeId.Bool)
        {
            return local == TypeId.String || IsNumeric(local);
        }

        if (local == TypeId.Bool)
        {
            return remote == TypeId.String || IsNumeric(remote);
        }

        if (remote == TypeId.String)
        {
            return IsNumeric(local);
        }

        if (local == TypeId.String)
        {
            return IsNumeric(remote);
        }

        return IsNumeric(remote) && IsNumeric(local);
    }

    /// <summary>
    /// Returns whether the generated compatible reader should read the field through this scalar reader.
    /// </summary>
    public static bool RequiresScalarRead(uint remoteTypeId, uint localTypeId)
    {
        TypeId remote = NormalizeScalarTypeId(remoteTypeId);
        TypeId local = NormalizeScalarTypeId(localTypeId);
        return IsScalar(remote) && IsScalar(local) && remoteTypeId != localTypeId &&
            (remote == local || CanConvert(remoteTypeId, localTypeId));
    }

    /// <summary>
    /// Reads a remote scalar payload and converts it to the local field type.
    /// </summary>
    [MethodImpl(MethodImplOptions.NoInlining)]
    public static T ReadField<T>(
        ReadContext context,
        TypeId remoteTypeId,
        TypeId localTypeId,
        string fieldName)
    {
        return ReadPayloadAs<T>(context, remoteTypeId, localTypeId, fieldName);
    }

    /// <summary>
    /// Reads remote scalar null framing and converts the remote scalar payload to the local field type.
    /// </summary>
    [MethodImpl(MethodImplOptions.NoInlining)]
    public static T ReadField<T>(
        ReadContext context,
        TypeId remoteTypeId,
        TypeId localTypeId,
        string fieldName,
        RefMode refMode)
    {
        switch (refMode)
        {
            case RefMode.None:
                return ReadPayloadAs<T>(context, remoteTypeId, localTypeId, fieldName);
            case RefMode.NullOnly:
                {
                    RefFlag flag = context.RefReader.ReadRefFlag(context.Reader);
                    return flag switch
                    {
                        RefFlag.Null => default!,
                        RefFlag.NotNullValue => ReadPayloadAs<T>(context, remoteTypeId, localTypeId, fieldName),
                        _ => throw Fail(
                            remoteTypeId,
                            localTypeId,
                            fieldName,
                            $"invalid compatible nullOnly ref flag {(sbyte)flag}"),
                    };
                }
            default:
                throw Fail(remoteTypeId, localTypeId, fieldName, $"unsupported compatible ref mode {refMode}");
        }
    }

    private static T ReadPayloadAs<T>(
        ReadContext context,
        TypeId remoteTypeId,
        TypeId localTypeId,
        string fieldName)
    {
        object value = ReadPayload(context, remoteTypeId, localTypeId, fieldName);
        return ConvertReadValue<T>(value, remoteTypeId, localTypeId, fieldName);
    }

    private static T ConvertReadValue<T>(
        object? value,
        TypeId remoteTypeId,
        TypeId localTypeId,
        string fieldName)
    {
        if (value is null)
        {
            return default!;
        }

        if (value is T typedValue)
        {
            return typedValue;
        }

        object converted = ConvertValue(value, remoteTypeId, localTypeId, typeof(T), fieldName);
        return (T)converted;
    }

    private static object ReadPayload(
        ReadContext context,
        TypeId remoteTypeId,
        TypeId localTypeId,
        string fieldName)
    {
        return remoteTypeId switch
        {
            TypeId.Bool => ReadBool(context, localTypeId, fieldName),
            TypeId.Int8 => context.Reader.ReadInt8(),
            TypeId.Int16 => context.Reader.ReadInt16(),
            TypeId.Int32 => context.Reader.ReadInt32(),
            TypeId.VarInt32 => context.Reader.ReadVarInt32(),
            TypeId.Int64 => context.Reader.ReadInt64(),
            TypeId.VarInt64 => context.Reader.ReadVarInt64(),
            TypeId.TaggedInt64 => context.Reader.ReadTaggedInt64(),
            TypeId.UInt8 => context.Reader.ReadUInt8(),
            TypeId.UInt16 => context.Reader.ReadUInt16(),
            TypeId.UInt32 => context.Reader.ReadUInt32(),
            TypeId.VarUInt32 => context.Reader.ReadVarUInt32(),
            TypeId.UInt64 => context.Reader.ReadUInt64(),
            TypeId.VarUInt64 => context.Reader.ReadVarUInt64(),
            TypeId.TaggedUInt64 => context.Reader.ReadTaggedUInt64(),
            TypeId.Float16 => BitConverter.UInt16BitsToHalf(context.Reader.ReadUInt16()),
            TypeId.BFloat16 => BFloat16.FromBits(context.Reader.ReadUInt16()),
            TypeId.Float32 => context.Reader.ReadFloat32(),
            TypeId.Float64 => context.Reader.ReadFloat64(),
            TypeId.Decimal => ReadDecimal(context),
            TypeId.String => StringSerializer.ReadString(context),
            _ => throw Fail(
                remoteTypeId,
                localTypeId,
                fieldName,
                $"unsupported compatible scalar type id {remoteTypeId}"),
        };
    }

    private static bool ReadBool(ReadContext context, TypeId localTypeId, string fieldName)
    {
        byte value = context.Reader.ReadUInt8();
        return value switch
        {
            0 => false,
            1 => true,
            _ => throw Fail(TypeId.Bool, localTypeId, fieldName, $"invalid bool payload {value}"),
        };
    }

    private static ForyDecimal ReadDecimal(ReadContext context)
    {
        (int scale, BigInteger unscaled) = DecimalCodec.Read(context.Reader);
        return new ForyDecimal(unscaled, scale);
    }

    private static object ConvertValue(
        object value,
        TypeId remoteTypeId,
        TypeId localTypeId,
        Type targetType,
        string fieldName)
    {
        TypeId remote = NormalizeScalarTypeId((uint)remoteTypeId);
        TypeId local = NormalizeScalarTypeId((uint)localTypeId);
        Type unwrappedTarget = Nullable.GetUnderlyingType(targetType) ?? targetType;
        if (local == TypeId.Bool)
        {
            return ToBool(value, remote, local, fieldName);
        }

        if (local == TypeId.String)
        {
            return ToStringValue(value, remote, local, fieldName);
        }

        if (IsNumeric(local))
        {
            return ToNumeric(value, remote, local, unwrappedTarget, fieldName);
        }

        throw Fail(remote, local, fieldName, "unsupported compatible scalar target");
    }

    private static bool ToBool(object value, TypeId remote, TypeId local, string fieldName)
    {
        if (remote == TypeId.String)
        {
            return value switch
            {
                "0" or "false" => false,
                "1" or "true" => true,
                string s => throw Fail(remote, local, fieldName, $"cannot convert string '{s}' to bool"),
                _ => throw Fail(remote, local, fieldName, "remote value is not a string"),
            };
        }

        DecimalValue numeric = ToDecimalValue(value, remote, local, fieldName);
        numeric = Normalize(numeric, remote, local, fieldName);
        if (numeric.Unscaled.IsZero)
        {
            return false;
        }

        if (numeric.Scale == 0 && numeric.Unscaled.IsOne)
        {
            return true;
        }

        throw Fail(remote, local, fieldName, "numeric value is not exactly 0 or 1");
    }

    private static string ToStringValue(object value, TypeId remote, TypeId local, string fieldName)
    {
        if (remote == TypeId.Bool)
        {
            return (bool)value ? "true" : "false";
        }

        if (!IsNumeric(remote))
        {
            throw Fail(remote, local, fieldName, "remote value is not numeric");
        }

        if (IsFloating(remote))
        {
            DecimalValue numeric = ToDecimalValue(value, remote, local, fieldName);
            if (numeric.Unscaled.IsZero)
            {
                return numeric.NegativeZero ? "-0.0" : "0.0";
            }

            return FormatFloating(Normalize(numeric, remote, local, fieldName));
        }

        if (remote == TypeId.Decimal)
        {
            return FormatDecimal(Normalize(ToDecimalValue(value, remote, local, fieldName), remote, local, fieldName));
        }

        return FormatInteger(ToInteger(value, remote, local, fieldName));
    }

    private static object ToNumeric(
        object value,
        TypeId remote,
        TypeId local,
        Type targetType,
        string fieldName)
    {
        if (remote == TypeId.Bool)
        {
            return FromInteger((bool)value ? BigInteger.One : BigInteger.Zero, remote, local, targetType, fieldName);
        }

        if (remote == TypeId.String)
        {
            if (!TryParseNumber((string)value, out DecimalValue parsed))
            {
                throw Fail(remote, local, fieldName, "string is not a compatible numeric literal");
            }

            return FromDecimal(parsed, local, targetType, remote, fieldName);
        }

        if (IsInteger(remote))
        {
            return FromInteger(ToInteger(value, remote, local, fieldName), remote, local, targetType, fieldName);
        }

        if (IsFloating(remote) && IsFloating(local) && TryNonFinite(value, out int sign))
        {
            if (sign == 0)
            {
                throw Fail(remote, local, fieldName, "NaN cannot convert across floating scalar types");
            }

            return NonFiniteFloat(sign, local, fieldName);
        }

        DecimalValue numeric = ToDecimalValue(value, remote, local, fieldName);
        return FromDecimal(numeric, local, targetType, remote, fieldName);
    }

    private static object FromInteger(
        BigInteger value,
        TypeId remote,
        TypeId local,
        Type targetType,
        string fieldName)
    {
        return local switch
        {
            TypeId.Int8 => CheckedSigned<sbyte>(value, SByteMin, SByteMax, remote, local, fieldName),
            TypeId.Int16 => CheckedSigned<short>(value, Int16Min, Int16Max, remote, local, fieldName),
            TypeId.Int32 => CheckedSigned<int>(value, Int32Min, Int32Max, remote, local, fieldName),
            TypeId.Int64 => CheckedSigned<long>(value, Int64Min, Int64Max, remote, local, fieldName),
            TypeId.UInt8 => CheckedUnsigned<byte>(value, ByteMax, remote, local, fieldName),
            TypeId.UInt16 => CheckedUnsigned<ushort>(value, UInt16Max, remote, local, fieldName),
            TypeId.UInt32 => CheckedUnsigned<uint>(value, UInt32Max, remote, local, fieldName),
            TypeId.UInt64 => CheckedUnsigned<ulong>(value, UInt64Max, remote, local, fieldName),
            TypeId.Float16 or TypeId.BFloat16 or TypeId.Float32 or TypeId.Float64 =>
                FromDecimal(new DecimalValue(value, 0, false), local, targetType, remote, fieldName),
            TypeId.Decimal => FromDecimal(new DecimalValue(value, 0, false), local, targetType, remote, fieldName),
            _ => throw Fail(remote, local, fieldName, "unsupported numeric target"),
        };
    }

    private static object FromDecimal(
        DecimalValue value,
        TypeId local,
        Type targetType,
        TypeId remote,
        string fieldName)
    {
        value = Normalize(value, remote, local, fieldName);
        return local switch
        {
            TypeId.Int8 => CheckedSigned<sbyte>(Integral(value, remote, local, fieldName), SByteMin, SByteMax, remote, local, fieldName),
            TypeId.Int16 => CheckedSigned<short>(Integral(value, remote, local, fieldName), Int16Min, Int16Max, remote, local, fieldName),
            TypeId.Int32 => CheckedSigned<int>(Integral(value, remote, local, fieldName), Int32Min, Int32Max, remote, local, fieldName),
            TypeId.Int64 => CheckedSigned<long>(Integral(value, remote, local, fieldName), Int64Min, Int64Max, remote, local, fieldName),
            TypeId.UInt8 => CheckedUnsigned<byte>(UnsignedIntegral(value, remote, local, fieldName), ByteMax, remote, local, fieldName),
            TypeId.UInt16 => CheckedUnsigned<ushort>(UnsignedIntegral(value, remote, local, fieldName), UInt16Max, remote, local, fieldName),
            TypeId.UInt32 => CheckedUnsigned<uint>(UnsignedIntegral(value, remote, local, fieldName), UInt32Max, remote, local, fieldName),
            TypeId.UInt64 => CheckedUnsigned<ulong>(UnsignedIntegral(value, remote, local, fieldName), UInt64Max, remote, local, fieldName),
            TypeId.Float16 => ToHalf(value, remote, local, fieldName),
            TypeId.BFloat16 => ToBFloat16(value, remote, local, fieldName),
            TypeId.Float32 => ToSingle(value, remote, local, fieldName),
            TypeId.Float64 => ToDouble(value, remote, local, fieldName),
            TypeId.Decimal => ToDecimalCarrier(value, targetType, remote, local, fieldName),
            _ => throw Fail(remote, local, fieldName, "unsupported numeric target"),
        };
    }

    private static object ToDecimalCarrier(
        DecimalValue value,
        Type targetType,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        value = Normalize(value, remote, local, fieldName);
        if (targetType == typeof(ForyDecimal))
        {
            return new ForyDecimal(value.Unscaled, value.Scale);
        }

        if (targetType == typeof(decimal))
        {
            return ToSystemDecimal(value, remote, local, fieldName);
        }

        throw Fail(remote, local, fieldName, $"unsupported decimal carrier {targetType}");
    }

    private static decimal ToSystemDecimal(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        value = Normalize(value, remote, local, fieldName);
        if (value.Scale is < 0 or > 28)
        {
            throw Fail(remote, local, fieldName, $"decimal scale {value.Scale} is outside System.Decimal range");
        }

        bool negative = value.Unscaled.Sign < 0;
        BigInteger magnitude = BigInteger.Abs(value.Unscaled);
        if ((magnitude >> 96) != BigInteger.Zero)
        {
            throw Fail(remote, local, fieldName, "decimal magnitude exceeds System.Decimal range");
        }

        int lo = unchecked((int)(uint)(magnitude & DecimalMask));
        int mid = unchecked((int)(uint)((magnitude >> 32) & DecimalMask));
        int hi = unchecked((int)(uint)((magnitude >> 64) & DecimalMask));
        return new decimal(lo, mid, hi, negative, (byte)value.Scale);
    }

    private static Half ToHalf(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        float parsed = ParseSingle(value, remote, local, fieldName);
        Half candidate = (Half)parsed;
        CheckFloatExact(FromHalf(candidate, remote, local, fieldName), value, remote, local, fieldName);
        return candidate;
    }

    private static BFloat16 ToBFloat16(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        float parsed = ParseSingle(value, remote, local, fieldName);
        BFloat16 candidate = BFloat16.FromSingle(parsed);
        CheckFloatExact(FromBFloat16(candidate, remote, local, fieldName), value, remote, local, fieldName);
        return candidate;
    }

    private static float ToSingle(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        float candidate = ParseSingle(value, remote, local, fieldName);
        CheckFloatExact(FromSingle(candidate, remote, local, fieldName), value, remote, local, fieldName);
        return candidate;
    }

    private static double ToDouble(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        double candidate = ParseDouble(value, remote, local, fieldName);
        CheckFloatExact(FromDouble(candidate, remote, local, fieldName), value, remote, local, fieldName);
        return candidate;
    }

    private static float ParseSingle(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (value.Unscaled.IsZero)
        {
            return value.NegativeZero ? -0.0f : 0.0f;
        }

        try
        {
            return float.Parse(FormatDecimal(value), NumberStyles.Float, CultureInfo.InvariantCulture);
        }
        catch (Exception ex) when (ex is FormatException or OverflowException)
        {
            throw Fail(remote, local, fieldName, "value is not representable as float32");
        }
    }

    private static double ParseDouble(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        if (value.Unscaled.IsZero)
        {
            return value.NegativeZero ? -0.0d : 0.0d;
        }

        try
        {
            return double.Parse(FormatDecimal(value), NumberStyles.Float, CultureInfo.InvariantCulture);
        }
        catch (Exception ex) when (ex is FormatException or OverflowException)
        {
            throw Fail(remote, local, fieldName, "value is not representable as float64");
        }
    }

    private static void CheckFloatExact(
        DecimalValue candidate,
        DecimalValue expected,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        candidate = Normalize(candidate, remote, local, fieldName);
        expected = Normalize(expected, remote, local, fieldName);
        if (candidate.Unscaled == expected.Unscaled &&
            candidate.Scale == expected.Scale &&
            (!expected.Unscaled.IsZero || candidate.NegativeZero == expected.NegativeZero))
        {
            return;
        }

        throw Fail(remote, local, fieldName, "numeric value is not exactly representable by target floating type");
    }

    private static BigInteger Integral(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        value = Normalize(value, remote, local, fieldName);
        if (value.Scale == 0)
        {
            return value.Unscaled;
        }

        BigInteger divisor = Pow10(value.Scale);
        BigInteger remainder;
        BigInteger quotient = BigInteger.DivRem(value.Unscaled, divisor, out remainder);
        if (remainder.IsZero)
        {
            return quotient;
        }

        throw Fail(remote, local, fieldName, "numeric value is not integral");
    }

    private static BigInteger UnsignedIntegral(DecimalValue value, TypeId remote, TypeId local, string fieldName)
    {
        BigInteger integer = Integral(value, remote, local, fieldName);
        if (integer.Sign >= 0)
        {
            return integer;
        }

        throw Fail(remote, local, fieldName, "negative value cannot convert to unsigned target");
    }

    private static object CheckedSigned<T>(
        BigInteger value,
        BigInteger min,
        BigInteger max,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        if (value < min || value > max)
        {
            throw Fail(remote, local, fieldName, $"integer value {value} is outside {local} range");
        }

        if (typeof(T) == typeof(sbyte))
        {
            return (sbyte)value;
        }

        if (typeof(T) == typeof(short))
        {
            return (short)value;
        }

        if (typeof(T) == typeof(int))
        {
            return (int)value;
        }

        return (long)value;
    }

    private static object CheckedUnsigned<T>(
        BigInteger value,
        BigInteger max,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        if (value.Sign < 0 || value > max)
        {
            throw Fail(remote, local, fieldName, $"integer value {value} is outside {local} range");
        }

        if (typeof(T) == typeof(byte))
        {
            return (byte)value;
        }

        if (typeof(T) == typeof(ushort))
        {
            return (ushort)value;
        }

        if (typeof(T) == typeof(uint))
        {
            return (uint)value;
        }

        return (ulong)value;
    }

    private static BigInteger ToInteger(object value, TypeId remote, TypeId local, string fieldName)
    {
        return value switch
        {
            sbyte v => v,
            short v => v,
            int v => v,
            long v => v,
            byte v => v,
            ushort v => v,
            uint v => v,
            ulong v => v,
            _ => throw Fail(remote, local, fieldName, $"remote value type {value.GetType()} is not an integer"),
        };
    }

    private static DecimalValue ToDecimalValue(object value, TypeId remote, TypeId local, string fieldName)
    {
        return value switch
        {
            sbyte v => new DecimalValue(v, 0, false),
            short v => new DecimalValue(v, 0, false),
            int v => new DecimalValue(v, 0, false),
            long v => new DecimalValue(v, 0, false),
            byte v => new DecimalValue(v, 0, false),
            ushort v => new DecimalValue(v, 0, false),
            uint v => new DecimalValue(v, 0, false),
            ulong v => new DecimalValue(v, 0, false),
            Half v => FromHalfChecked(v, remote, local, fieldName),
            BFloat16 v => FromBFloat16Checked(v, remote, local, fieldName),
            float v => FromSingleChecked(v, remote, local, fieldName),
            double v => FromDoubleChecked(v, remote, local, fieldName),
            ForyDecimal v => new DecimalValue(v.UnscaledValue, v.Scale, false),
            decimal v => FromSystemDecimal(v),
            _ => throw Fail(remote, local, fieldName, $"remote value type {value.GetType()} is not numeric"),
        };
    }

    private static bool TryNonFinite(object value, out int sign)
    {
        sign = 0;
        switch (value)
        {
            case Half v when Half.IsNaN(v):
                return true;
            case Half v when Half.IsInfinity(v):
                sign = Half.IsNegative(v) ? -1 : 1;
                return true;
            case BFloat16 v when v.IsNaN:
                return true;
            case BFloat16 v when v.IsInfinity:
                sign = v.SignBit ? -1 : 1;
                return true;
            case float v when float.IsNaN(v):
                return true;
            case float v when float.IsInfinity(v):
                sign = float.IsNegative(v) ? -1 : 1;
                return true;
            case double v when double.IsNaN(v):
                return true;
            case double v when double.IsInfinity(v):
                sign = double.IsNegative(v) ? -1 : 1;
                return true;
            default:
                return false;
        }
    }

    private static object NonFiniteFloat(int sign, TypeId local, string fieldName)
    {
        bool negative = sign < 0;
        return local switch
        {
            TypeId.Float16 => negative ? Half.NegativeInfinity : Half.PositiveInfinity,
            TypeId.BFloat16 => negative ? BFloat16.NegativeInfinity : BFloat16.PositiveInfinity,
            TypeId.Float32 => negative ? float.NegativeInfinity : float.PositiveInfinity,
            TypeId.Float64 => negative ? double.NegativeInfinity : double.PositiveInfinity,
            _ => throw Fail(local, local, fieldName, "non-finite value requires a floating target"),
        };
    }

    private static DecimalValue FromHalfChecked(Half value, TypeId remote, TypeId local, string fieldName)
    {
        if (!Half.IsFinite(value))
        {
            throw Fail(remote, local, fieldName, "non-finite float16 cannot convert to this target");
        }

        return FromHalf(value, remote, local, fieldName);
    }

    private static DecimalValue FromBFloat16Checked(BFloat16 value, TypeId remote, TypeId local, string fieldName)
    {
        if (!value.IsFinite)
        {
            throw Fail(remote, local, fieldName, "non-finite bfloat16 cannot convert to this target");
        }

        return FromBFloat16(value, remote, local, fieldName);
    }

    private static DecimalValue FromSingleChecked(float value, TypeId remote, TypeId local, string fieldName)
    {
        if (!float.IsFinite(value))
        {
            throw Fail(remote, local, fieldName, "non-finite float32 cannot convert to this target");
        }

        return FromSingle(value, remote, local, fieldName);
    }

    private static DecimalValue FromDoubleChecked(double value, TypeId remote, TypeId local, string fieldName)
    {
        if (!double.IsFinite(value))
        {
            throw Fail(remote, local, fieldName, "non-finite float64 cannot convert to this target");
        }

        return FromDouble(value, remote, local, fieldName);
    }

    private static DecimalValue FromSystemDecimal(decimal value)
    {
        int[] bits = decimal.GetBits(value);
        BigInteger unscaled =
            ((BigInteger)(uint)bits[2] << 64) |
            ((BigInteger)(uint)bits[1] << 32) |
            (uint)bits[0];
        if ((bits[3] & unchecked((int)0x8000_0000)) != 0)
        {
            unscaled = BigInteger.Negate(unscaled);
        }

        return new DecimalValue(unscaled, (bits[3] >> 16) & 0xFF, false);
    }

    private static DecimalValue FromHalf(Half value, TypeId remote, TypeId local, string fieldName)
    {
        ushort bits = BitConverter.HalfToUInt16Bits(value);
        return FromFloatBits(bits, 10, 5, 15, remote, local, fieldName);
    }

    private static DecimalValue FromBFloat16(BFloat16 value, TypeId remote, TypeId local, string fieldName)
    {
        ushort bits = value.ToBits();
        return FromFloatBits(bits, 7, 8, 127, remote, local, fieldName);
    }

    private static DecimalValue FromSingle(float value, TypeId remote, TypeId local, string fieldName)
    {
        uint bits = BitConverter.SingleToUInt32Bits(value);
        return FromFloatBits(bits, 23, 8, 127, remote, local, fieldName);
    }

    private static DecimalValue FromDouble(double value, TypeId remote, TypeId local, string fieldName)
    {
        ulong bits = BitConverter.DoubleToUInt64Bits(value);
        return FromFloatBits(bits, 52, 11, 1023, remote, local, fieldName);
    }

    private static DecimalValue FromFloatBits(
        ulong bits,
        int fractionBits,
        int exponentBits,
        int exponentBias,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        bool negative = (bits & (1UL << (fractionBits + exponentBits))) != 0;
        ulong exponentMask = (1UL << exponentBits) - 1UL;
        ulong exponent = (bits >> fractionBits) & exponentMask;
        ulong fractionMask = (1UL << fractionBits) - 1UL;
        ulong fraction = bits & fractionMask;
        if (exponent == 0 && fraction == 0)
        {
            return new DecimalValue(BigInteger.Zero, 0, negative);
        }

        BigInteger significand = exponent == 0
            ? fraction
            : (BigInteger.One << fractionBits) | fraction;
        int binaryExponent = (exponent == 0 ? 1 - exponentBias : (int)exponent - exponentBias) - fractionBits;
        if (binaryExponent >= 0)
        {
            BigInteger unscaled = significand << binaryExponent;
            return new DecimalValue(negative ? BigInteger.Negate(unscaled) : unscaled, 0, false);
        }

        int scale = checked(-binaryExponent);
        if (scale > MaxCompatibleDecimalDigits)
        {
            throw Fail(remote, local, fieldName, "float decimal expansion is too large");
        }
        BigInteger decimalSignificand = significand * BigInteger.Pow(5, scale);
        return Normalize(new DecimalValue(
            negative ? BigInteger.Negate(decimalSignificand) : decimalSignificand,
            scale,
            false), remote, local, fieldName);
    }

    private static bool TryParseNumber(string value, out DecimalValue result)
    {
        result = default;
        if (value.Length == 0 || value.Length > MaxCompatibleNumericTextLength)
        {
            return false;
        }

        int index = 0;
        bool negative = value[0] == '-';
        if (negative)
        {
            index++;
            if (index == value.Length)
            {
                return false;
            }
        }

        int intStart = index;
        int significantDigits = 0;
        bool seenNonZero = false;
        if (value[index] == '0')
        {
            CountSignificantDigit(value[index], ref seenNonZero, ref significantDigits);
            index++;
            if (index < value.Length && IsDigit(value[index]))
            {
                return false;
            }
        }
        else if (value[index] is >= '1' and <= '9')
        {
            while (index < value.Length && IsDigit(value[index]))
            {
                CountSignificantDigit(value[index], ref seenNonZero, ref significantDigits);
                index++;
            }
        }
        else
        {
            return false;
        }

        int intEnd = index;
        int fracStart = index;
        int fracEnd = index;
        if (index < value.Length && value[index] == '.')
        {
            index++;
            fracStart = index;
            while (index < value.Length && IsDigit(value[index]))
            {
                CountSignificantDigit(value[index], ref seenNonZero, ref significantDigits);
                index++;
            }

            if (index == fracStart)
            {
                return false;
            }

            fracEnd = index;
        }

        int exponent = 0;
        if (index < value.Length && (value[index] == 'e' || value[index] == 'E'))
        {
            index++;
            bool exponentNegative = index < value.Length && value[index] == '-';
            if (exponentNegative)
            {
                index++;
            }

            if (index == value.Length)
            {
                return false;
            }

            if (value[index] == '0')
            {
                index++;
                if (index < value.Length && IsDigit(value[index]))
                {
                    return false;
                }
            }
            else if (value[index] is >= '1' and <= '9')
            {
                while (index < value.Length && IsDigit(value[index]))
                {
                    exponent = exponent * 10 + value[index] - '0';
                    if (exponent > MaxCompatibleDecimalDigits)
                    {
                        return false;
                    }
                    index++;
                }
            }
            else
            {
                return false;
            }

            if (exponentNegative)
            {
                exponent = -exponent;
            }
        }

        if (index != value.Length)
        {
            return false;
        }

        if (significantDigits > MaxCompatibleDecimalDigits)
        {
            return false;
        }

        int scale = fracEnd - fracStart - exponent;
        if (!CompatibleDecimalShape(significantDigits, scale))
        {
            return false;
        }

        string digits = string.Concat(value.AsSpan(intStart, intEnd - intStart), value.AsSpan(fracStart, fracEnd - fracStart));
        BigInteger unscaled = BigInteger.Parse(digits, CultureInfo.InvariantCulture);
        if (negative)
        {
            unscaled = BigInteger.Negate(unscaled);
        }

        return TryNormalize(new DecimalValue(unscaled, scale, negative && unscaled.IsZero), out result);
    }

    private static void CountSignificantDigit(char digit, ref bool seenNonZero, ref int significantDigits)
    {
        if (digit != '0' || seenNonZero)
        {
            seenNonZero = true;
            significantDigits++;
        }
    }

    private static bool CompatibleDecimalShape(int significantDigits, int scale)
    {
        if (scale > MaxCompatibleDecimalDigits)
        {
            return false;
        }
        return scale >= 0 || significantDigits + (-scale) <= MaxCompatibleDecimalDigits;
    }

    private static bool IsDigit(char value)
    {
        return value is >= '0' and <= '9';
    }

    private static DecimalValue Normalize(
        DecimalValue value,
        TypeId remote,
        TypeId local,
        string fieldName)
    {
        if (TryNormalize(value, out DecimalValue normalized))
        {
            return normalized;
        }

        throw Fail(remote, local, fieldName, "converted decimal exceeds compatible conversion bounds");
    }

    private static bool TryNormalize(DecimalValue value, out DecimalValue normalized)
    {
        BigInteger unscaled = value.Unscaled;
        long scale = value.Scale;
        if (scale < 0)
        {
            long extraDigits = -scale;
            if (extraDigits > MaxCompatibleDecimalDigits ||
                DecimalDigitCount(unscaled) + extraDigits > MaxCompatibleDecimalDigits)
            {
                normalized = default;
                return false;
            }
            unscaled *= Pow10((int)extraDigits);
            scale = 0;
        }

        while (scale > 0 && !unscaled.IsZero)
        {
            BigInteger remainder;
            BigInteger quotient = BigInteger.DivRem(unscaled, 10, out remainder);
            if (!remainder.IsZero)
            {
                break;
            }

            unscaled = quotient;
            scale--;
        }

        if (unscaled.IsZero)
        {
            normalized = new DecimalValue(BigInteger.Zero, 0, value.NegativeZero);
            return true;
        }

        if (scale > MaxCompatibleDecimalDigits ||
            DecimalDigitCount(unscaled) > MaxCompatibleDecimalDigits)
        {
            normalized = default;
            return false;
        }

        normalized = new DecimalValue(unscaled, (int)scale, false);
        return true;
    }

    private static string FormatInteger(BigInteger value)
    {
        return value.ToString(CultureInfo.InvariantCulture);
    }

    private static string FormatDecimal(DecimalValue value)
    {
        if (value.Unscaled.IsZero)
        {
            return "0";
        }

        bool negative = value.Unscaled.Sign < 0;
        string digits = BigInteger.Abs(value.Unscaled).ToString(CultureInfo.InvariantCulture);
        if (value.Scale == 0)
        {
            return negative ? "-" + digits : digits;
        }

        string text;
        if (digits.Length > value.Scale)
        {
            int point = digits.Length - value.Scale;
            text = digits[..point] + "." + digits[point..];
        }
        else
        {
            text = "0." + new string('0', value.Scale - digits.Length) + digits;
        }

        return negative ? "-" + text : text;
    }

    private static string FormatFloating(DecimalValue value)
    {
        string text = FormatDecimal(value);
        return text.Contains('.', StringComparison.Ordinal) ? text : text + ".0";
    }

    private static BigInteger Pow10(int scale)
    {
        return BigInteger.Pow(10, scale);
    }

    private static int DecimalDigitCount(BigInteger value)
    {
        BigInteger magnitude = BigInteger.Abs(value);
        if (magnitude >= MaxCompatibleDecimalMagnitude)
        {
            return MaxCompatibleDecimalDigits + 1;
        }

        return magnitude.ToString(CultureInfo.InvariantCulture).Length;
    }

    private static TypeId NormalizeScalarTypeId(uint typeId)
    {
        return typeId switch
        {
            (uint)TypeId.Int32 or (uint)TypeId.VarInt32 => TypeId.Int32,
            (uint)TypeId.Int64 or (uint)TypeId.VarInt64 or (uint)TypeId.TaggedInt64 => TypeId.Int64,
            (uint)TypeId.UInt32 or (uint)TypeId.VarUInt32 => TypeId.UInt32,
            (uint)TypeId.UInt64 or (uint)TypeId.VarUInt64 or (uint)TypeId.TaggedUInt64 => TypeId.UInt64,
            _ => (TypeId)typeId,
        };
    }

    private static bool IsInteger(TypeId typeId)
    {
        return typeId is TypeId.Int8 or TypeId.Int16 or TypeId.Int32 or TypeId.Int64 or
            TypeId.UInt8 or TypeId.UInt16 or TypeId.UInt32 or TypeId.UInt64;
    }

    private static bool IsFloating(TypeId typeId)
    {
        return typeId is TypeId.Float16 or TypeId.BFloat16 or TypeId.Float32 or TypeId.Float64;
    }

    private static bool IsNumeric(TypeId typeId)
    {
        return IsInteger(typeId) || IsFloating(typeId) || typeId == TypeId.Decimal;
    }

    private static bool IsScalar(TypeId typeId)
    {
        return typeId == TypeId.Bool || typeId == TypeId.String || IsNumeric(typeId);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static InvalidDataException Fail(TypeId remote, TypeId local, string fieldName, string reason)
    {
        return new InvalidDataException(
            $"compatible scalar conversion failed for field '{fieldName}' from {remote} to {local}: {reason}");
    }

    private readonly record struct DecimalValue(BigInteger Unscaled, int Scale, bool NegativeZero);
}
