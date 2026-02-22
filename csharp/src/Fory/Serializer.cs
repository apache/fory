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

public readonly struct Serializer<T>
{
    private readonly ITypedSerializer<T>? _serializer;

    internal Serializer(ITypedSerializer<T> serializer)
    {
        _serializer = serializer;
    }

    private ITypedSerializer<T> SerializerImpl
    {
        get
        {
            if (_serializer is null)
            {
                throw new InvalidDataException($"serializer handle for {typeof(T)} is not initialized");
            }

            return _serializer;
        }
    }

    public Type Type => typeof(T);

    public TypeId StaticTypeId => SerializerImpl.StaticTypeId;

    public bool IsNullableType => SerializerImpl.IsNullableType;

    public bool IsReferenceTrackableType => SerializerImpl.IsReferenceTrackableType;

    public T DefaultValue => SerializerImpl.DefaultValue;

    public object? DefaultObject => SerializerImpl.DefaultValue;

    public bool IsNone(in T value)
    {
        return SerializerImpl.IsNone(value);
    }

    public bool IsNoneObject(object? value)
    {
        if (value is null)
        {
            return IsNullableType;
        }

        return value is T typed && IsNone(typed);
    }

    public void WriteData(ref WriteContext context, in T value, bool hasGenerics)
    {
        SerializerImpl.WriteData(ref context, value, hasGenerics);
    }

    public T ReadData(ref ReadContext context)
    {
        return SerializerImpl.ReadData(ref context);
    }

    public void Write(ref WriteContext context, in T value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        SerializerImpl.Write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public T Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return SerializerImpl.Read(ref context, refMode, readTypeInfo);
    }

    public void WriteTypeInfo(ref WriteContext context)
    {
        SerializerImpl.WriteTypeInfo(ref context);
    }

    public void ReadTypeInfo(ref ReadContext context)
    {
        SerializerImpl.ReadTypeInfo(ref context);
    }

    public IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return SerializerImpl.CompatibleTypeMetaFields(trackRef);
    }
}
