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

    public void Reserve(int additional)
    {
        EnsureCapacity(additional);
    }

    public void WriteUInt8(byte value)
    {
        EnsureCapacity(1);
        _storage[_count] = value;
        _count += 1;
    }

    public void WriteInt8(sbyte value)
    {
        WriteUInt8(unchecked((byte)value));
    }

    public void WriteUInt16(ushort value)
    {
        EnsureCapacity(2);
        BinaryPrimitives.WriteUInt16LittleEndian(_storage.AsSpan(_count, 2), value);
        _count += 2;
    }

    public void WriteInt16(short value)
    {
        WriteUInt16(unchecked((ushort)value));
    }

    public void WriteUInt32(uint value)
    {
        EnsureCapacity(4);
        BinaryPrimitives.WriteUInt32LittleEndian(_storage.AsSpan(_count, 4), value);
        _count += 4;
    }

    public void WriteInt32(int value)
    {
        WriteUInt32(unchecked((uint)value));
    }

    public void WriteUInt64(ulong value)
    {
        EnsureCapacity(8);
        BinaryPrimitives.WriteUInt64LittleEndian(_storage.AsSpan(_count, 8), value);
        _count += 8;
    }

    public void WriteInt64(long value)
    {
        WriteUInt64(unchecked((ulong)value));
    }

    public void WriteVarUInt32(uint value)
    {
        EnsureCapacity(5);
        uint remaining = value;
        while (remaining >= 0x80)
        {
            _storage[_count] = (byte)((remaining & 0x7F) | 0x80);
            _count += 1;
            remaining >>= 7;
        }

        _storage[_count] = (byte)remaining;
        _count += 1;
    }

    public void WriteVarUInt64(ulong value)
    {
        EnsureCapacity(10);
        ulong remaining = value;
        for (var i = 0; i < 8; i++)
        {
            if (remaining < 0x80)
            {
                _storage[_count] = (byte)remaining;
                _count += 1;
                return;
            }

            _storage[_count] = (byte)((remaining & 0x7F) | 0x80);
            _count += 1;
            remaining >>= 7;
        }

        _storage[_count] = (byte)(remaining & 0xFF);
        _count += 1;
    }

    public void WriteVarUInt36Small(ulong value)
    {
        if (value >= (1UL << 36))
        {
            throw new EncodingException("varuint36small overflow");
        }

        WriteVarUInt64(value);
    }

    public void WriteVarInt32(int value)
    {
        uint zigzag = unchecked((uint)((value << 1) ^ (value >> 31)));
        WriteVarUInt32(zigzag);
    }

    public void WriteVarInt64(long value)
    {
        ulong zigzag = unchecked((ulong)((value << 1) ^ (value >> 63)));
        WriteVarUInt64(zigzag);
    }

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

    public void WriteFloat32(float value)
    {
        WriteUInt32(unchecked((uint)BitConverter.SingleToInt32Bits(value)));
    }

    public void WriteFloat64(double value)
    {
        WriteUInt64(unchecked((ulong)BitConverter.DoubleToInt64Bits(value)));
    }

    public void WriteBytes(ReadOnlySpan<byte> bytes)
    {
        EnsureCapacity(bytes.Length);
        bytes.CopyTo(_storage.AsSpan(_count));
        _count += bytes.Length;
    }

    public void SetByte(int index, byte value)
    {
        if ((uint)index >= (uint)_count)
        {
            throw new ArgumentOutOfRangeException(nameof(index));
        }

        _storage[index] = value;
    }

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

    private void EnsureCapacity(int additional)
    {
        int required = _count + additional;
        if (required <= _storage.Length)
        {
            return;
        }

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
        uint result = 0;
        int shift = 0;
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
        ulong result = 0;
        int shift = 0;
        for (var i = 0; i < 8; i++)
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
