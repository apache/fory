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

public sealed class TypeInfo
{
    private readonly object _serializer;
    private readonly Func<object?, bool> _isNoneObject;
    private readonly Action<WriteContext, object?, bool> _writeDataObject;
    private readonly Func<ReadContext, object?> _readDataObject;
    private readonly Action<WriteContext, object?, RefMode, bool, bool> _writeObject;
    private readonly Func<ReadContext, RefMode, bool, object?> _readObject;
    private readonly Action<WriteContext> _writeTypeInfo;
    private readonly Action<ReadContext> _readTypeInfo;
    private readonly Func<bool, IReadOnlyList<TypeMetaFieldInfo>> _compatibleTypeMetaFields;

    private TypeInfo(
        Type type,
        object serializer,
        TypeId staticTypeId,
        bool isNullableType,
        bool isReferenceTrackableType,
        object? defaultObject,
        Func<object?, bool> isNoneObject,
        Action<WriteContext, object?, bool> writeDataObject,
        Func<ReadContext, object?> readDataObject,
        Action<WriteContext, object?, RefMode, bool, bool> writeObject,
        Func<ReadContext, RefMode, bool, object?> readObject,
        Action<WriteContext> writeTypeInfo,
        Action<ReadContext> readTypeInfo,
        Func<bool, IReadOnlyList<TypeMetaFieldInfo>> compatibleTypeMetaFields)
    {
        Type = type;
        _serializer = serializer;
        StaticTypeId = staticTypeId;
        IsNullableType = isNullableType;
        IsReferenceTrackableType = isReferenceTrackableType;
        DefaultObject = defaultObject;
        _isNoneObject = isNoneObject;
        _writeDataObject = writeDataObject;
        _readDataObject = readDataObject;
        _writeObject = writeObject;
        _readObject = readObject;
        _writeTypeInfo = writeTypeInfo;
        _readTypeInfo = readTypeInfo;
        _compatibleTypeMetaFields = compatibleTypeMetaFields;
    }

    internal static TypeInfo Create<T>(Type type, Serializer<T> serializer)
    {
        return new TypeInfo(
            type,
            serializer,
            serializer.StaticTypeId,
            serializer.IsNullableType,
            serializer.IsReferenceTrackableType,
            serializer.DefaultObject,
            value => serializer.IsNoneObject(value),
            (context, value, hasGenerics) => serializer.WriteDataObject(context, value, hasGenerics),
            context => serializer.ReadDataObject(context),
            (context, value, refMode, writeTypeInfo, hasGenerics) =>
                serializer.WriteObject(context, value, refMode, writeTypeInfo, hasGenerics),
            (context, refMode, readTypeInfo) => serializer.ReadObject(context, refMode, readTypeInfo),
            context => serializer.WriteTypeInfo(context),
            context => serializer.ReadTypeInfo(context),
            trackRef => serializer.CompatibleTypeMetaFields(trackRef));
    }

    public Type Type { get; }

    public TypeId StaticTypeId { get; }

    public bool IsNullableType { get; }

    public bool IsReferenceTrackableType { get; }

    public object? DefaultObject { get; }

    internal Serializer<T> RequireSerializer<T>()
    {
        if (_serializer is Serializer<T> serializer)
        {
            return serializer;
        }

        throw new InvalidDataException($"serializer type mismatch for {typeof(T)}");
    }

    public bool IsNoneObject(object? value)
    {
        return _isNoneObject(value);
    }

    public void WriteDataObject(WriteContext context, object? value, bool hasGenerics)
    {
        _writeDataObject(context, value, hasGenerics);
    }

    public object? ReadDataObject(ReadContext context)
    {
        return _readDataObject(context);
    }

    public void WriteObject(WriteContext context, object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        _writeObject(context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public object? ReadObject(ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return _readObject(context, refMode, readTypeInfo);
    }

    internal void WriteTypeInfo(WriteContext context)
    {
        _writeTypeInfo(context);
    }

    internal void ReadTypeInfo(ReadContext context)
    {
        _readTypeInfo(context);
    }

    internal IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return _compatibleTypeMetaFields(trackRef);
    }

    internal bool IsRegistered { get; private set; }

    internal uint? UserTypeId { get; private set; }

    internal bool RegisterByName { get; private set; }

    internal MetaString? NamespaceName { get; private set; }

    internal MetaString? TypeName { get; private set; }

    internal void RegisterByTypeId(uint userTypeId)
    {
        IsRegistered = true;
        UserTypeId = userTypeId;
        RegisterByName = false;
        NamespaceName = null;
        TypeName = null;
    }

    internal void RegisterByTypeName(MetaString namespaceName, MetaString typeName)
    {
        IsRegistered = true;
        UserTypeId = null;
        RegisterByName = true;
        NamespaceName = namespaceName;
        TypeName = typeName;
    }

    internal void CopyRegistrationFrom(TypeInfo source)
    {
        IsRegistered = source.IsRegistered;
        UserTypeId = source.UserTypeId;
        RegisterByName = source.RegisterByName;
        NamespaceName = source.NamespaceName;
        TypeName = source.TypeName;
    }
}
