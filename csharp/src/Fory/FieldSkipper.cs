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

public static class FieldSkipper
{
    public static void SkipFieldValue(ReadContext context, TypeMetaFieldType fieldType)
    {
        SkipValue(context, fieldType, RefModeExtensions.From(fieldType.Nullable, fieldType.TrackRef));
    }

    private static void SkipValue(ReadContext context, TypeMetaFieldType fieldType, RefMode refMode)
    {
        switch (refMode)
        {
            case RefMode.None:
                SkipPayload(context, fieldType);
                return;
            case RefMode.NullOnly:
                {
                    sbyte flag = context.Reader.ReadInt8();
                    if (flag == (sbyte)RefFlag.Null)
                    {
                        return;
                    }

                    if (flag != (sbyte)RefFlag.NotNullValue)
                    {
                        throw new InvalidDataException($"unexpected nullOnly flag {flag}");
                    }

                    SkipPayload(context, fieldType);
                    return;
                }
            case RefMode.Tracking:
                _ = ReadTrackedValue(context, fieldType);
                return;
            default:
                throw new InvalidDataException($"unsupported ref mode {refMode}");
        }
    }

    private static object? ReadTrackedValue(ReadContext context, TypeMetaFieldType fieldType)
    {
        return fieldType.TypeId switch
        {
            (uint)TypeId.String => context.TypeResolver.GetSerializer<string>().Read(context, RefMode.Tracking, false),
            (uint)TypeId.List => context.TypeResolver.GetSerializer<List<object?>>().Read(context, RefMode.Tracking, false),
            (uint)TypeId.Set => context.TypeResolver.GetSerializer<HashSet<object?>>().Read(context, RefMode.Tracking, false),
            (uint)TypeId.Map => context.TypeResolver.GetSerializer<NullableKeyDictionary<object, object?>>().Read(context, RefMode.Tracking, false),
            (uint)TypeId.Union or
            (uint)TypeId.TypedUnion or
            (uint)TypeId.NamedUnion => context.TypeResolver.GetSerializer<Union>().Read(context, RefMode.Tracking, false),
            _ => throw new InvalidDataException($"unsupported tracked skip field type id {fieldType.TypeId}"),
        };
    }

    private static void SkipPayload(ReadContext context, TypeMetaFieldType fieldType)
    {
        switch (fieldType.TypeId)
        {
            case (uint)TypeId.Bool:
            case (uint)TypeId.Int8:
            case (uint)TypeId.UInt8:
                context.Reader.Skip(1);
                return;
            case (uint)TypeId.Int16:
            case (uint)TypeId.UInt16:
            case (uint)TypeId.Float16:
            case (uint)TypeId.BFloat16:
                context.Reader.Skip(2);
                return;
            case (uint)TypeId.Int32:
            case (uint)TypeId.UInt32:
            case (uint)TypeId.Float32:
            case (uint)TypeId.Date:
                context.Reader.Skip(4);
                return;
            case (uint)TypeId.Int64:
            case (uint)TypeId.UInt64:
            case (uint)TypeId.Float64:
            case (uint)TypeId.Timestamp:
            case (uint)TypeId.Duration:
                context.Reader.Skip(8);
                return;
            case (uint)TypeId.VarInt32:
                _ = context.Reader.ReadVarInt32();
                return;
            case (uint)TypeId.VarUInt32:
                _ = context.Reader.ReadVarUInt32();
                return;
            case (uint)TypeId.VarInt64:
                _ = context.Reader.ReadVarInt64();
                return;
            case (uint)TypeId.VarUInt64:
                _ = context.Reader.ReadVarUInt64();
                return;
            case (uint)TypeId.TaggedInt64:
                _ = context.Reader.ReadTaggedInt64();
                return;
            case (uint)TypeId.TaggedUInt64:
                _ = context.Reader.ReadTaggedUInt64();
                return;
            case (uint)TypeId.String:
                _ = StringSerializer.ReadString(context);
                return;
            case (uint)TypeId.Decimal:
                _ = context.TypeResolver.GetSerializer<ForyDecimal>().ReadData(context);
                return;
            case (uint)TypeId.Binary:
            case (uint)TypeId.BoolArray:
            case (uint)TypeId.Int8Array:
            case (uint)TypeId.Int16Array:
            case (uint)TypeId.Int32Array:
            case (uint)TypeId.Int64Array:
            case (uint)TypeId.UInt8Array:
            case (uint)TypeId.UInt16Array:
            case (uint)TypeId.UInt32Array:
            case (uint)TypeId.UInt64Array:
            case (uint)TypeId.Float16Array:
            case (uint)TypeId.BFloat16Array:
            case (uint)TypeId.Float32Array:
            case (uint)TypeId.Float64Array:
                SkipPackedArray(context);
                return;
            case (uint)TypeId.List:
            case (uint)TypeId.Set:
                SkipListOrSet(context, fieldType);
                return;
            case (uint)TypeId.Map:
                SkipMap(context, fieldType);
                return;
            case (uint)TypeId.Enum:
            case (uint)TypeId.NamedEnum:
                _ = context.Reader.ReadVarUInt32();
                return;
            case (uint)TypeId.Union:
            case (uint)TypeId.TypedUnion:
            case (uint)TypeId.NamedUnion:
                _ = context.TypeResolver.GetSerializer<Union>().ReadData(context);
                return;
            default:
                throw new InvalidDataException($"unsupported compatible field type id {fieldType.TypeId}");
        }
    }

    private static void SkipPackedArray(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        context.Reader.Skip(payloadSize);
    }

    private static void SkipListOrSet(ReadContext context, TypeMetaFieldType fieldType)
    {
        if (fieldType.Generics.Count != 1)
        {
            throw new InvalidDataException("list/set field metadata must have one element type");
        }

        int length = checked((int)context.Reader.ReadVarUInt32());
        if (length == 0)
        {
            return;
        }

        TypeMetaFieldType elementType = fieldType.Generics[0];
        byte header = context.Reader.ReadUInt8();
        bool trackRef = (header & CollectionBits.TrackingRef) != 0;
        bool hasNull = (header & CollectionBits.HasNull) != 0;
        bool declared = (header & CollectionBits.DeclaredElementType) != 0;
        bool sameType = (header & CollectionBits.SameType) != 0;
        if (!sameType)
        {
            throw new InvalidDataException("dynamic compatible list/set skip is not supported");
        }

        if (!declared)
        {
            _ = context.TypeResolver.ReadAnyTypeInfo(context);
        }

        RefMode elementRefMode = trackRef ? RefMode.Tracking : hasNull ? RefMode.NullOnly : RefMode.None;
        for (int i = 0; i < length; i++)
        {
            SkipValue(context, elementType, elementRefMode);
        }
    }

    private static void SkipMap(ReadContext context, TypeMetaFieldType fieldType)
    {
        if (fieldType.Generics.Count != 2)
        {
            throw new InvalidDataException("map field metadata must have key/value types");
        }

        TypeMetaFieldType keyType = fieldType.Generics[0];
        TypeMetaFieldType valueType = fieldType.Generics[1];
        int totalLength = checked((int)context.Reader.ReadVarUInt32());
        int readCount = 0;
        while (readCount < totalLength)
        {
            byte header = context.Reader.ReadUInt8();
            bool trackKeyRef = (header & DictionaryBits.TrackingKeyRef) != 0;
            bool keyNull = (header & DictionaryBits.KeyNull) != 0;
            bool keyDeclared = (header & DictionaryBits.DeclaredKeyType) != 0;
            bool trackValueRef = (header & DictionaryBits.TrackingValueRef) != 0;
            bool valueNull = (header & DictionaryBits.ValueNull) != 0;
            bool valueDeclared = (header & DictionaryBits.DeclaredValueType) != 0;

            if (keyNull || valueNull)
            {
                if (!keyNull)
                {
                    if (!keyDeclared)
                    {
                        _ = context.TypeResolver.ReadAnyTypeInfo(context);
                    }

                    SkipValue(context, keyType, trackKeyRef ? RefMode.Tracking : RefMode.None);
                }

                if (!valueNull)
                {
                    if (!valueDeclared)
                    {
                        _ = context.TypeResolver.ReadAnyTypeInfo(context);
                    }

                    SkipValue(context, valueType, trackValueRef ? RefMode.Tracking : RefMode.None);
                }

                readCount++;
                continue;
            }

            int chunkSize = context.Reader.ReadUInt8();
            if (!keyDeclared)
            {
                _ = context.TypeResolver.ReadAnyTypeInfo(context);
            }

            if (!valueDeclared)
            {
                _ = context.TypeResolver.ReadAnyTypeInfo(context);
            }

            for (int i = 0; i < chunkSize; i++)
            {
                SkipValue(context, keyType, trackKeyRef ? RefMode.Tracking : RefMode.None);
                SkipValue(context, valueType, trackValueRef ? RefMode.Tracking : RefMode.None);
            }

            readCount += chunkSize;
        }
    }
}
