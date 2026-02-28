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

internal enum ForyStringEncoding : ulong
{
    Latin1 = 0,
    Utf16 = 1,
    Utf8 = 2,
}

public sealed class BoolSerializer : Serializer<bool>
{

    public override bool DefaultValue => false;

    public override void WriteData(WriteContext context, in bool value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt8(value ? (byte)1 : (byte)0);
    }

    public override bool ReadData(ReadContext context)
    {
        return context.Reader.ReadUInt8() != 0;
    }
}

public sealed class Int8Serializer : Serializer<sbyte>
{

    public override sbyte DefaultValue => 0;

    public override void WriteData(WriteContext context, in sbyte value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt8(value);
    }

    public override sbyte ReadData(ReadContext context)
    {
        return context.Reader.ReadInt8();
    }
}

public sealed class Int16Serializer : Serializer<short>
{

    public override short DefaultValue => 0;

    public override void WriteData(WriteContext context, in short value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt16(value);
    }

    public override short ReadData(ReadContext context)
    {
        return context.Reader.ReadInt16();
    }
}

public sealed class Int32Serializer : Serializer<int>
{

    public override int DefaultValue => 0;

    public override void WriteData(WriteContext context, in int value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarInt32(value);
    }

    public override int ReadData(ReadContext context)
    {
        return context.Reader.ReadVarInt32();
    }
}

public sealed class Int64Serializer : Serializer<long>
{

    public override long DefaultValue => 0;

    public override void WriteData(WriteContext context, in long value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarInt64(value);
    }

    public override long ReadData(ReadContext context)
    {
        return context.Reader.ReadVarInt64();
    }
}

public sealed class UInt8Serializer : Serializer<byte>
{

    public override byte DefaultValue => 0;

    public override void WriteData(WriteContext context, in byte value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt8(value);
    }

    public override byte ReadData(ReadContext context)
    {
        return context.Reader.ReadUInt8();
    }
}

public sealed class UInt16Serializer : Serializer<ushort>
{

    public override ushort DefaultValue => 0;

    public override void WriteData(WriteContext context, in ushort value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt16(value);
    }

    public override ushort ReadData(ReadContext context)
    {
        return context.Reader.ReadUInt16();
    }
}

public sealed class UInt32Serializer : Serializer<uint>
{

    public override uint DefaultValue => 0;

    public override void WriteData(WriteContext context, in uint value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarUInt32(value);
    }

    public override uint ReadData(ReadContext context)
    {
        return context.Reader.ReadVarUInt32();
    }
}

public sealed class UInt64Serializer : Serializer<ulong>
{

    public override ulong DefaultValue => 0;

    public override void WriteData(WriteContext context, in ulong value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarUInt64(value);
    }

    public override ulong ReadData(ReadContext context)
    {
        return context.Reader.ReadVarUInt64();
    }
}

public sealed class Float32Serializer : Serializer<float>
{

    public override float DefaultValue => 0;

    public override void WriteData(WriteContext context, in float value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteFloat32(value);
    }

    public override float ReadData(ReadContext context)
    {
        return context.Reader.ReadFloat32();
    }
}

public sealed class Float64Serializer : Serializer<double>
{

    public override double DefaultValue => 0;

    public override void WriteData(WriteContext context, in double value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteFloat64(value);
    }

    public override double ReadData(ReadContext context)
    {
        return context.Reader.ReadFloat64();
    }
}

public sealed class BinarySerializer : Serializer<byte[]>
{


    public override byte[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in byte[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        byte[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)safe.Length);
        context.Writer.WriteBytes(safe);
    }

    public override byte[] ReadData(ReadContext context)
    {
        uint length = context.Reader.ReadVarUInt32();
        return context.Reader.ReadBytes(checked((int)length));
    }
}
