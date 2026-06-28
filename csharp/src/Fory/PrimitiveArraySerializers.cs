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



    public override bool[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in bool[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        bool[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)safe.Length);
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt8(safe[i] ? (byte)1 : (byte)0);
        }
    }

    public override bool[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        context.Reader.CheckBound(byteSize);
        bool[] values = new bool[byteSize];
        for (int i = 0; i < byteSize; i++)
        {
            values[i] = context.Reader.ReadUInt8() != 0;
        }

        return values;
    }
}

internal sealed class Int8ArraySerializer : Serializer<sbyte[]>
{



    public override sbyte[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in sbyte[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        sbyte[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)safe.Length);
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteInt8(safe[i]);
        }
    }

    public override sbyte[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        context.Reader.CheckBound(byteSize);
        sbyte[] values = new sbyte[byteSize];
        for (int i = 0; i < byteSize; i++)
        {
            values[i] = context.Reader.ReadInt8();
        }

        return values;
    }
}

internal sealed class Int16ArraySerializer : Serializer<short[]>
{



    public override short[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in short[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        short[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 2));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteInt16(safe[i]);
        }
    }

    public override short[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 1) != 0)
        {
            throw new InvalidDataException("int16 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        short[] values = new short[byteSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt16();
        }

        return values;
    }
}

internal sealed class Int32ArraySerializer : Serializer<int[]>
{



    public override int[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in int[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        int[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 4));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteInt32(safe[i]);
        }
    }

    public override int[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 3) != 0)
        {
            throw new InvalidDataException("int32 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        int[] values = new int[byteSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt32();
        }

        return values;
    }
}

internal sealed class Int64ArraySerializer : Serializer<long[]>
{



    public override long[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in long[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        long[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 8));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteInt64(safe[i]);
        }
    }

    public override long[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 7) != 0)
        {
            throw new InvalidDataException("int64 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        long[] values = new long[byteSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt64();
        }

        return values;
    }
}

internal sealed class UInt16ArraySerializer : Serializer<ushort[]>
{



    public override ushort[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in ushort[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        ushort[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 2));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt16(safe[i]);
        }
    }

    public override ushort[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 1) != 0)
        {
            throw new InvalidDataException("uint16 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        ushort[] values = new ushort[byteSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt16();
        }

        return values;
    }
}

internal sealed class UInt32ArraySerializer : Serializer<uint[]>
{



    public override uint[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in uint[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        uint[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 4));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt32(safe[i]);
        }
    }

    public override uint[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 3) != 0)
        {
            throw new InvalidDataException("uint32 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        uint[] values = new uint[byteSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt32();
        }

        return values;
    }
}

internal sealed class UInt64ArraySerializer : Serializer<ulong[]>
{



    public override ulong[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in ulong[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        ulong[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 8));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt64(safe[i]);
        }
    }

    public override ulong[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 7) != 0)
        {
            throw new InvalidDataException("uint64 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        ulong[] values = new ulong[byteSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt64();
        }

        return values;
    }
}

internal sealed class Float16ArraySerializer : Serializer<Half[]>
{



    public override Half[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in Half[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        Half[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 2));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt16(BitConverter.HalfToUInt16Bits(safe[i]));
        }
    }

    public override Half[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 1) != 0)
        {
            throw new InvalidDataException("float16 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        Half[] values = new Half[byteSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = BitConverter.UInt16BitsToHalf(context.Reader.ReadUInt16());
        }

        return values;
    }
}

internal sealed class BFloat16ArraySerializer : Serializer<BFloat16[]>
{



    public override BFloat16[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in BFloat16[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        BFloat16[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 2));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteUInt16(safe[i].ToBits());
        }
    }

    public override BFloat16[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 1) != 0)
        {
            throw new InvalidDataException("bfloat16 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        BFloat16[] values = new BFloat16[byteSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = BFloat16.FromBits(context.Reader.ReadUInt16());
        }

        return values;
    }
}

internal sealed class Float32ArraySerializer : Serializer<float[]>
{



    public override float[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in float[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        float[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 4));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteFloat32(safe[i]);
        }
    }

    public override float[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 3) != 0)
        {
            throw new InvalidDataException("float32 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        float[] values = new float[byteSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadFloat32();
        }

        return values;
    }
}

internal sealed class Float64ArraySerializer : Serializer<double[]>
{



    public override double[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in double[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        double[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)(safe.Length * 8));
        for (int i = 0; i < safe.Length; i++)
        {
            context.Writer.WriteFloat64(safe[i]);
        }
    }

    public override double[] ReadData(ReadContext context)
    {
        int byteSize = checked((int)context.Reader.ReadVarUInt32());
        if ((byteSize & 7) != 0)
        {
            throw new InvalidDataException("float64 array byte size mismatch");
        }

        context.Reader.CheckBound(byteSize);
        double[] values = new double[byteSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadFloat64();
        }

        return values;
    }
}
