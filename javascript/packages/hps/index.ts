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

interface Hps {
  serializeString: (dist: Uint8Array, str: string, offset: number, maxLength: number) => number;
}

const build = () => {
  try {
    const hps: Hps = require("bindings")("hps.node");
    const { serializeString: _serializeString } = hps;

    return {
      serializeString: (v: string, dist: Uint8Array, offset: number) => {
        if (typeof v !== "string") {
          throw new Error(`isLatin1 requires string but got ${typeof v}`);
        }
        // The native writer copies without bounds checks: a 5-byte varint header
        // plus at most 2 bytes per UTF-16 code unit must fit in `dist`.
        if (offset + 5 + v.length * 2 > dist.byteLength) {
          throw new RangeError(
            `serializeString needs up to ${5 + v.length * 2} bytes at offset ${offset} but buffer length is ${dist.byteLength}`,
          );
        }
        return _serializeString(dist, v, offset, 0);
      },
    };
  } catch {
    return null;
  }
};

export default build();
