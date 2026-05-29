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

public sealed class UnknownCase
{
    public UnknownCase(int caseId, object? value)
        : this(caseId, (uint)global::Apache.Fory.TypeId.Unknown, value)
    {
    }

    internal UnknownCase(int caseId, uint typeId, object? value)
    {
        CaseId = caseId;
        TypeId = typeId;
        Value = value;
    }

    public int CaseId { get; }

    public object? Value { get; }

    // Keep resolver TypeInfo out of the carrier. It is bound to one TypeResolver/Fory instance,
    // so the cross-runtime carrier stores only the stable wire type id plus the polymorphic value.
    internal uint TypeId { get; }

    public T? Downcast<T>()
    {
        return Value is T typed ? typed : default;
    }

    internal static UnknownCase FromRuntime(int caseId, uint typeId, object? value)
    {
        return new UnknownCase(caseId, typeId, value);
    }

    public override string ToString()
    {
        return $"UnknownCase{{caseId={CaseId}, valueType={Value?.GetType().FullName ?? "null"}}}";
    }
}
