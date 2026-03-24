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

using System.Buffers.Binary;
using System.Runtime.CompilerServices;

namespace Apache.Fory;

public sealed class ByteWriter
{
    private byte[] _storage;
    private int _count;

    public ByteWriter(int capacity = 256)
    {
        _storage = new byte[Math.Max(1, capacity)];
        _count = 0;
    }

    public int Count => _count;

    public IReadOnlyList<byte> Storage => new ArraySegment<byte>(_storage, 0, _count);

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void Reserve(int additional)
    {
        EnsureCapacity(additional);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteUInt8(byte value)
    {
        EnsureCapacity(1);
        _storage[_count] = value;
        _count += 1;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteInt8(sbyte value)
    {
        WriteUInt8(unchecked((byte)value));
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteUInt16(ushort value)
    {
        EnsureCapacity(2);
        int index = _count;
        if (BitConverter.IsLittleEndian)
        {
            Unsafe.WriteUnaligned(ref _storage[index], value);
        }
        else
        {
            BinaryPrimitives.WriteUInt16LittleEndian(_storage.AsSpan(index, 2), value);
        }

        _count = index + 2;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteInt16(short value)
    {
        WriteUInt16(unchecked((ushort)value));
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteUInt32(uint value)
    {
        EnsureCapacity(4);
        int index = _count;
        if (BitConverter.IsLittleEndian)
        {
            Unsafe.WriteUnaligned(ref _storage[index], value);
        }
        else
        {
            BinaryPrimitives.WriteUInt32LittleEndian(_storage.AsSpan(index, 4), value);
        }

        _count = index + 4;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteInt32(int value)
    {
        WriteUInt32(unchecked((uint)value));
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteUInt64(ulong value)
    {
        EnsureCapacity(8);
        int index = _count;
        if (BitConverter.IsLittleEndian)
        {
            Unsafe.WriteUnaligned(ref _storage[index], value);
        }
        else
        {
            BinaryPrimitives.WriteUInt64LittleEndian(_storage.AsSpan(index, 8), value);
        }

        _count = index + 8;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteInt64(long value)
    {
        WriteUInt64(unchecked((ulong)value));
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteVarUInt32(uint value)
    {
        EnsureCapacity(5);
        int index = _count;
        if (value < (1u << 7))
        {
            _storage[index] = unchecked((byte)value);
            _count = index + 1;
            return;
        }

        if (value < (1u << 14))
        {
            _storage[index] = unchecked((byte)((value & 0x7Fu) | 0x80u));
            _storage[index + 1] = unchecked((byte)(value >> 7));
            _count = index + 2;
            return;
        }

        if (value < (1u << 21))
        {
            _storage[index] = unchecked((byte)((value & 0x7Fu) | 0x80u));
            _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7Fu) | 0x80u));
            _storage[index + 2] = unchecked((byte)(value >> 14));
            _count = index + 3;
            return;
        }

        if (value < (1u << 28))
        {
            _storage[index] = unchecked((byte)((value & 0x7Fu) | 0x80u));
            _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7Fu) | 0x80u));
            _storage[index + 2] = unchecked((byte)(((value >> 14) & 0x7Fu) | 0x80u));
            _storage[index + 3] = unchecked((byte)(value >> 21));
            _count = index + 4;
            return;
        }

        _storage[index] = unchecked((byte)((value & 0x7Fu) | 0x80u));
        _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7Fu) | 0x80u));
        _storage[index + 2] = unchecked((byte)(((value >> 14) & 0x7Fu) | 0x80u));
        _storage[index + 3] = unchecked((byte)(((value >> 21) & 0x7Fu) | 0x80u));
        _storage[index + 4] = unchecked((byte)(value >> 28));
        _count = index + 5;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteVarUInt64(ulong value)
    {
        EnsureCapacity(9);
        int index = _count;
        if (value < (1UL << 7))
        {
            _storage[index] = unchecked((byte)value);
            _count = index + 1;
            return;
        }

        if (value < (1UL << 14))
        {
            _storage[index] = unchecked((byte)((value & 0x7FuL) | 0x80uL));
            _storage[index + 1] = unchecked((byte)(value >> 7));
            _count = index + 2;
            return;
        }

        if (value < (1UL << 21))
        {
            _storage[index] = unchecked((byte)((value & 0x7FuL) | 0x80uL));
            _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7FuL) | 0x80uL));
            _storage[index + 2] = unchecked((byte)(value >> 14));
            _count = index + 3;
            return;
        }

        if (value < (1UL << 28))
        {
            _storage[index] = unchecked((byte)((value & 0x7FuL) | 0x80uL));
            _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7FuL) | 0x80uL));
            _storage[index + 2] = unchecked((byte)(((value >> 14) & 0x7FuL) | 0x80uL));
            _storage[index + 3] = unchecked((byte)(value >> 21));
            _count = index + 4;
            return;
        }

        if (value < (1UL << 35))
        {
            _storage[index] = unchecked((byte)((value & 0x7FuL) | 0x80uL));
            _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7FuL) | 0x80uL));
            _storage[index + 2] = unchecked((byte)(((value >> 14) & 0x7FuL) | 0x80uL));
            _storage[index + 3] = unchecked((byte)(((value >> 21) & 0x7FuL) | 0x80uL));
            _storage[index + 4] = unchecked((byte)(value >> 28));
            _count = index + 5;
            return;
        }

        if (value < (1UL << 42))
        {
            _storage[index] = unchecked((byte)((value & 0x7FuL) | 0x80uL));
            _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7FuL) | 0x80uL));
            _storage[index + 2] = unchecked((byte)(((value >> 14) & 0x7FuL) | 0x80uL));
            _storage[index + 3] = unchecked((byte)(((value >> 21) & 0x7FuL) | 0x80uL));
            _storage[index + 4] = unchecked((byte)(((value >> 28) & 0x7FuL) | 0x80uL));
            _storage[index + 5] = unchecked((byte)(value >> 35));
            _count = index + 6;
            return;
        }

        if (value < (1UL << 49))
        {
            _storage[index] = unchecked((byte)((value & 0x7FuL) | 0x80uL));
            _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7FuL) | 0x80uL));
            _storage[index + 2] = unchecked((byte)(((value >> 14) & 0x7FuL) | 0x80uL));
            _storage[index + 3] = unchecked((byte)(((value >> 21) & 0x7FuL) | 0x80uL));
            _storage[index + 4] = unchecked((byte)(((value >> 28) & 0x7FuL) | 0x80uL));
            _storage[index + 5] = unchecked((byte)(((value >> 35) & 0x7FuL) | 0x80uL));
            _storage[index + 6] = unchecked((byte)(value >> 42));
            _count = index + 7;
            return;
        }

        if (value < (1UL << 56))
        {
            _storage[index] = unchecked((byte)((value & 0x7FuL) | 0x80uL));
            _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7FuL) | 0x80uL));
            _storage[index + 2] = unchecked((byte)(((value >> 14) & 0x7FuL) | 0x80uL));
            _storage[index + 3] = unchecked((byte)(((value >> 21) & 0x7FuL) | 0x80uL));
            _storage[index + 4] = unchecked((byte)(((value >> 28) & 0x7FuL) | 0x80uL));
            _storage[index + 5] = unchecked((byte)(((value >> 35) & 0x7FuL) | 0x80uL));
            _storage[index + 6] = unchecked((byte)(((value >> 42) & 0x7FuL) | 0x80uL));
            _storage[index + 7] = unchecked((byte)(value >> 49));
            _count = index + 8;
            return;
        }

        _storage[index] = unchecked((byte)((value & 0x7FuL) | 0x80uL));
        _storage[index + 1] = unchecked((byte)(((value >> 7) & 0x7FuL) | 0x80uL));
        _storage[index + 2] = unchecked((byte)(((value >> 14) & 0x7FuL) | 0x80uL));
        _storage[index + 3] = unchecked((byte)(((value >> 21) & 0x7FuL) | 0x80uL));
        _storage[index + 4] = unchecked((byte)(((value >> 28) & 0x7FuL) | 0x80uL));
        _storage[index + 5] = unchecked((byte)(((value >> 35) & 0x7FuL) | 0x80uL));
        _storage[index + 6] = unchecked((byte)(((value >> 42) & 0x7FuL) | 0x80uL));
        _storage[index + 7] = unchecked((byte)(((value >> 49) & 0x7FuL) | 0x80uL));
        _storage[index + 8] = unchecked((byte)(value >> 56));
        _count = index + 9;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteVarUInt36Small(ulong value)
    {
        if (value >= (1UL << 36))
        {
            throw new EncodingException("varuint36small overflow");
        }

        WriteVarUInt64(value);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteVarInt32(int value)
    {
        uint zigzag = unchecked((uint)((value << 1) ^ (value >> 31)));
        WriteVarUInt32(zigzag);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteVarInt64(long value)
    {
        ulong zigzag = unchecked((ulong)((value << 1) ^ (value >> 63)));
        WriteVarUInt64(zigzag);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteTaggedInt64(long value)
    {
        if (value >= -1_073_741_824L && value <= 1_073_741_823L)
        {
            WriteInt32(unchecked((int)value << 1));
            return;
        }

        WriteUInt8(0x01);
        WriteInt64(value);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteTaggedUInt64(ulong value)
    {
        if (value <= int.MaxValue)
        {
            WriteUInt32(unchecked((uint)value << 1));
            return;
        }

        WriteUInt8(0x01);
        WriteUInt64(value);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteFloat32(float value)
    {
        WriteUInt32(unchecked((uint)BitConverter.SingleToInt32Bits(value)));
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteFloat64(double value)
    {
        WriteUInt64(unchecked((ulong)BitConverter.DoubleToInt64Bits(value)));
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void WriteBytes(ReadOnlySpan<byte> bytes)
    {
        EnsureCapacity(bytes.Length);
        bytes.CopyTo(_storage.AsSpan(_count));
        _count += bytes.Length;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public Span<byte> GetSpan(int size)
    {
        if (size < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(size));
        }

        EnsureCapacity(size);
        return _storage.AsSpan(_count, size);
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void Advance(int count)
    {
        if (count < 0 || _count + count > _storage.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(count));
        }

        _count += count;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void SetByte(int index, byte value)
    {
        if ((uint)index >= (uint)_count)
        {
            throw new ArgumentOutOfRangeException(nameof(index));
        }

        _storage[index] = value;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public void SetBytes(int index, ReadOnlySpan<byte> bytes)
    {
        if (index < 0 || index + bytes.Length > _count)
        {
            throw new ArgumentOutOfRangeException(nameof(index));
        }

        bytes.CopyTo(_storage.AsSpan(index));
    }

    public byte[] ToArray()
    {
        byte[] result = new byte[_count];
        Array.Copy(_storage, 0, result, 0, _count);
        return result;
    }

    public void Reset()
    {
        _count = 0;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private void EnsureCapacity(int additional)
    {
        if (additional <= 0)
        {
            return;
        }

        int required = _count + additional;
        if (required <= _storage.Length)
        {
            return;
        }

        Grow(required);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private void Grow(int required)
    {
        int next = _storage.Length * 2;
        if (next < required)
        {
            next = required;
        }

        Array.Resize(ref _storage, next);
    }
}

public sealed class ByteReader
{
    private byte[] _storage;
    private int _length;
    private int _cursor;

    public ByteReader(ReadOnlySpan<byte> data)
    {
        _storage = data.ToArray();
        _length = _storage.Length;
        _cursor = 0;
    }

    public ByteReader(byte[] bytes)
    {
        _storage = bytes;
        _length = bytes.Length;
        _cursor = 0;
    }

    public byte[] Storage => _storage;

    public int Cursor => _cursor;

    public int Remaining => _length - _cursor;

    public void Reset(ReadOnlySpan<byte> data)
    {
        _storage = data.ToArray();
        _length = _storage.Length;
        _cursor = 0;
    }

    public void Reset(byte[] bytes)
    {
        _storage = bytes;
        _length = bytes.Length;
        _cursor = 0;
    }

    public void SetCursor(int value)
    {
        _cursor = value;
    }

    public void MoveBack(int amount)
    {
        _cursor -= amount;
    }

    public void CheckBound(int need)
    {
        if (_cursor + need > _length)
        {
            throw new OutOfBoundsException(_cursor, need, _length);
        }
    }

    public byte ReadUInt8()
    {
        CheckBound(1);
        byte value = _storage[_cursor];
        _cursor += 1;
        return value;
    }

    public sbyte ReadInt8()
    {
        return unchecked((sbyte)ReadUInt8());
    }

    public ushort ReadUInt16()
    {
        CheckBound(2);
        ushort value = BinaryPrimitives.ReadUInt16LittleEndian(_storage.AsSpan(_cursor, 2));
        _cursor += 2;
        return value;
    }

    public short ReadInt16()
    {
        return unchecked((short)ReadUInt16());
    }

    public uint ReadUInt32()
    {
        CheckBound(4);
        uint value = BinaryPrimitives.ReadUInt32LittleEndian(_storage.AsSpan(_cursor, 4));
        _cursor += 4;
        return value;
    }

    public int ReadInt32()
    {
        return unchecked((int)ReadUInt32());
    }

    public ulong ReadUInt64()
    {
        CheckBound(8);
        ulong value = BinaryPrimitives.ReadUInt64LittleEndian(_storage.AsSpan(_cursor, 8));
        _cursor += 8;
        return value;
    }

    public long ReadInt64()
    {
        return unchecked((long)ReadUInt64());
    }

    public uint ReadVarUInt32()
    {
        byte[] storage = _storage;
        int cursor = _cursor;
        int length = _length;
        if (cursor >= length)
        {
            throw new OutOfBoundsException(cursor, 1, length);
        }

        byte first = storage[cursor];
        if ((first & 0x80) == 0)
        {
            _cursor = cursor + 1;
            return first;
        }

        cursor += 1;
        uint result = (uint)(first & 0x7F);
        int shift = 7;
        while (true)
        {
            if (cursor >= length)
            {
                throw new OutOfBoundsException(cursor, 1, length);
            }

            byte b = storage[cursor];
            cursor += 1;
            result |= (uint)(b & 0x7F) << shift;
            if ((b & 0x80) == 0)
            {
                _cursor = cursor;
                return result;
            }

            shift += 7;
            if (shift > 28)
            {
                throw new EncodingException("varuint32 overflow");
            }
        }
    }

    public ulong ReadVarUInt64()
    {
        byte[] storage = _storage;
        int cursor = _cursor;
        int length = _length;
        if (cursor >= length)
        {
            throw new OutOfBoundsException(cursor, 1, length);
        }

        byte first = storage[cursor];
        if ((first & 0x80) == 0)
        {
            _cursor = cursor + 1;
            return first;
        }

        cursor += 1;
        ulong result = (ulong)(first & 0x7F);
        int shift = 7;
        for (var i = 1; i < 8; i++)
        {
            if (cursor >= length)
            {
                throw new OutOfBoundsException(cursor, 1, length);
            }

            byte b = storage[cursor];
            cursor += 1;
            result |= (ulong)(b & 0x7F) << shift;
            if ((b & 0x80) == 0)
            {
                _cursor = cursor;
                return result;
            }

            shift += 7;
        }

        if (cursor >= length)
        {
            throw new OutOfBoundsException(cursor, 1, length);
        }

        byte last = storage[cursor];
        cursor += 1;
        result |= (ulong)last << 56;
        _cursor = cursor;
        return result;
    }

    public ulong ReadVarUInt36Small()
    {
        ulong value = ReadVarUInt64();
        if (value >= (1UL << 36))
        {
            throw new EncodingException("varuint36small overflow");
        }

        return value;
    }

    public int ReadVarInt32()
    {
        uint encoded = ReadVarUInt32();
        return unchecked((int)((encoded >> 1) ^ (~(encoded & 1) + 1)));
    }

    public long ReadVarInt64()
    {
        ulong encoded = ReadVarUInt64();
        return unchecked((long)((encoded >> 1) ^ (~(encoded & 1UL) + 1UL)));
    }

    public long ReadTaggedInt64()
    {
        int first = ReadInt32();
        if ((first & 1) == 0)
        {
            return first >> 1;
        }

        MoveBack(3);
        return ReadInt64();
    }

    public ulong ReadTaggedUInt64()
    {
        uint first = ReadUInt32();
        if ((first & 1) == 0)
        {
            return first >> 1;
        }

        MoveBack(3);
        return ReadUInt64();
    }

    public float ReadFloat32()
    {
        return BitConverter.Int32BitsToSingle(unchecked((int)ReadUInt32()));
    }

    public double ReadFloat64()
    {
        return BitConverter.Int64BitsToDouble(unchecked((long)ReadUInt64()));
    }

    public byte[] ReadBytes(int count)
    {
        CheckBound(count);
        byte[] result = new byte[count];
        Array.Copy(_storage, _cursor, result, 0, count);
        _cursor += count;
        return result;
    }

    public ReadOnlySpan<byte> ReadSpan(int count)
    {
        CheckBound(count);
        ReadOnlySpan<byte> span = _storage.AsSpan(_cursor, count);
        _cursor += count;
        return span;
    }

    public void Skip(int count)
    {
        CheckBound(count);
        _cursor += count;
    }
}
