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
    internal TypeInfo(Type type, Serializer serializer)
    {
        Type = type;
        Serializer = serializer;
        StaticTypeId = serializer.StaticTypeId;
        IsNullableType = serializer.IsNullableType;
        IsReferenceTrackableType = serializer.IsReferenceTrackableType;
    }

    public Type Type { get; }

    internal Serializer Serializer { get; }

    public TypeId StaticTypeId { get; }

    public bool IsNullableType { get; }

    public bool IsReferenceTrackableType { get; }

    public void WriteObject(WriteContext context, object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        Serializer.WriteObject(context, value, refMode, writeTypeInfo, hasGenerics);
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
