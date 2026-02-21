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
    public static void SkipFieldValue(ref ReadContext context, TypeMetaFieldType fieldType)
    {
        _ = ReadFieldValue(ref context, fieldType);
    }

    private static uint? ReadEnumOrdinal(ref ReadContext context, RefMode refMode)
    {
        return refMode switch
        {
            RefMode.None => context.Reader.ReadVarUInt32(),
            RefMode.NullOnly => ReadNullableEnumOrdinal(ref context),
            RefMode.Tracking => throw new ForyInvalidDataException("enum tracking ref mode is not supported"),
            _ => throw new ForyInvalidDataException($"unsupported ref mode {refMode}"),
        };
    }

    private static uint? ReadNullableEnumOrdinal(ref ReadContext context)
    {
        sbyte flag = context.Reader.ReadInt8();
        if (flag == (sbyte)RefFlag.Null)
        {
            return null;
        }

        if (flag != (sbyte)RefFlag.NotNullValue)
        {
            throw new ForyInvalidDataException($"unexpected enum nullOnly flag {flag}");
        }

        return context.Reader.ReadVarUInt32();
    }

    private static object? ReadFieldValue(ref ReadContext context, TypeMetaFieldType fieldType)
    {
        RefMode refMode = RefModeExtensions.From(fieldType.Nullable, fieldType.TrackRef);
        switch (fieldType.TypeId)
        {
            case (uint)ForyTypeId.Bool:
                return SerializerRegistry.Get<bool>().Read(ref context, refMode, false);
            case (uint)ForyTypeId.Int8:
                return SerializerRegistry.Get<sbyte>().Read(ref context, refMode, false);
            case (uint)ForyTypeId.Int16:
                return SerializerRegistry.Get<short>().Read(ref context, refMode, false);
            case (uint)ForyTypeId.VarInt32:
                return SerializerRegistry.Get<int>().Read(ref context, refMode, false);
            case (uint)ForyTypeId.VarInt64:
                return SerializerRegistry.Get<long>().Read(ref context, refMode, false);
            case (uint)ForyTypeId.Float32:
                return SerializerRegistry.Get<float>().Read(ref context, refMode, false);
            case (uint)ForyTypeId.Float64:
                return SerializerRegistry.Get<double>().Read(ref context, refMode, false);
            case (uint)ForyTypeId.String:
                return SerializerRegistry.Get<string>().Read(ref context, refMode, false);
            case (uint)ForyTypeId.List:
            {
                if (fieldType.Generics.Count != 1 || fieldType.Generics[0].TypeId != (uint)ForyTypeId.String)
                {
                    throw new ForyInvalidDataException("unsupported compatible list element type");
                }

                return SerializerRegistry.Get<List<string>>().Read(ref context, refMode, false);
            }
            case (uint)ForyTypeId.Set:
            {
                if (fieldType.Generics.Count != 1 || fieldType.Generics[0].TypeId != (uint)ForyTypeId.String)
                {
                    throw new ForyInvalidDataException("unsupported compatible set element type");
                }

                return SerializerRegistry.Get<HashSet<string>>().Read(ref context, refMode, false);
            }
            case (uint)ForyTypeId.Map:
            {
                if (fieldType.Generics.Count != 2 ||
                    fieldType.Generics[0].TypeId != (uint)ForyTypeId.String ||
                    fieldType.Generics[1].TypeId != (uint)ForyTypeId.String)
                {
                    throw new ForyInvalidDataException("unsupported compatible map key/value type");
                }

                return SerializerRegistry.Get<Dictionary<string, string>>().Read(ref context, refMode, false);
            }
            case (uint)ForyTypeId.Enum:
                return ReadEnumOrdinal(ref context, refMode);
            case (uint)ForyTypeId.Union:
            case (uint)ForyTypeId.TypedUnion:
            case (uint)ForyTypeId.NamedUnion:
                return SerializerRegistry.Get<Union>().Read(ref context, refMode, false);
            default:
                throw new ForyInvalidDataException($"unsupported compatible field type id {fieldType.TypeId}");
        }
    }
}
