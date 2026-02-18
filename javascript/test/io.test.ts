/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import { fromUint8Array } from '../packages/fory/lib/platformBuffer';
import { BinaryReader } from '../packages/fory/lib/reader';
import { Config, RefFlags } from '../packages/fory/lib/type';
import { BinaryWriter } from '../packages/fory/lib/writer';
import { describe, expect, test } from '@jest/globals';


function num2Bin(num: number) {
    return (num >>> 0).toString(2);
}

[
    {
        useSliceString: true,
    }
].forEach((config: Config) => {
    describe('writer', () => {
        test('should unsigned int work', () => {
            const writer = new BinaryWriter(config);
            let len = 0;
            [
                8,
                16,
                32,
            ].forEach((x, y) => {
                {
                    writer[`writeUint${x}`](10);
                    len += x / 8;
                    var ab = writer.dump();
                    expect(ab.byteLength).toBe(len);
                    if (x === 64) {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getBigUint${x}`](writer.writeGetCursor() - x / 8, true)).toBe(BigInt(10));
                    } else {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getUint${x}`](writer.writeGetCursor() - x / 8, true)).toBe(10);
                    }
                    expect(writer.writeGetCursor()).toBe(len);
                }

                {
                    writer[`writeUint${x}`](-1);
                    len += x / 8;
                    var ab = writer.dump();
                    expect(ab.byteLength).toBe(len);
                    if (x === 64) {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getBigUint${x}`](writer.writeGetCursor() - x / 8, true)).toBe(BigInt(2 ** x) - BigInt(1));
                    } else {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getUint${x}`](writer.writeGetCursor() - x / 8, true)).toBe(2 ** x - 1);
                    }
                    expect(writer.writeGetCursor()).toBe(len);
                }

                {
                    writer[`writeUint${x}`](2 ** x);
                    len += x / 8;
                    var ab = writer.dump();
                    expect(ab.byteLength).toBe(len);
                    if (x === 64) {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getBigUint${x}`](writer.writeGetCursor() - x / 8, true)).toBe(BigInt(0));
                    } else {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getUint${x}`](writer.writeGetCursor() - x / 8, true)).toBe(0);
                    }
                    expect(writer.writeGetCursor()).toBe(len);
                }
            })


        });

        test('should int work', () => {
            const writer = new BinaryWriter(config);
            let len = 0;
            [
                8,
                16,
                32,
            ].forEach((x, y) => {
                {
                    writer[`writeInt${x}`](10);
                    len += x / 8;
                    var ab = writer.dump();
                    expect(ab.byteLength).toBe(len);
                    if (x === 64) {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getBigInt${x}`](writer.writeGetCursor() - x / 8, true)).toBe(BigInt(10));
                    } else {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getInt${x}`](writer.writeGetCursor() - x / 8, true)).toBe(10);
                    }
                    expect(writer.writeGetCursor()).toBe(len);
                }

                {
                    writer[`writeInt${x}`](2 ** x);
                    len += x / 8;
                    var ab = writer.dump();
                    expect(ab.byteLength).toBe(len);
                    if (x === 64) {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getBigInt${x}`](writer.writeGetCursor() - x / 8, true)).toBe(BigInt(0));
                    } else {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getInt${x}`](writer.writeGetCursor() - x / 8, true)).toBe(0);
                    }
                    expect(writer.writeGetCursor()).toBe(len);
                }

                {
                    writer[`writeInt${x}`](-1);
                    len += x / 8;
                    var ab = writer.dump();
                    expect(ab.byteLength).toBe(len);
                    if (x === 64) {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getBigInt${x}`](writer.writeGetCursor() - x / 8, true)).toBe(BigInt(-1));
                    } else {
                        expect(new DataView(ab.buffer, ab.byteOffset)[`getInt${x}`](writer.writeGetCursor() - x / 8, true)).toBe(-1);
                    }
                    expect(writer.writeGetCursor()).toBe(len);
                }
            })
        });

        test('should varUInt32 work', () => {
            [
                1,
                2,
                3,
                4,
            ].forEach(x => {
                {
                    const writer = new BinaryWriter(config);
                    const value = (2 ** (x * 7)) - 1;
                    writer.writeVarUInt32(value);
                    const ab = writer.dump();
                    expect(ab.byteLength).toBe(x);
                    for (let index = 0; index < ab.byteLength - 1; index++) {
                        expect(num2Bin(ab[index])).toBe('11111111');
                    }
                    expect(num2Bin(ab[ab.byteLength - 1])).toBe('1111111');
                    const reader = new BinaryReader(config);
                    reader.reset(ab);
                    const vari32 = reader.readVarUInt32();
                    expect(vari32).toBe(value);
                }
                {
                    const writer = new BinaryWriter(config);
                    const value = (2 ** (x * 7));
                    writer.writeVarUInt32(value);
                    const ab = writer.dump();
                    expect(ab.byteLength).toBe(x + 1);
                    for (let index = 0; index < ab.byteLength - 1; index++) {
                        expect(num2Bin(ab[index])).toBe('10000000');
                    }
                    expect(num2Bin(ab[ab.byteLength - 1])).toBe('1');
                    const reader = new BinaryReader(config);
                    reader.reset(ab);
                    const vari32 = reader.readVarUInt32();
                    expect(vari32).toBe(value);
                }
            });
        });

        test('should varInt32 work', () => {
            const writer = new BinaryWriter(config);
            const value = -1;
            writer.writeVarInt32(value);
            const ab = writer.dump();
            expect(ab.byteLength).toBe(1);
            expect(num2Bin(ab[0])).toBe('1');
            const reader = new BinaryReader(config);
            reader.reset(ab);
            const vari32 = reader.readVarInt32();
            expect(vari32).toBe(value);
        });

        test('should short latin1 string work', () => {
            const writer = new BinaryWriter(config);
            writer.stringWithHeader("hello world");
            const ab = writer.dump();
            const reader = new BinaryReader(config);
            reader.reset(ab);
            const header = reader.readVarUint36Small();
            expect(header & 0b11).toBe(0);
            const len = header >>> 2;
            expect(len).toBe(11);
            const str = reader.stringLatin1(11);
            expect(str).toBe("hello world");
        });

        test('should long latin1 string work', () => {
            const writer = new BinaryWriter(config);
            const str = new Array(10).fill('hello world').join('');
            writer.stringWithHeader(str);
            const ab = writer.dump();
            const reader = new BinaryReader(config);
            reader.reset(ab);
            const header = reader.readVarUint36Small();
            expect(header & 0b11).toBe(0);
            const len = header >>> 2;
            expect(len).toBe(110);
            expect(reader.stringLatin1(len)).toBe(str);
        });

        test('should short utf8 string work', () => {
            const writer = new BinaryWriter(config);
            const str = new Array(1).fill('hello 你好 😁').join('');
            writer.stringWithHeader(str);
            const ab = writer.dump();
            const reader = new BinaryReader(config);

            {
                reader.reset(ab);
                const header = reader.readVarUint36Small();
                expect(header & 0b11).toBe(2);
                const len = header >>> 2;
                expect(len).toBe(17);
                expect(reader.stringUtf8(len)).toBe(str);
            }
            {
                reader.reset(ab);
                expect(reader.stringWithHeader()).toBe(str);
            }
        });

        test('should long utf8 string work', () => {
            const writer = new BinaryWriter(config);
            const str = new Array(10).fill('hello 你好 😁').join('');
            writer.stringWithHeader(str);
            const ab = writer.dump();
            const reader = new BinaryReader(config);
            {
                reader.reset(ab);
                const header = reader.readVarUint36Small();
                expect(header & 0b11).toBe(2);
                const len = header >>> 2;
                expect(len).toBe(170);
                expect(reader.stringUtf8(len)).toBe(str);
            }
            {
                reader.reset(ab);
                expect(reader.stringWithHeader()).toBe(str);
            }
        });

        test('should buffer work', () => {
            const writer = new BinaryWriter(config);
            writer.buffer(new Uint8Array([1, 2, 3, 4, 5]));
            const ab = writer.dump();
            const reader = new BinaryReader(config);
            reader.reset(ab);
            expect(ab.byteLength).toBe(5);
            expect(ab[0]).toBe(1);
            expect(ab[1]).toBe(2);
            expect(ab[2]).toBe(3);
            expect(ab[3]).toBe(4);
            expect(ab[4]).toBe(5);
        });

        test('should bufferWithoutMemCheck work', () => {
            const writer = new BinaryWriter(config);
            writer.bufferWithoutMemCheck(fromUint8Array(new Uint8Array([1, 2, 3, 4, 5])), 5);
            const ab = writer.dump();
            const reader = new BinaryReader(config);
            reader.reset(ab);
            expect(ab.byteLength).toBe(5);
            expect(ab[0]).toBe(1);
            expect(ab[1]).toBe(2);
            expect(ab[2]).toBe(3);
            expect(ab[3]).toBe(4);
            expect(ab[4]).toBe(5);
        });

        test('should setUint32Position work', () => {
            const writer = new BinaryWriter(config);
            writer.writeSkip(10);
            writer.setUint32Position(0, 100);
            writer.setUint32Position(5, 100);
            const ab = writer.dump();
            expect(ab.byteLength).toBe(10);
            expect(ab[0]).toBe(100);
            expect(ab[5]).toBe(100);
        });

        test('should float work', () => {
            const writer = new BinaryWriter(config);
            writer.writeFloat32(10.01);
            const ab = writer.dump();
            expect(ab.byteLength).toBe(4);
            const reader = new BinaryReader(config);
            reader.reset(ab);
            expect(reader.readFloat32().toFixed(2)).toBe((10.01).toFixed(2));
        });

        test('should float64 work', () => {
            const writer = new BinaryWriter(config);
            writer.writeFloat64(10.01);
            const ab = writer.dump();
            expect(ab.byteLength).toBe(8);
            const reader = new BinaryReader(config);
            reader.reset(ab);
            expect(reader.readFloat64().toFixed(2)).toBe((10.01).toFixed(2));
        });

        test('should reserve work', () => {
            const writer = new BinaryWriter(config);
            const byteLength = writer.getByteLen();
            const cursor = writer.writeGetCursor();
            const reserved = writer.getReserved();
            writer.reserve(10);

            expect(writer.getReserved()).toBe(reserved + 10);
            expect(writer.getByteLen()).toBe(byteLength);
            expect(writer.writeGetCursor()).toBe(cursor);
        });

        test('should reserve work', () => {
            const writer = new BinaryWriter(config);
            const byteLength = writer.getByteLen();
            const cursor = writer.writeGetCursor();
            const reserved = writer.getReserved();
            writer.reserve(1024 * 101);

            expect(writer.getReserved()).toBe(reserved + 1024 * 101);
            expect(writer.getByteLen()).toBe(byteLength * 2 + 1024 * 101);
            expect(writer.writeGetCursor()).toBe(cursor);
        });

        test('should reset work', () => {
            const writer = new BinaryWriter(config);
            writer.writeInt16(100);
            writer.reset();
            expect(writer.writeGetCursor()).toBe(0);
            expect(writer.getReserved()).toBe(0);
        });

        test('should int24 work', () => {
            const writer = new BinaryWriter(config);
            writer.writeInt24( (20 << 8)  | 10);
            const ab = writer.dump();
            const reader = new BinaryReader({});
            reader.reset(ab)
            expect(reader.readInt8()).toBe(10);
            expect(reader.readInt16()).toBe(20);
        });

        test('should varUInt64 work', () => {
            const writer = new BinaryWriter(config);
            writer.writeVarUInt64(2n ** 2n);
            const ab = writer.dump();
            const reader = new BinaryReader({});
            reader.reset(ab)
            expect(reader.readVarUInt64()).toBe(2n ** 2n);
        });

        test('should varUInt64 work', () => {
            const writer = new BinaryWriter(config);
            writer.writeVarUInt64(2n ** 63n);
            const ab = writer.dump();
            const reader = new BinaryReader({});
            reader.reset(ab)
            expect(reader.readVarUInt64()).toBe(2n ** 63n);
        });

        test('should varInt64 work', () => {
            const writer = new BinaryWriter(config);
            writer.writeVarInt64(2n ** 2n);
            const ab = writer.dump();
            const reader = new BinaryReader({});
            reader.reset(ab)
            expect(reader.readVarInt64()).toBe(2n ** 2n);
        });

        test('should varInt64 work', () => {
            const writer = new BinaryWriter(config);
            writer.writeVarInt64(2n ** 62n);
            const ab = writer.dump();
            const reader = new BinaryReader({});
            reader.reset(ab)
            expect(reader.readVarInt64()).toBe(2n ** 62n);
        });

        test('should silong work', () => {
            const writer = new BinaryWriter(config);
            writer.writeSliInt64(2n ** 2n);
            const ab = writer.dump();
            const reader = new BinaryReader({});
            reader.reset(ab)
            expect(reader.readSliInt64()).toBe(2n ** 2n);
        });

        test('should silong work', () => {
            const writer = new BinaryWriter(config);
            writer.writeSliInt64(2n ** 62n);
            const ab = writer.dump();
            const reader = new BinaryReader({});
            reader.reset(ab)
            expect(reader.readSliInt64()).toBe(2n ** 62n);
        });
    });
})
