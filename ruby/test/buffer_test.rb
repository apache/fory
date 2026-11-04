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

require_relative "test_helper"

class BufferTest < Minitest::Test
  def test_le_roundtrip
    buf = Fory::Buffer.new
    buf.write_int8(-1)
    buf.write_int16_le(-2)
    buf.write_int32_le(-3)
    buf.write_int64_le(-4)

    buf.reset_read!
    assert_equal(-1, buf.read_int8)
    assert_equal(-2, buf.read_int16_le)
    assert_equal(-3, buf.read_int32_le)
    assert_equal(-4, buf.read_int64_le)
  end

  def test_underflow
    buf = Fory::Buffer.wrap("\x01")
    buf.read_uint8
    assert_raises(Fory::Buffer::UnderflowError) { buf.read_uint8 }
  end

  def test_grows
    buf = Fory::Buffer.new(0)
    100.times { buf.write_uint8(0xAA) }
    assert_equal(100, buf.bytes.bytesize)
  end
end
