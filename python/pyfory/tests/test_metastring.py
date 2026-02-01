# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import pytest
from pyfory.meta.metastring import (
    MetaStringEncoder,
    MetaStringDecoder,
    Encoding,
    EXTENDED_ENCODING_NEGATIVE_NUMBER_STRING,
    EXTENDED_ENCODING_NUMBER_STRING,
    EXTENDED_ENCODING_UTF8,
)


def test_encode_metastring_lower_special():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    # Test for encoding and decoding
    encoded = encoder._encode_lower_special("abc_def")
    assert len(encoded) == 5
    assert len(encoder.encode("org.apache.fory.benchmark.data").encoded_data) == 19
    assert len(encoder.encode("MediaContent").encoded_data) == 9
    decoded = decoder.decode(encoded, Encoding.LOWER_SPECIAL)
    assert decoded == "abc_def"

    for i in range(128):
        builder = "".join(chr(ord("a") + j % 26) for j in range(i))
        encoded = encoder._encode_lower_special(builder)
        decoded = decoder.decode(encoded, Encoding.LOWER_SPECIAL)
        assert decoded == builder


def test_encode_metastring_lower_upper_digit_special():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    # Test for encoding and decoding
    encoded = encoder._encode_lower_upper_digit_special("ExampleInput123")
    assert len(encoded) == 12
    decoded = decoder.decode(encoded, Encoding.LOWER_UPPER_DIGIT_SPECIAL)
    assert decoded == "ExampleInput123"

    for i in range(1, 128):
        string = create_string(i)
        encoded = encoder._encode_lower_upper_digit_special(string)
        decoded = decoder.decode(encoded, Encoding.LOWER_UPPER_DIGIT_SPECIAL)
        assert decoded == string, f"Failed at {i}"


def create_string(length):
    chars = []
    for j in range(length):
        n = j % 64
        if n < 26:
            chars.append(chr(ord("a") + n))
        elif n < 52:
            chars.append(chr(ord("A") + (n - 26)))
        elif n < 62:
            chars.append(chr(ord("0") + (n - 52)))
        elif n == 62:
            chars.append(".")
        else:
            chars.append("_")
    return "".join(chars)


def test_metastring():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    for i in range(1, 128):
        try:
            string = create_string(i)
            metastring = encoder.encode(string)
            assert metastring.encoding != Encoding.EXTENDED
            assert metastring.original == string

            new_string = decoder.decode(metastring.encoded_data, metastring.encoding)
            assert new_string == string
        except Exception as e:
            pytest.fail(f"Failed at {i} with exception: {str(e)}")


def test_encode_empty_string():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    for encoding in [
        Encoding.LOWER_SPECIAL,
        Encoding.LOWER_UPPER_DIGIT_SPECIAL,
        Encoding.FIRST_TO_LOWER_SPECIAL,
        Encoding.ALL_TO_LOWER_SPECIAL,
        Encoding.EXTENDED,
    ]:
        metastring = encoder.encode_with_encoding("", encoding)
        assert len(metastring.encoded_data) == 0
        decoded = decoder.decode(metastring.encoded_data, metastring.encoding)
        assert decoded == ""


def test_encode_characters_outside_of_lower_special():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")

    test_string = "abcdefABCDEF1234!@#"
    metastring = encoder.encode(test_string)
    assert metastring.encoding == Encoding.EXTENDED
    assert metastring.encoded_data[0] == EXTENDED_ENCODING_UTF8


def test_all_to_upper_special_encoding():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    test_string = "ABC_DEF"
    metastring = encoder.encode(test_string)
    assert metastring.encoding == Encoding.LOWER_UPPER_DIGIT_SPECIAL
    decoded_string = decoder.decode(metastring.encoded_data, metastring.encoding)
    assert decoded_string == test_string


def test_first_to_lower_special_encoding():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    test_string = "Aabcdef"
    metastring = encoder.encode(test_string)
    assert metastring.encoding == Encoding.FIRST_TO_LOWER_SPECIAL
    decoded_string = decoder.decode(metastring.encoded_data, metastring.encoding)
    assert decoded_string == test_string


def test_utf8_encoding():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    test_string = "你好，世界"  # Non-Latin characters
    metastring = encoder.encode(test_string)
    assert metastring.encoding == Encoding.EXTENDED
    assert metastring.encoded_data[0] == EXTENDED_ENCODING_UTF8
    decoded_string = decoder.decode(metastring.encoded_data, metastring.encoding)
    assert decoded_string == test_string


def test_number_string_encoding():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    test_string = "1234567890"
    metastring = encoder.encode(test_string)
    assert metastring.encoding == Encoding.EXTENDED
    assert metastring.encoded_data[0] == EXTENDED_ENCODING_NUMBER_STRING
    decoded_string = decoder.decode(metastring.encoded_data, metastring.encoding)
    assert decoded_string == test_string


def test_negative_number_string_encoding():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    test_string = "-324345545454"
    metastring = encoder.encode(test_string)
    assert metastring.encoding == Encoding.EXTENDED
    assert metastring.encoded_data[0] == EXTENDED_ENCODING_NEGATIVE_NUMBER_STRING
    decoded_string = decoder.decode(metastring.encoded_data, metastring.encoding)
    assert decoded_string == test_string


def test_strip_last_char():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")

    test_string = "abc"  # encoded as 1|00000|00, 001|00010, exactly two bytes
    encoded_metastring = encoder.encode(test_string)
    assert not encoded_metastring.strip_last_char

    test_string = "abcde"  # encoded as 1|00000|00, 001|00010, 00011|001, 00xxxxxx, stripped last char
    encoded_metastring = encoder.encode(test_string)
    assert encoded_metastring.strip_last_char


def test_empty_string():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")
    decoder = MetaStringDecoder(special_char1=".", special_char2="_")

    metastring = encoder.encode("")
    assert metastring.encoded_data == bytes()

    decoded = decoder.decode(metastring.encoded_data, metastring.encoding)
    assert decoded == ""


def test_ascii_encoding():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")

    test_string = "asciiOnly"
    encoded_metastring = encoder.encode(test_string)
    assert encoded_metastring.encoding != Encoding.EXTENDED
    assert encoded_metastring.encoding == Encoding.ALL_TO_LOWER_SPECIAL


def test_non_ascii_encoding():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")

    test_string = "こんにちは"  # Non-ASCII string
    encoded_metastring = encoder.encode(test_string)
    assert encoded_metastring.encoding == Encoding.EXTENDED
    assert encoded_metastring.encoded_data[0] == EXTENDED_ENCODING_UTF8


def test_non_ascii_encoding_and_non_utf8():
    encoder = MetaStringEncoder(special_char1=".", special_char2="_")

    non_ascii_string = "こんにちは"  # Non-ASCII string

    with pytest.raises(ValueError, match="Unsupported character for LOWER_SPECIAL encoding: こ"):
        encoder.encode_with_encoding(non_ascii_string, Encoding.LOWER_SPECIAL)
