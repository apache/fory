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

using System.Buffers.Binary;

namespace Apache.Fory;

public static class MurmurHash3
{
    public static (ulong H1, ulong H2) X64_128(ReadOnlySpan<byte> bytes, ulong seed = 47)
    {
        const ulong c1 = 0x87c37b91114253d5;
        const ulong c2 = 0x4cf5ad432745937f;

        ulong h1 = seed;
        ulong h2 = seed;

        int length = bytes.Length;
        int nblocks = length / 16;
        for (int i = 0; i < nblocks; i++)
        {
            int offset = i * 16;
            ulong k1 = BinaryPrimitives.ReadUInt64LittleEndian(bytes.Slice(offset, 8));
            ulong k2 = BinaryPrimitives.ReadUInt64LittleEndian(bytes.Slice(offset + 8, 8));

            k1 *= c1;
            k1 = RotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;

            h1 = RotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= c2;
            k2 = RotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;

            h2 = RotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        ulong tk1 = 0;
        ulong tk2 = 0;
        int tailStart = nblocks * 16;
        ReadOnlySpan<byte> tail = bytes.Slice(tailStart);
        switch (length & 15)
        {
            case 15:
                tk2 ^= (ulong)tail[14] << 48;
                goto case 14;
            case 14:
                tk2 ^= (ulong)tail[13] << 40;
                goto case 13;
            case 13:
                tk2 ^= (ulong)tail[12] << 32;
                goto case 12;
            case 12:
                tk2 ^= (ulong)tail[11] << 24;
                goto case 11;
            case 11:
                tk2 ^= (ulong)tail[10] << 16;
                goto case 10;
            case 10:
                tk2 ^= (ulong)tail[9] << 8;
                goto case 9;
            case 9:
                tk2 ^= tail[8];
                tk2 *= c2;
                tk2 = RotateLeft(tk2, 33);
                tk2 *= c1;
                h2 ^= tk2;
                goto case 8;
            case 8:
                tk1 ^= (ulong)tail[7] << 56;
                goto case 7;
            case 7:
                tk1 ^= (ulong)tail[6] << 48;
                goto case 6;
            case 6:
                tk1 ^= (ulong)tail[5] << 40;
                goto case 5;
            case 5:
                tk1 ^= (ulong)tail[4] << 32;
                goto case 4;
            case 4:
                tk1 ^= (ulong)tail[3] << 24;
                goto case 3;
            case 3:
                tk1 ^= (ulong)tail[2] << 16;
                goto case 2;
            case 2:
                tk1 ^= (ulong)tail[1] << 8;
                goto case 1;
            case 1:
                tk1 ^= tail[0];
                tk1 *= c1;
                tk1 = RotateLeft(tk1, 31);
                tk1 *= c2;
                h1 ^= tk1;
                break;
        }

        h1 ^= (ulong)length;
        h2 ^= (ulong)length;
        h1 += h2;
        h2 += h1;
        h1 = Fmix64(h1);
        h2 = Fmix64(h2);
        h1 += h2;
        h2 += h1;
        return (h1, h2);
    }

    private static ulong RotateLeft(ulong x, int r)
    {
        return (x << r) | (x >> (64 - r));
    }

    private static ulong Fmix64(ulong x)
    {
        x ^= x >> 33;
        x *= 0xff51afd7ed558ccd;
        x ^= x >> 33;
        x *= 0xc4ceb9fe1a85ec53;
        x ^= x >> 33;
        return x;
    }
}
