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

import { OwnershipError } from '../packages/fory/lib/error';
import { BinaryWriter } from '../packages/fory/lib/writer';
import { describe, expect, test } from '@jest/globals';

describe('writer', () => {
    test('should dumpOwn dispose work', () => {
        const writer = new BinaryWriter({});
        {
            writer.writeUint8(256);
            const { get, dispose } = writer.dumpAndOwn();
            const ab = get();
            expect(ab.byteLength).toBe(1);
            expect(ab[0]).toBe(0);
            expect(writer.writeGetCursor()).toBe(1);
            dispose();
        }
        writer.reset();
        {
            writer.writeUint8(256);
            const { get, dispose } = writer.dumpAndOwn();
            const ab = get();
            expect(ab.byteLength).toBe(1);
            expect(ab[0]).toBe(0);
            expect(writer.writeGetCursor()).toBe(1);
            dispose();
        }
    });

    test('should dumpOwn work', () => {
        const writer = new BinaryWriter({});
        {
            writer.writeUint8(256);
            const { get } = writer.dumpAndOwn();
            const ab = get();
            expect(ab.byteLength).toBe(1);
            expect(ab[0]).toBe(0);
            expect(writer.writeGetCursor()).toBe(1);
        }
        try {
            writer.reset();
        } catch (error) {
            expect(error instanceof OwnershipError).toBe(true);
            return;
        }
        throw new Error("unreachable code")
    });
});