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

internal sealed class BoolArraySerializer : Serializer<bool[]>
{
    public override TypeId StaticTypeId => TypeId.BoolArray;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override bool[] DefaultValue => null!;

    public override bool IsNone(in bool[] value) => value is null;

    public override void WriteData(ref WriteContext context, in bool[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        bool[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)safe.Length);
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt8(safe[i] ? (byte)1 : (byte)0);
        }
    }

    public override bool[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        bool[] values = new bool[payloadSize];
        for (int i = 0; i < payloadSize; i++)
        {
            values[i] = context.Reader.ReadUInt8() != 0;
        }

        return values;
    }
}

internal sealed class Int8ArraySerializer : Serializer<sbyte[]>
{
    public override TypeId StaticTypeId => TypeId.Int8Array;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override sbyte[] DefaultValue => null!;

    public override bool IsNone(in sbyte[] value) => value is null;

    public override void WriteData(ref WriteContext context, in sbyte[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        sbyte[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)safe.Length);
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteInt8(safe[i]);
        }
    }

    public override sbyte[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        sbyte[] values = new sbyte[payloadSize];
        for (int i = 0; i < payloadSize; i++)
        {
            values[i] = context.Reader.ReadInt8();
        }

        return values;
    }
}

internal sealed class Int16ArraySerializer : Serializer<short[]>
{
    public override TypeId StaticTypeId => TypeId.Int16Array;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override short[] DefaultValue => null!;

    public override bool IsNone(in short[] value) => value is null;

    public override void WriteData(ref WriteContext context, in short[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        short[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 2));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteInt16(safe[i]);
        }
    }

    public override short[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 1) != 0)
        {
            throw new InvalidDataException("int16 array payload size mismatch");
        }

        short[] values = new short[payloadSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt16();
        }

        return values;
    }
}

internal sealed class Int32ArraySerializer : Serializer<int[]>
{
    public override TypeId StaticTypeId => TypeId.Int32Array;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override int[] DefaultValue => null!;

    public override bool IsNone(in int[] value) => value is null;

    public override void WriteData(ref WriteContext context, in int[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        int[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 4));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteInt32(safe[i]);
        }
    }

    public override int[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 3) != 0)
        {
            throw new InvalidDataException("int32 array payload size mismatch");
        }

        int[] values = new int[payloadSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt32();
        }

        return values;
    }
}

internal sealed class Int64ArraySerializer : Serializer<long[]>
{
    public override TypeId StaticTypeId => TypeId.Int64Array;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override long[] DefaultValue => null!;

    public override bool IsNone(in long[] value) => value is null;

    public override void WriteData(ref WriteContext context, in long[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        long[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 8));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteInt64(safe[i]);
        }
    }

    public override long[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 7) != 0)
        {
            throw new InvalidDataException("int64 array payload size mismatch");
        }

        long[] values = new long[payloadSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt64();
        }

        return values;
    }
}

internal sealed class UInt16ArraySerializer : Serializer<ushort[]>
{
    public override TypeId StaticTypeId => TypeId.UInt16Array;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override ushort[] DefaultValue => null!;

    public override bool IsNone(in ushort[] value) => value is null;

    public override void WriteData(ref WriteContext context, in ushort[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        ushort[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 2));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt16(safe[i]);
        }
    }

    public override ushort[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 1) != 0)
        {
            throw new InvalidDataException("uint16 array payload size mismatch");
        }

        ushort[] values = new ushort[payloadSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt16();
        }

        return values;
    }
}

internal sealed class UInt32ArraySerializer : Serializer<uint[]>
{
    public override TypeId StaticTypeId => TypeId.UInt32Array;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override uint[] DefaultValue => null!;

    public override bool IsNone(in uint[] value) => value is null;

    public override void WriteData(ref WriteContext context, in uint[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        uint[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 4));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt32(safe[i]);
        }
    }

    public override uint[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 3) != 0)
        {
            throw new InvalidDataException("uint32 array payload size mismatch");
        }

        uint[] values = new uint[payloadSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt32();
        }

        return values;
    }
}

internal sealed class UInt64ArraySerializer : Serializer<ulong[]>
{
    public override TypeId StaticTypeId => TypeId.UInt64Array;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override ulong[] DefaultValue => null!;

    public override bool IsNone(in ulong[] value) => value is null;

    public override void WriteData(ref WriteContext context, in ulong[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        ulong[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 8));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt64(safe[i]);
        }
    }

    public override ulong[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 7) != 0)
        {
            throw new InvalidDataException("uint64 array payload size mismatch");
        }

        ulong[] values = new ulong[payloadSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt64();
        }

        return values;
    }
}

internal sealed class Float32ArraySerializer : Serializer<float[]>
{
    public override TypeId StaticTypeId => TypeId.Float32Array;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override float[] DefaultValue => null!;

    public override bool IsNone(in float[] value) => value is null;

    public override void WriteData(ref WriteContext context, in float[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        float[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 4));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteFloat32(safe[i]);
        }
    }

    public override float[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 3) != 0)
        {
            throw new InvalidDataException("float32 array payload size mismatch");
        }

        float[] values = new float[payloadSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadFloat32();
        }

        return values;
    }
}

internal sealed class Float64ArraySerializer : Serializer<double[]>
{
    public override TypeId StaticTypeId => TypeId.Float64Array;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override double[] DefaultValue => null!;

    public override bool IsNone(in double[] value) => value is null;

    public override void WriteData(ref WriteContext context, in double[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        double[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 8));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteFloat64(safe[i]);
        }
    }

    public override double[] ReadData(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 7) != 0)
        {
            throw new InvalidDataException("float64 array payload size mismatch");
        }

        double[] values = new double[payloadSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadFloat64();
        }

        return values;
    }
}
