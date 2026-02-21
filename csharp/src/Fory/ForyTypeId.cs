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

public enum ForyTypeId : uint
{
    Unknown = 0,
    Bool = 1,
    Int8 = 2,
    Int16 = 3,
    Int32 = 4,
    VarInt32 = 5,
    Int64 = 6,
    VarInt64 = 7,
    TaggedInt64 = 8,
    UInt8 = 9,
    UInt16 = 10,
    UInt32 = 11,
    VarUInt32 = 12,
    UInt64 = 13,
    VarUInt64 = 14,
    TaggedUInt64 = 15,
    Float8 = 16,
    Float16 = 17,
    BFloat16 = 18,
    Float32 = 19,
    Float64 = 20,
    String = 21,
    List = 22,
    Set = 23,
    Map = 24,
    Enum = 25,
    NamedEnum = 26,
    Struct = 27,
    CompatibleStruct = 28,
    NamedStruct = 29,
    NamedCompatibleStruct = 30,
    Ext = 31,
    NamedExt = 32,
    Union = 33,
    TypedUnion = 34,
    NamedUnion = 35,
    None = 36,
    Duration = 37,
    Timestamp = 38,
    Date = 39,
    Decimal = 40,
    Binary = 41,
    Array = 42,
    BoolArray = 43,
    Int8Array = 44,
    Int16Array = 45,
    Int32Array = 46,
    Int64Array = 47,
    UInt8Array = 48,
    UInt16Array = 49,
    UInt32Array = 50,
    UInt64Array = 51,
    Float8Array = 52,
    Float16Array = 53,
    BFloat16Array = 54,
    Float32Array = 55,
    Float64Array = 56,
}

internal static class ForyTypeIdExtensions
{
    public static bool IsUserTypeKind(this ForyTypeId typeId)
    {
        return typeId switch
        {
            ForyTypeId.Enum or
                ForyTypeId.NamedEnum or
                ForyTypeId.Struct or
                ForyTypeId.CompatibleStruct or
                ForyTypeId.NamedStruct or
                ForyTypeId.NamedCompatibleStruct or
                ForyTypeId.Ext or
                ForyTypeId.NamedExt or
                ForyTypeId.TypedUnion or
                ForyTypeId.NamedUnion => true,
            _ => false,
        };
    }

    public static bool NeedsTypeInfoForField(this ForyTypeId typeId)
    {
        return typeId switch
        {
            ForyTypeId.Struct or
                ForyTypeId.CompatibleStruct or
                ForyTypeId.NamedStruct or
                ForyTypeId.NamedCompatibleStruct or
                ForyTypeId.Ext or
                ForyTypeId.NamedExt or
                ForyTypeId.Unknown => true,
            _ => false,
        };
    }
}

