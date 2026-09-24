# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

module Fory
  module Encoding
    class Error < StandardError; end
    class MalformedError < Error; end
    class TruncatedError < Error; end

    module_function

    def zigzag_encode_i64(n)
      ((n << 1) ^ (n >> 63)) & 0xFFFF_FFFF_FFFF_FFFF
    end

    def zigzag_decode_i64(u)
      ((u >> 1) ^ -(u & 1))
    end

    def write_varuint64(buf, value)
      v = value & 0xFFFF_FFFF_FFFF_FFFF
      while v >= 0x80
        buf.write_uint8((v & 0x7F) | 0x80)
        v >>= 7
      end
      buf.write_uint8(v)
    end

    def read_varuint64(buf, max_bytes: 10)
      shift = 0
      result = 0
      i = 0
      while i < max_bytes
        raise TruncatedError, "truncated varuint" if buf.remaining < 1
        b = buf.read_uint8
        result |= ((b & 0x7F) << shift)
        return result if (b & 0x80) == 0
        shift += 7
        i += 1
      end
      raise MalformedError, "varuint too long"
    end

    def write_varint64(buf, value)
      write_varuint64(buf, zigzag_encode_i64(value))
    end

    def read_varint64(buf)
      zigzag_decode_i64(read_varuint64(buf))
    end


    def write_tagged_int64(buf, value)
      if value >= -1_073_741_824 && value <= 1_073_741_823
        buf.write_int32_le(value << 1)
      else
        buf.write_uint8(0x01)
        buf.write_int64_le(value)
      end
    end

    def read_tagged_int64(buf)
      raise TruncatedError, "truncated tagged int64" if buf.remaining < 4

      b0 = buf.read_uint8
      b1 = buf.read_uint8
      b2 = buf.read_uint8
      b3 = buf.read_uint8
      u32 = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)

      if (u32 & 1) == 0
        i32 = u32 >= 0x8000_0000 ? u32 - 0x1_0000_0000 : u32
        (i32 >> 1)
      else
        raise TruncatedError, "truncated tagged int64" if buf.remaining < 5
        buf.instance_variable_set(:@read_pos, buf.read_pos - 3)
        buf.read_int64_le
      end
    end

    def write_varuint36_small(buf, value)
      raise ArgumentError, "value must be >= 0" if value < 0
      raise ArgumentError, "value too large for varuint36" if value > 0xFFFF_FFFFF

      if value < 0x80
        buf.write_uint8(value)
      else
        write_varuint64(buf, value)
      end
    end

    def read_varuint36_small(buf)
      raise TruncatedError, "truncated varuint36" if buf.remaining < 1

      b0 = buf.read_uint8
      if (b0 & 0x80) == 0
        v = b0
      else
        buf.instance_variable_set(:@read_pos, buf.read_pos - 1)
        v = read_varuint64(buf)
      end

      raise MalformedError, "varuint36 overflow" if v > 0xFFFF_FFFFF
      v
    end

    UTF8 = 2

    def write_string_utf8_header(buf, byte_length)
      header = (byte_length << 2) | UTF8
      write_varuint36_small(buf, header)
    end

    def read_string_header(buf)
      header = read_varuint36_small(buf)
      encoding = header & 0x3
      byte_length = header >> 2
      [byte_length, encoding]
    end
  end
end
