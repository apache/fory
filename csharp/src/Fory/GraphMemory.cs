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

using System.Runtime.CompilerServices;

namespace Apache.Fory;

internal static class GraphMemory
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal static long ValueOwnerBytes<T>() => ValueOwner<T>.Bytes;

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal static void ReserveRootValue<T>(ReadContext context)
    {
        long bytes = ValueOwner<T>.Bytes;
        if (bytes != 0)
        {
            context.ReserveGraphMemory(bytes);
        }
    }

    private static class ValueOwner<T>
    {
        internal static readonly long Bytes = Compute();

        private static long Compute()
        {
            Type type = typeof(T);
            if (!ShouldReserve(type))
            {
                return 0;
            }

            int bytes = Unsafe.SizeOf<T>();
            return bytes == 0 ? 1 : bytes;
        }

        private static bool ShouldReserve(Type type)
        {
            if (!type.IsValueType ||
                Nullable.GetUnderlyingType(type) is not null ||
                type.IsEnum ||
                type.IsPrimitive)
            {
                return false;
            }

            return type != typeof(decimal) &&
                   type != typeof(Half) &&
                   type != typeof(BFloat16) &&
                   type != typeof(DateOnly) &&
                   type != typeof(DateTime) &&
                   type != typeof(DateTimeOffset) &&
                   type != typeof(TimeSpan);
        }
    }
}
