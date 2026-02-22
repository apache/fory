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

public sealed class DynamicAnyObjectSerializer : Serializer<object?>
{
    public override TypeId StaticTypeId => TypeId.Unknown;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override object? DefaultValue => null;
    public override bool IsNone(in object? value) => value is null;

    public override void WriteData(ref WriteContext context, in object? value, bool hasGenerics)
    {
        if (IsNone(value))
        {
            return;
        }

        DynamicAnyCodec.WriteAnyPayload(value!, ref context, hasGenerics);
    }

    public override object? ReadData(ref ReadContext context)
    {
        DynamicTypeInfo? dynamicTypeInfo = context.DynamicTypeInfo(typeof(object));
        if (dynamicTypeInfo is null)
        {
            throw new InvalidDataException("dynamic Any value requires type info");
        }

        return context.TypeResolver.ReadDynamicValue(dynamicTypeInfo, ref context);
    }

    public override void WriteTypeInfo(ref WriteContext context)
    {
        throw new InvalidDataException("dynamic Any value type info is runtime-only");
    }

    public override void ReadTypeInfo(ref ReadContext context)
    {
        DynamicTypeInfo typeInfo = context.TypeResolver.ReadDynamicTypeInfo(ref context);
        context.SetDynamicTypeInfo(typeof(object), typeInfo);
    }

    public override void Write(ref WriteContext context, in object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        if (refMode != RefMode.None)
        {
            if (IsNone(value))
            {
                context.Writer.WriteInt8((sbyte)RefFlag.Null);
                return;
            }

            bool wroteTrackingRefFlag = false;
            if (refMode == RefMode.Tracking && AnyValueIsReferenceTrackable(value!, context.TypeResolver))
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

    public override object? Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
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

    private static bool AnyValueIsReferenceTrackable(object value, TypeResolver typeResolver)
    {
        Serializer serializer = typeResolver.GetSerializer(value.GetType());
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

        Serializer serializer = context.TypeResolver.GetSerializer(value.GetType());
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
        context.TypeResolver.GetSerializer<object?>().Write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public static object? ReadAny(ref ReadContext context, RefMode refMode, bool readTypeInfo = true)
    {
        return context.TypeResolver.GetSerializer<object?>().Read(ref context, refMode, readTypeInfo);
    }

    public static void WriteAnyPayload(object value, ref WriteContext context, bool hasGenerics)
    {
        if (DynamicContainerCodec.TryWritePayload(value, ref context, hasGenerics))
        {
            return;
        }

        Serializer serializer = context.TypeResolver.GetSerializer(value.GetType());
        serializer.WriteDataObject(ref context, value, hasGenerics);
    }
}
