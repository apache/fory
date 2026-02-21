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

public readonly struct DynamicAnyObjectSerializer : IStaticSerializer<DynamicAnyObjectSerializer, object?>
{
    public static TypeId StaticTypeId => TypeId.Unknown;
    public static bool IsNullableType => true;
    public static bool IsReferenceTrackableType => true;
    public static object? DefaultValue => null;
    public static bool IsNone(in object? value) => value is null;

    public static void WriteData(ref WriteContext context, in object? value, bool hasGenerics)
    {
        if (IsNone(value))
        {
            return;
        }

        DynamicAnyCodec.WriteAnyPayload(value!, ref context, hasGenerics);
    }

    public static object? ReadData(ref ReadContext context)
    {
        DynamicTypeInfo? dynamicTypeInfo = context.DynamicTypeInfo(typeof(object));
        if (dynamicTypeInfo is null)
        {
            throw new InvalidDataException("dynamic Any value requires type info");
        }

        return context.TypeResolver.ReadDynamicValue(dynamicTypeInfo, ref context);
    }

    public static void WriteTypeInfo(ref WriteContext context)
    {
        throw new InvalidDataException("dynamic Any value type info is runtime-only");
    }

    public static void ReadTypeInfo(ref ReadContext context)
    {
        DynamicTypeInfo typeInfo = context.TypeResolver.ReadDynamicTypeInfo(ref context);
        context.SetDynamicTypeInfo(typeof(object), typeInfo);
    }

    public static void Write(ref WriteContext context, in object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        if (refMode != RefMode.None)
        {
            if (IsNone(value))
            {
                context.Writer.WriteInt8((sbyte)RefFlag.Null);
                return;
            }

            bool wroteTrackingRefFlag = false;
            if (refMode == RefMode.Tracking && AnyValueIsReferenceTrackable(value!))
            {
                if (context.RefWriter.TryWriteReference(context.Writer, value!))
                {
                    return;
                }

                wroteTrackingRefFlag = true;
            }

            if (!wroteTrackingRefFlag)
            {
                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
            }
        }

        if (writeTypeInfo)
        {
            DynamicAnyCodec.WriteAnyTypeInfo(value!, ref context);
        }

        WriteData(ref context, value, hasGenerics);
    }

    public static object? Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        if (refMode != RefMode.None)
        {
            sbyte rawFlag = context.Reader.ReadInt8();
            RefFlag flag = (RefFlag)rawFlag;
            switch (flag)
            {
                case RefFlag.Null:
                    return null;
                case RefFlag.Ref:
                {
                    uint refId = context.Reader.ReadVarUInt32();
                    return context.RefReader.ReadRefValue(refId);
                }
                case RefFlag.RefValue:
                {
                    uint reservedRefId = context.RefReader.ReserveRefId();
                    context.PushPendingReference(reservedRefId);
                    if (readTypeInfo)
                    {
                        ReadTypeInfo(ref context);
                    }

                    object? value = ReadData(ref context);
                    if (readTypeInfo)
                    {
                        context.ClearDynamicTypeInfo(typeof(object));
                    }

                    context.FinishPendingReferenceIfNeeded(value);
                    context.PopPendingReference();
                    return value;
                }
                case RefFlag.NotNullValue:
                    break;
                default:
                    throw new RefException($"invalid ref flag {rawFlag}");
            }
        }

        if (readTypeInfo)
        {
            ReadTypeInfo(ref context);
        }

        object? result = ReadData(ref context);
        if (readTypeInfo)
        {
            context.ClearDynamicTypeInfo(typeof(object));
        }

        return result;
    }

    private static bool AnyValueIsReferenceTrackable(object value)
    {
        SerializerBinding serializer = SerializerRegistry.GetBinding(value.GetType());
        return serializer.IsReferenceTrackableType;
    }

}

public static class DynamicAnyCodec
{
    internal static void WriteAnyTypeInfo(object value, ref WriteContext context)
    {
        if (DynamicContainerCodec.TryGetTypeId(value, out TypeId containerTypeId))
        {
            context.Writer.WriteUInt8((byte)containerTypeId);
            return;
        }

        SerializerBinding serializer = SerializerRegistry.GetBinding(value.GetType());
        serializer.WriteTypeInfo(ref context);
    }

    public static object? CastAnyDynamicValue(object? value, Type targetType)
    {
        if (value is null)
        {
            if (targetType == typeof(object))
            {
                return null;
            }

            if (!targetType.IsValueType || Nullable.GetUnderlyingType(targetType) is not null)
            {
                return null;
            }

            throw new InvalidDataException($"cannot cast null dynamic Any value to non-nullable {targetType}");
        }

        if (targetType.IsInstanceOfType(value))
        {
            return value;
        }

        throw new InvalidDataException($"cannot cast dynamic Any value to {targetType}");
    }

    public static void WriteAny(ref WriteContext context, object? value, RefMode refMode, bool writeTypeInfo = true, bool hasGenerics = false)
    {
        SerializerRegistry.Get<object?>().Write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public static object? ReadAny(ref ReadContext context, RefMode refMode, bool readTypeInfo = true)
    {
        return SerializerRegistry.Get<object?>().Read(ref context, refMode, readTypeInfo);
    }

    public static void WriteAnyPayload(object value, ref WriteContext context, bool hasGenerics)
    {
        if (DynamicContainerCodec.TryWritePayload(value, ref context, hasGenerics))
        {
            return;
        }

        SerializerBinding serializer = SerializerRegistry.GetBinding(value.GetType());
        serializer.WriteData(ref context, value, hasGenerics);
    }
}
