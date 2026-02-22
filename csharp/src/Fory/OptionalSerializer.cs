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

public sealed class NullableSerializer<T> : Serializer<T?> where T : struct
{
    public override TypeId StaticTypeId => TypeResolver.StaticTypeIdOf<T>();

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => TypeResolver.IsReferenceTrackableTypeOf<T>();

    public override T? DefaultValue => null;

    public override bool IsNone(in T? value)
    {
        return !value.HasValue;
    }

    public override void WriteData(ref WriteContext context, in T? value, bool hasGenerics)
    {
        if (!value.HasValue)
        {
            throw new InvalidDataException("Nullable<T>.None cannot write raw payload");
        }

        T wrapped = value.Value;
        Serializer<T> wrappedSerializer = context.TypeResolver.GetSerializer<T>();
        wrappedSerializer.WriteData(ref context, wrapped, hasGenerics);
    }

    public override T? ReadData(ref ReadContext context)
    {
        Serializer<T> wrappedSerializer = context.TypeResolver.GetSerializer<T>();
        return wrappedSerializer.ReadData(ref context);
    }

    public override void WriteTypeInfo(ref WriteContext context)
    {
        Serializer<T> wrappedSerializer = context.TypeResolver.GetSerializer<T>();
        wrappedSerializer.WriteTypeInfo(ref context);
    }

    public override void ReadTypeInfo(ref ReadContext context)
    {
        Serializer<T> wrappedSerializer = context.TypeResolver.GetSerializer<T>();
        wrappedSerializer.ReadTypeInfo(ref context);
    }

    public override IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return TypeResolver.CompatibleTypeMetaFieldsOf<T>(trackRef);
    }

    public override void Write(ref WriteContext context, in T? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        Serializer<T> wrappedSerializer = context.TypeResolver.GetSerializer<T>();
        switch (refMode)
        {
            case RefMode.None:
                if (!value.HasValue)
                {
                    throw new InvalidDataException("Nullable<T>.None with RefMode.None");
                }

                T wrapped = value.Value;
                wrappedSerializer.Write(ref context, wrapped, RefMode.None, writeTypeInfo, hasGenerics);
                break;
            case RefMode.NullOnly:
                if (!value.HasValue)
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    return;
                }

                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
                wrappedSerializer.Write(ref context, value.Value, RefMode.None, writeTypeInfo, hasGenerics);
                break;
            case RefMode.Tracking:
                if (!value.HasValue)
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    return;
                }

                wrappedSerializer.Write(ref context, value.Value, RefMode.Tracking, writeTypeInfo, hasGenerics);
                break;
            default:
                throw new InvalidDataException($"unsupported ref mode {refMode}");
        }
    }

    public override T? Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        Serializer<T> wrappedSerializer = context.TypeResolver.GetSerializer<T>();
        switch (refMode)
        {
            case RefMode.None:
                return wrappedSerializer.Read(ref context, RefMode.None, readTypeInfo);
            case RefMode.NullOnly:
            {
                sbyte refFlag = context.Reader.ReadInt8();
                if (refFlag == (sbyte)RefFlag.Null)
                {
                    return null;
                }

                return wrappedSerializer.Read(ref context, RefMode.None, readTypeInfo);
            }
            case RefMode.Tracking:
            {
                sbyte refFlag = context.Reader.ReadInt8();
                if (refFlag == (sbyte)RefFlag.Null)
                {
                    return null;
                }

                context.Reader.MoveBack(1);
                return wrappedSerializer.Read(ref context, RefMode.Tracking, readTypeInfo);
            }
            default:
                throw new InvalidDataException($"unsupported ref mode {refMode}");
        }
    }
}
