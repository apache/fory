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
    private readonly Serializer<T> _wrappedSerializer;

    public NullableSerializer()
    {
        _wrappedSerializer = SerializerRegistry.Get<T>();
    }

    public override ForyTypeId StaticTypeId => _wrappedSerializer.StaticTypeId;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => _wrappedSerializer.IsReferenceTrackableType;

    public override T? DefaultValue => null;

    public override bool IsNone(T? value)
    {
        return !value.HasValue;
    }

    public override void WriteData(ref WriteContext context, in T? value, bool hasGenerics)
    {
        if (!value.HasValue)
        {
            throw new ForyInvalidDataException("Nullable<T>.None cannot write raw payload");
        }

        T wrapped = value.Value;
        _wrappedSerializer.WriteData(ref context, wrapped, hasGenerics);
    }

    public override T? ReadData(ref ReadContext context)
    {
        return _wrappedSerializer.ReadData(ref context);
    }

    public override void WriteTypeInfo(ref WriteContext context)
    {
        _wrappedSerializer.WriteTypeInfo(ref context);
    }

    public override void ReadTypeInfo(ref ReadContext context)
    {
        _wrappedSerializer.ReadTypeInfo(ref context);
    }

    public override IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return _wrappedSerializer.CompatibleTypeMetaFields(trackRef);
    }

    public override void Write(ref WriteContext context, in T? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        switch (refMode)
        {
            case RefMode.None:
                if (!value.HasValue)
                {
                    throw new ForyInvalidDataException("Nullable<T>.None with RefMode.None");
                }

                T wrapped = value.Value;
                _wrappedSerializer.Write(ref context, wrapped, RefMode.None, writeTypeInfo, hasGenerics);
                break;
            case RefMode.NullOnly:
                if (!value.HasValue)
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    return;
                }

                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
                _wrappedSerializer.Write(ref context, value.Value, RefMode.None, writeTypeInfo, hasGenerics);
                break;
            case RefMode.Tracking:
                if (!value.HasValue)
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    return;
                }

                _wrappedSerializer.Write(ref context, value.Value, RefMode.Tracking, writeTypeInfo, hasGenerics);
                break;
        }
    }

    public override T? Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        switch (refMode)
        {
            case RefMode.None:
                return _wrappedSerializer.Read(ref context, RefMode.None, readTypeInfo);
            case RefMode.NullOnly:
            {
                sbyte refFlag = context.Reader.ReadInt8();
                if (refFlag == (sbyte)RefFlag.Null)
                {
                    return null;
                }

                return _wrappedSerializer.Read(ref context, RefMode.None, readTypeInfo);
            }
            case RefMode.Tracking:
            {
                sbyte refFlag = context.Reader.ReadInt8();
                if (refFlag == (sbyte)RefFlag.Null)
                {
                    return null;
                }

                context.Reader.MoveBack(1);
                return _wrappedSerializer.Read(ref context, RefMode.Tracking, readTypeInfo);
            }
            default:
                throw new ForyInvalidDataException($"unsupported ref mode {refMode}");
        }
    }
}

