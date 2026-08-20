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
using Fory.InheritanceProviders;

namespace Fory.InheritanceConsumer;

[ForyStruct]
public class CrossAssemblyMiddle : SharedOrdinaryBase
{
    protected override string ProtectedText { get; set; } = string.Empty;

    [ForyField(5)]
    public int MiddleValue { get; set; }
}

[ForyStruct]
public sealed class CrossAssemblyLeaf : CrossAssemblyMiddle
{
    [ForyField(6)]
    public string LeafValue { get; set; } = string.Empty;
}

[ForyStruct]
public class ExternalMiddle : ExternalPrivateDerived
{
    [ForyField(4)]
    public string MiddleValue { get; set; } = string.Empty;
}

[ForyStruct]
public sealed class ExternalLeaf : ExternalMiddle
{
    [ForyField(5)]
    public long LeafValue { get; set; }
}
