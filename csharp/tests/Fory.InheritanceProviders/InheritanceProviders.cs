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

using Apache.Fory;
using Fory.ExternalTypes;

namespace Fory.InheritanceProviders;

[ForyStruct]
public abstract class SharedOrdinaryBase
{
    [ForyField(1)]
    private int _privateValue;

    [ForyField(2)]
    protected abstract string ProtectedText { get; set; }

    [ForyField(3)]
    public long PublicBaseValue;

    [ForyField(4)]
    internal int InternalBaseValue;

    private readonly int _cache = 37;

    public void SetBaseState(
        int privateValue,
        string protectedText,
        int internalBaseValue)
    {
        _privateValue = privateValue;
        ProtectedText = protectedText;
        InternalBaseValue = internalBaseValue;
    }

    public (
        int PrivateValue,
        string ProtectedText,
        int InternalBaseValue,
        int Cache) ReadBaseState()
    {
        return (_privateValue, ProtectedText, InternalBaseValue, _cache);
    }
}

[ForyStruct(Target = typeof(ExternalPrivateDerived), BaseOnly = true)]
public abstract class SharedExternalHierarchy
{
    [ForyField(
        1,
        TargetDeclaringType = typeof(ExternalPrivateBase),
        TargetMemberName = "_identifier")]
    public abstract long Identifier { get; }

    [ForyField(
        2,
        TargetDeclaringType = typeof(ExternalPrivateBase),
        TargetMemberName = "<Secret>k__BackingField")]
    public abstract string Secret { get; }

    [ForyField(
        Ignore = true,
        TargetDeclaringType = typeof(ExternalPrivateBase),
        TargetMemberName = "_cache")]
    public abstract int CacheStorage { get; }

    [ForyField(3)]
    public abstract int PublicValue { get; }
}
