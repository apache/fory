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

[ForyStruct]
public abstract class XlangOrdinaryBase
{
    [ForyField(1)]
    private int _identifier;

    [ForyField(2)]
    public string Name { get; set; } = string.Empty;

    public int Identifier => _identifier;

    public void SetIdentifier(int identifier)
    {
        _identifier = identifier;
    }
}

[ForyStruct]
public sealed class XlangOrdinaryLeaf : XlangOrdinaryBase
{
    [ForyField(3)]
    public long Score { get; set; }
}

[ForyStruct]
public sealed class XlangExternalLeaf : ExternalPrivateDerived
{
    [ForyField(4)]
    public string LeafName { get; set; } = string.Empty;
}
