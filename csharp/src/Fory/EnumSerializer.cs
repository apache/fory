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

public sealed class EnumSerializer<TEnum> : Serializer<TEnum> where TEnum : struct, Enum
{
    private static readonly Dictionary<TEnum, uint> DefinedValueToOrdinal = BuildValueToOrdinalMap();
    private static readonly Dictionary<uint, TEnum> DefinedOrdinalToValue = BuildOrdinalToValueMap(DefinedValueToOrdinal);

    public TypeId StaticTypeId => TypeId.Enum;
    public override TEnum DefaultValue => default;

    public override void WriteData(WriteContext context, in TEnum value, bool hasGenerics)
    {
        _ = hasGenerics;
        if (!DefinedValueToOrdinal.TryGetValue(value, out uint ordinal))
        {
            ordinal = Convert.ToUInt32(value);
        }

        context.Writer.WriteVarUInt32(ordinal);
    }

    public override TEnum ReadData(ReadContext context)
    {
        uint ordinal = context.Reader.ReadVarUInt32();
        if (DefinedOrdinalToValue.TryGetValue(ordinal, out TEnum value))
        {
            return value;
        }

        return (TEnum)Enum.ToObject(typeof(TEnum), ordinal);
    }

    private static Dictionary<TEnum, uint> BuildValueToOrdinalMap()
    {
        Dictionary<TEnum, uint> values = [];
        foreach (TEnum value in Enum.GetValues<TEnum>())
        {
            values[value] = Convert.ToUInt32(value);
        }

        return values;
    }

    private static Dictionary<uint, TEnum> BuildOrdinalToValueMap(Dictionary<TEnum, uint> valueToOrdinal)
    {
        Dictionary<uint, TEnum> ordinalToValue = [];
        foreach (KeyValuePair<TEnum, uint> pair in valueToOrdinal)
        {
            ordinalToValue[pair.Value] = pair.Key;
        }

        return ordinalToValue;
    }
}
