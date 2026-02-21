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

public readonly struct NullableSerializer<T> : IStaticSerializer<NullableSerializer<T>, T?> where T : struct
{
    private static Serializer<T> WrappedSerializer => SerializerRegistry.Get<T>();

    public static ForyTypeId StaticTypeId => WrappedSerializer.StaticTypeId;

    public static bool IsNullableType => true;

    public static bool IsReferenceTrackableType => WrappedSerializer.IsReferenceTrackableType;

    public static T? DefaultValue => null;

    public static bool IsNone(in T? value)
    {
        return !value.HasValue;
    }

    public static void WriteData(ref WriteContext context, in T? value, bool hasGenerics)
    {
        if (!value.HasValue)
        {
            throw new ForyInvalidDataException("Nullable<T>.None cannot write raw payload");
        }

        T wrapped = value.Value;
        WrappedSerializer.WriteData(ref context, wrapped, hasGenerics);
    }

    public static T? ReadData(ref ReadContext context)
    {
        return WrappedSerializer.ReadData(ref context);
    }

    public static void WriteTypeInfo(ref WriteContext context)
    {
        WrappedSerializer.WriteTypeInfo(ref context);
    }

    public static void ReadTypeInfo(ref ReadContext context)
    {
        WrappedSerializer.ReadTypeInfo(ref context);
    }

    public static IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return WrappedSerializer.CompatibleTypeMetaFields(trackRef);
    }

    public static void Write(ref WriteContext context, in T? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        switch (refMode)
        {
            case RefMode.None:
                if (!value.HasValue)
                {
                    throw new ForyInvalidDataException("Nullable<T>.None with RefMode.None");
                }

                T wrapped = value.Value;
                WrappedSerializer.Write(ref context, wrapped, RefMode.None, writeTypeInfo, hasGenerics);
                break;
            case RefMode.NullOnly:
                if (!value.HasValue)
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    return;
                }

                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
                WrappedSerializer.Write(ref context, value.Value, RefMode.None, writeTypeInfo, hasGenerics);
                break;
            case RefMode.Tracking:
                if (!value.HasValue)
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    return;
                }

                WrappedSerializer.Write(ref context, value.Value, RefMode.Tracking, writeTypeInfo, hasGenerics);
                break;
        }
    }

    public static T? Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        switch (refMode)
        {
            case RefMode.None:
                return WrappedSerializer.Read(ref context, RefMode.None, readTypeInfo);
            case RefMode.NullOnly:
            {
                sbyte refFlag = context.Reader.ReadInt8();
                if (refFlag == (sbyte)RefFlag.Null)
                {
                    return null;
                }

                return WrappedSerializer.Read(ref context, RefMode.None, readTypeInfo);
            }
            case RefMode.Tracking:
            {
                sbyte refFlag = context.Reader.ReadInt8();
                if (refFlag == (sbyte)RefFlag.Null)
                {
                    return null;
                }

                context.Reader.MoveBack(1);
                return WrappedSerializer.Read(ref context, RefMode.Tracking, readTypeInfo);
            }
            default:
                throw new ForyInvalidDataException($"unsupported ref mode {refMode}");
        }
    }
}
