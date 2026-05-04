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

namespace Apache.Fory;

/// <summary>
/// Represents one IEEE 754 bfloat16 value.
/// </summary>
/// <remarks>
/// <para>
/// <see cref="BFloat16" /> is the public Fory carrier for xlang <c>BFLOAT16</c> values. It stores
/// the canonical 16-bit wire representation and converts to and from <see cref="float" /> using
/// round-to-nearest-even semantics.
/// </para>
/// <para>
/// Use <see cref="FromBits" /> and <see cref="ToBits" /> when you need exact bit-preserving
/// control. Use <see cref="FromSingle" /> or the explicit conversion operators when you want
/// numeric conversion.
/// </para>
/// <para>
/// For <c>array&lt;bfloat16&gt;</c> schema, use a dense <c>BFloat16[]</c> carrier or declare
/// <c>S.Array&lt;S.BFloat16&gt;</c> in schema metadata. General <c>List&lt;BFloat16&gt;</c> fields keep
/// list schema.
/// </para>
/// </remarks>
public readonly struct BFloat16 : IEquatable<BFloat16>, IComparable<BFloat16>
{
    private const uint Float32ExpMask = 0x7F80_0000u;
    private const uint Float32MantissaMask = 0x007F_FFFFu;
    private const ushort CanonicalNaNBits = 0x7FC0;

    /// <summary>
    /// Gets the canonical quiet NaN value.
    /// </summary>
    public static BFloat16 NaN => FromBits(CanonicalNaNBits);

    /// <summary>
    /// Gets positive infinity.
    /// </summary>
    public static BFloat16 PositiveInfinity => FromBits(0x7F80);

    /// <summary>
    /// Gets negative infinity.
    /// </summary>
    public static BFloat16 NegativeInfinity => FromBits(0xFF80);

    /// <summary>
    /// Gets positive zero.
    /// </summary>
    public static BFloat16 Zero => default;

    /// <summary>
    /// Gets negative zero.
    /// </summary>
    public static BFloat16 NegativeZero => FromBits(0x8000);

    /// <summary>
    /// Gets the raw IEEE 754 bfloat16 bits.
    /// </summary>
    public ushort Bits { get; }

    /// <summary>
    /// Initializes a new instance from the exact wire bits.
    /// </summary>
    /// <param name="bits">Raw bfloat16 bits.</param>
    public BFloat16(ushort bits)
    {
        Bits = bits;
    }

    /// <summary>
    /// Creates a value from exact wire bits.
    /// </summary>
    public static BFloat16 FromBits(ushort bits) => new(bits);

    /// <summary>
    /// Creates a value from a <see cref="float" />.
    /// </summary>
    public static BFloat16 FromSingle(float value) => new(Float32ToBFloat16Bits(value));

    /// <summary>
    /// Returns the exact wire bits.
    /// </summary>
    public ushort ToBits() => Bits;

    /// <summary>
    /// Converts this value to <see cref="float" />.
    /// </summary>
    public float ToSingle() => BFloat16BitsToFloat32(Bits);

    /// <summary>
    /// Converts this value to <see cref="double" />.
    /// </summary>
    public double ToDouble() => ToSingle();

    /// <summary>
    /// Returns whether the value is NaN.
    /// </summary>
    public bool IsNaN => (Bits & 0x7F80) == 0x7F80 && (Bits & 0x007F) != 0;

    /// <summary>
    /// Returns whether the value is infinite.
    /// </summary>
    public bool IsInfinity => (Bits & 0x7F80) == 0x7F80 && (Bits & 0x007F) == 0;

    /// <summary>
    /// Returns whether the value is finite.
    /// </summary>
    public bool IsFinite => (Bits & 0x7F80) != 0x7F80;

    /// <summary>
    /// Returns whether the value is numerically zero, including signed zero.
    /// </summary>
    public bool IsZero => (Bits & 0x7FFF) == 0;

    /// <summary>
    /// Returns whether the sign bit is set.
    /// </summary>
    public bool SignBit => (Bits & 0x8000) != 0;

    /// <summary>
    /// Reinterprets a <see cref="float" /> as bfloat16 bits using round-to-nearest-even.
    /// </summary>
    public static ushort ToBits(float value) => Float32ToBFloat16Bits(value);

    /// <summary>
    /// Reinterprets bfloat16 bits as a <see cref="float" />.
    /// </summary>
    public static float ToSingle(ushort bits) => BFloat16BitsToFloat32(bits);

    /// <summary>
    /// Converts from <see cref="float" /> to <see cref="BFloat16" />.
    /// </summary>
    public static explicit operator BFloat16(float value) => FromSingle(value);

    /// <summary>
    /// Converts from <see cref="double" /> to <see cref="BFloat16" />.
    /// </summary>
    public static explicit operator BFloat16(double value) => FromSingle((float)value);

    /// <summary>
    /// Converts to <see cref="float" />.
    /// </summary>
    public static implicit operator float(BFloat16 value) => value.ToSingle();

    /// <summary>
    /// Converts to <see cref="double" />.
    /// </summary>
    public static implicit operator double(BFloat16 value) => value.ToDouble();

    public int CompareTo(BFloat16 other)
    {
        return ToSingle().CompareTo(other.ToSingle());
    }

    public bool Equals(BFloat16 other)
    {
        return Bits == other.Bits;
    }

    public override bool Equals(object? obj)
    {
        return obj is BFloat16 other && Equals(other);
    }

    public override int GetHashCode()
    {
        return Bits.GetHashCode();
    }

    public override string ToString()
    {
        return ToSingle().ToString(System.Globalization.CultureInfo.InvariantCulture);
    }

    public static bool operator ==(BFloat16 left, BFloat16 right) => left.Equals(right);

    public static bool operator !=(BFloat16 left, BFloat16 right) => !left.Equals(right);

    public static bool operator <(BFloat16 left, BFloat16 right) => left.CompareTo(right) < 0;

    public static bool operator <=(BFloat16 left, BFloat16 right) => left.CompareTo(right) <= 0;

    public static bool operator >(BFloat16 left, BFloat16 right) => left.CompareTo(right) > 0;

    public static bool operator >=(BFloat16 left, BFloat16 right) => left.CompareTo(right) >= 0;

    internal static ushort Float32ToBFloat16Bits(float value)
    {
        uint bits32 = BitConverter.SingleToUInt32Bits(value);
        if ((bits32 & Float32ExpMask) == Float32ExpMask && (bits32 & Float32MantissaMask) != 0)
        {
            return CanonicalNaNBits;
        }

        uint lsb = (bits32 >> 16) & 1u;
        uint rounded = bits32 + 0x7FFFu + lsb;
        return unchecked((ushort)(rounded >> 16));
    }

    internal static float BFloat16BitsToFloat32(ushort bits)
    {
        return BitConverter.UInt32BitsToSingle((uint)bits << 16);
    }
}
