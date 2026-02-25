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
        _ = ReadFieldValue(context, fieldType);
    }

    private static uint? ReadEnumOrdinal(ReadContext context, RefMode refMode)
    {
        return refMode switch
        {
            RefMode.None => context.Reader.ReadVarUInt32(),
            RefMode.NullOnly => ReadNullableEnumOrdinal(context),
            RefMode.Tracking => throw new InvalidDataException("enum tracking ref mode is not supported"),
            _ => throw new InvalidDataException($"unsupported ref mode {refMode}"),
        };
    }

    private static uint? ReadNullableEnumOrdinal(ReadContext context)
    {
        sbyte flag = context.Reader.ReadInt8();
        if (flag == (sbyte)RefFlag.Null)
        {
            return null;
        }

        if (flag != (sbyte)RefFlag.NotNullValue)
        {
            throw new InvalidDataException($"unexpected enum nullOnly flag {flag}");
        }

        return context.Reader.ReadVarUInt32();
    }

    private static object? ReadFieldValue(ReadContext context, TypeMetaFieldType fieldType)
    {
        RefMode refMode = RefModeExtensions.From(fieldType.Nullable, fieldType.TrackRef);
        switch (fieldType.TypeId)
        {
            case (uint)TypeId.Bool:
                return context.TypeResolver.GetSerializer<bool>().Read(context, refMode, false);
            case (uint)TypeId.Int8:
                return context.TypeResolver.GetSerializer<sbyte>().Read(context, refMode, false);
            case (uint)TypeId.Int16:
                return context.TypeResolver.GetSerializer<short>().Read(context, refMode, false);
            case (uint)TypeId.VarInt32:
                return context.TypeResolver.GetSerializer<int>().Read(context, refMode, false);
            case (uint)TypeId.VarInt64:
                return context.TypeResolver.GetSerializer<long>().Read(context, refMode, false);
            case (uint)TypeId.Float32:
                return context.TypeResolver.GetSerializer<float>().Read(context, refMode, false);
            case (uint)TypeId.Float64:
                return context.TypeResolver.GetSerializer<double>().Read(context, refMode, false);
            case (uint)TypeId.String:
                return context.TypeResolver.GetSerializer<string>().Read(context, refMode, false);
            case (uint)TypeId.List:
            {
                if (fieldType.Generics.Count != 1 || fieldType.Generics[0].TypeId != (uint)TypeId.String)
                {
                    throw new InvalidDataException("unsupported compatible list element type");
                }

                return context.TypeResolver.GetSerializer<List<string>>().Read(context, refMode, false);
            }
            case (uint)TypeId.Set:
            {
                if (fieldType.Generics.Count != 1 || fieldType.Generics[0].TypeId != (uint)TypeId.String)
                {
                    throw new InvalidDataException("unsupported compatible set element type");
                }

                return context.TypeResolver.GetSerializer<HashSet<string>>().Read(context, refMode, false);
            }
            case (uint)TypeId.Map:
            {
                if (fieldType.Generics.Count != 2 ||
                    fieldType.Generics[0].TypeId != (uint)TypeId.String ||
                    fieldType.Generics[1].TypeId != (uint)TypeId.String)
                {
                    throw new InvalidDataException("unsupported compatible map key/value type");
                }

                return context.TypeResolver.GetSerializer<Dictionary<string, string>>().Read(context, refMode, false);
            }
            case (uint)TypeId.Enum:
                return ReadEnumOrdinal(context, refMode);
            case (uint)TypeId.Union:
            case (uint)TypeId.TypedUnion:
            case (uint)TypeId.NamedUnion:
                return context.TypeResolver.GetSerializer<Union>().Read(context, refMode, false);
            default:
                throw new InvalidDataException($"unsupported compatible field type id {fieldType.TypeId}");
        }
    }
}
