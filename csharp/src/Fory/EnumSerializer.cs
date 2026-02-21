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

public readonly struct EnumSerializer<TEnum> : IStaticSerializer<EnumSerializer<TEnum>, TEnum> where TEnum : struct, Enum
{
    public static ForyTypeId StaticTypeId => ForyTypeId.Enum;
    public static TEnum DefaultValue => default;

    public static void WriteData(ref WriteContext context, in TEnum value, bool hasGenerics)
    {
        _ = hasGenerics;
        uint ordinal = Convert.ToUInt32(value);
        context.Writer.WriteVarUInt32(ordinal);
    }

    public static TEnum ReadData(ref ReadContext context)
    {
        uint ordinal = context.Reader.ReadVarUInt32();
        TEnum value = (TEnum)Enum.ToObject(typeof(TEnum), ordinal);
        if (!Enum.IsDefined(typeof(TEnum), value))
        {
            throw new ForyInvalidDataException($"unknown enum ordinal {ordinal}");
        }

        return value;
    }
}
