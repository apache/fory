# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
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
  # growable byte buffer with independent read/write cursors.
  # this is a prototype-level implementation to support encoding work.
  class Buffer
    class Error < StandardError; end
    class UnderflowError < Error; end

    attr_reader :read_pos, :write_pos

    def initialize(initial_capacity = 64)
      raise ArgumentError, "initial_capacity must be >= 0" if initial_capacity < 0

      @bytes = String.new(capacity: initial_capacity, encoding: ::Encoding::BINARY)
      @read_pos = 0
      @write_pos = 0
    end

    def self.wrap(bytes)
      buf = new(0)
      buf.replace_bytes(bytes)
      buf
    end

    def replace_bytes(bytes)
      @bytes = bytes.dup.force_encoding(::Encoding::BINARY)
      @read_pos = 0
      @write_pos = @bytes.bytesize
      self
    end

    def bytes
      @bytes.byteslice(0, @write_pos)
    end

    def remaining
      @write_pos - @read_pos
    end

    def reset_read!
      @read_pos = 0
    end

    def clear!
      @read_pos = 0
      @write_pos = 0
      self
    end

    def ensure_capacity(extra)
      needed = @write_pos + extra
      return if needed <= @bytes.bytesize

      new_size = [@bytes.bytesize, 1].max
      new_size *= 2 while new_size < needed
      @bytes << "\x00" * (new_size - @bytes.bytesize)
    end

    def write_bytes(str)
      str = str.dup.force_encoding(::Encoding::BINARY)
      ensure_capacity(str.bytesize)
      @bytes.setbyte(@write_pos, str.getbyte(0)) if str.bytesize == 1
      @bytes[@write_pos, str.bytesize] = str
      @write_pos += str.bytesize
      self
    end

    def read_bytes(n)
      raise ArgumentError, "n must be >= 0" if n < 0
      raise UnderflowError, "need #{n} bytes, have #{remaining}" if remaining < n

      out = @bytes.byteslice(@read_pos, n)
      @read_pos += n
      out
    end

    def write_uint8(v)
      ensure_capacity(1)
      @bytes.setbyte(@write_pos, v & 0xFF)
      @write_pos += 1
      self
    end

    def read_uint8
      raise UnderflowError, "need 1 byte, have #{remaining}" if remaining < 1

      b = @bytes.getbyte(@read_pos)
      @read_pos += 1
      b
    end

    def write_int8(v)
      write_uint8(v)
    end

    def read_int8
      v = read_uint8
      v >= 0x80 ? v - 0x100 : v
    end

    def write_int16_le(v)
      ensure_capacity(2)
      @bytes.setbyte(@write_pos, v & 0xFF)
      @bytes.setbyte(@write_pos + 1, (v >> 8) & 0xFF)
      @write_pos += 2
      self
    end

    def read_int16_le
      b0 = read_uint8
      b1 = read_uint8
      v = b0 | (b1 << 8)
      v >= 0x8000 ? v - 0x10000 : v
    end

    def write_int32_le(v)
      ensure_capacity(4)
      @bytes.setbyte(@write_pos, v & 0xFF)
      @bytes.setbyte(@write_pos + 1, (v >> 8) & 0xFF)
      @bytes.setbyte(@write_pos + 2, (v >> 16) & 0xFF)
      @bytes.setbyte(@write_pos + 3, (v >> 24) & 0xFF)
      @write_pos += 4
      self
    end

    def read_int32_le
      b0 = read_uint8
      b1 = read_uint8
      b2 = read_uint8
      b3 = read_uint8
      v = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
      v >= 0x8000_0000 ? v - 0x1_0000_0000 : v
    end

    def write_int64_le(v)
      ensure_capacity(8)
      8.times do |i|
        @bytes.setbyte(@write_pos + i, (v >> (8 * i)) & 0xFF)
      end
      @write_pos += 8
      self
    end

    def read_int64_le
      bytes = read_bytes(8).bytes
      v = 0
      bytes.each_with_index { |b, i| v |= (b << (8 * i)) }
      # interpret as signed 64-bit
      v >= 0x8000_0000_0000_0000 ? v - 0x1_0000_0000_0000_0000 : v
    end
  end
end
