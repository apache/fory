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

namespace Apache.Fory.XlangPeer;

[ForyStruct(Target = typeof(XlangUser))]
internal abstract class XlangUserSerializer
{
    [ForyField(
        1,
        TargetDeclaringType = typeof(XlangUser),
        TargetMemberName = "<Id>k__BackingField")]
    public abstract int Id { get; }

    [ForyField(
        2,
        TargetDeclaringType = typeof(XlangUser),
        TargetMemberName = "<Name>k__BackingField")]
    public abstract string Name { get; }
}

[ForyStruct(Target = typeof(XlangPoint))]
internal abstract class XlangPointSerializer
{
    [ForyField(1)]
    public abstract int X { get; }

    [ForyField(2)]
    public abstract int Y { get; }
}

[ForyEnum(Target = typeof(XlangStatus))]
internal static class XlangStatusSerializer
{
}

[ForyStruct(Target = typeof(XlangHolder))]
internal abstract class XlangHolderSerializer
{
    [ForyField(1)]
    public abstract List<XlangUser> Users { get; }

    [ForyField(2)]
    public abstract Dictionary<string, XlangUser> UsersByName { get; }

    [ForyField(
        Ignore = true,
        TargetDeclaringType = typeof(XlangHolder),
        TargetMemberName = "<Users>k__BackingField")]
    public abstract List<XlangUser> UsersStorage { get; }

    [ForyField(
        Ignore = true,
        TargetDeclaringType = typeof(XlangHolder),
        TargetMemberName = "<UsersByName>k__BackingField")]
    public abstract Dictionary<string, XlangUser> UsersByNameStorage { get; }
}
