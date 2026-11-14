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

class EncodingTest < Minitest::Test
  def test_varuint64_roundtrip
    values = [0, 1, 127, 128, 300, 16_384, 2**32 - 1, 2**63 - 1]
    values.each do |v|
      buf = Fory::Buffer.new
      Fory::Encoding.write_varuint64(buf, v)
      buf.reset_read!
      assert_equal(v, Fory::Encoding.read_varuint64(buf))
    end
  end

  def test_varint64_roundtrip
    values = [0, 1, -1, 63, -64, 64, -65, 2**31, -(2**31), 2**63 - 1, -(2**63)]
    values.each do |v|
      buf = Fory::Buffer.new
      Fory::Encoding.write_varint64(buf, v)
      buf.reset_read!
      assert_equal(v, Fory::Encoding.read_varint64(buf))
    end
  end

  def test_varuint_malformed_too_long
    buf = Fory::Buffer.wrap("\x80" * 10)
    assert_raises(Fory::Encoding::MalformedError) { Fory::Encoding.read_varuint64(buf, max_bytes: 3) }
  end

  def test_varuint_truncated
    buf = Fory::Buffer.wrap("\x80")
    assert_raises(Fory::Encoding::TruncatedError) { Fory::Encoding.read_varuint64(buf) }
  end

  def test_tagged_int64_roundtrip
    values = [
      -1_073_741_825, -1_073_741_824, -32_768, -129, -128, -1, 0, 1, 127, 128,
      1_073_741_823, 1_073_741_824,
      -(2**63), (2**63) - 1
    ]
    values.each do |v|
      buf = Fory::Buffer.new
      Fory::Encoding.write_tagged_int64(buf, v)
      buf.reset_read!
      assert_equal(v, Fory::Encoding.read_tagged_int64(buf))
    end
  end

  def test_varuint36_small_roundtrip
    values = [0, 1, 127, 128, 16_384, 0xFFFF_FFFFF]
    values.each do |v|
      buf = Fory::Buffer.new
      Fory::Encoding.write_varuint36_small(buf, v)
      buf.reset_read!
      assert_equal(v, Fory::Encoding.read_varuint36_small(buf))
    end
  end

  def test_varuint36_small_truncated
    # First byte indicates multi-byte varint but no continuation bytes.
    buf = Fory::Buffer.wrap("\x80")
    assert_raises(Fory::Encoding::TruncatedError) { Fory::Encoding.read_varuint36_small(buf) }
  end

  def test_string_header
    buf = Fory::Buffer.new
    Fory::Encoding.write_string_utf8_header(buf, 123)
    buf.reset_read!
    len, enc = Fory::Encoding.read_string_header(buf)
    assert_equal(123, len)
    assert_equal(Fory::Encoding::UTF8, enc)
  end

  def test_varuint36_small_golden
    cases = {
      0 => ["00"],
      1 => ["01"],
      127 => ["7F"],
      128 => ["80", "01"],
      16_384 => ["80", "80", "01"],
    }

    cases.each do |value, expected_hex|
      buf = Fory::Buffer.new
      Fory::Encoding.write_varuint36_small(buf, value)
      assert_equal(expected_hex, buf.bytes.bytes.map { |b| "%02X" % b })

      buf.reset_read!
      assert_equal(value, Fory::Encoding.read_varuint36_small(buf))
    end
  end

  def test_tagged_int64_golden
    buf = Fory::Buffer.new
    Fory::Encoding.write_tagged_int64(buf, 1)
    assert_equal(["02", "00", "00", "00"], buf.bytes.bytes.map { |b| "%02X" % b })

    buf = Fory::Buffer.new
    Fory::Encoding.write_tagged_int64(buf, 1_073_741_824)
    assert_equal(
      ["01", "00", "00", "00", "40", "00", "00", "00", "00"],
      buf.bytes.bytes.map { |b| "%02X" % b }
    )
  end

  def test_string_header_golden_utf8

    buf = Fory::Buffer.new
    Fory::Encoding.write_string_utf8_header(buf, 0)
    assert_equal(["02"], buf.bytes.bytes.map { |b| "%02X" % b })

    buf = Fory::Buffer.new
    Fory::Encoding.write_string_utf8_header(buf, 1)
    assert_equal(["06"], buf.bytes.bytes.map { |b| "%02X" % b })

    buf = Fory::Buffer.new
    Fory::Encoding.write_string_utf8_header(buf, 128)
    assert_equal(["82", "04"], buf.bytes.bytes.map { |b| "%02X" % b })
  end
end
