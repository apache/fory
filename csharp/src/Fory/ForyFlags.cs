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

public enum RefFlag : sbyte
{
    Null = -3,
    Ref = -2,
    NotNullValue = -1,
    RefValue = 0,
}

public enum RefMode : byte
{
    None = 0,
    NullOnly = 1,
    Tracking = 2,
}

internal static class RefModeExtensions
{
    public static RefMode From(bool nullable, bool trackRef)
    {
        if (trackRef)
        {
            return RefMode.Tracking;
        }

        return nullable ? RefMode.NullOnly : RefMode.None;
    }
}

public static class ForyHeaderFlag
{
    public const byte IsNull = 0x01;
    public const byte IsXlang = 0x02;
    public const byte IsOutOfBand = 0x04;
}

