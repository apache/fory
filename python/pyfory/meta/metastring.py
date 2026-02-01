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

from collections import namedtuple
from enum import Enum
from typing import List


class Encoding(Enum):
    """
    Defines the types of supported encodings for MetaStrings.
    """

    UTF_8 = 0x00
    LOWER_SPECIAL = 0x01
    LOWER_UPPER_DIGIT_SPECIAL = 0x02
    FIRST_TO_LOWER_SPECIAL = 0x03
    ALL_TO_LOWER_SPECIAL = 0x04


Statistics = namedtuple(
    "Statistics",
    [
        "can_lower_upper_digit_special_encoded",
        "can_lower_special_encoded",
        "digit_count",
        "upper_count",
    ],
)

# _METASTRING_NUM_CHARS_LIMIT is used to check whether the length of the value is valid.
_METASTRING_NUM_CHARS_LIMIT = 32767


class MetaString:
    def __init__(
        self,
        original: str,
        encoding: Encoding,
        encoded_data: bytes,
        length: int,
        special_char1: str = ".",
        special_char2: str = "|",
    ):
        self.original = original
        self.encoding = encoding
        self.encoded_data = encoded_data
        self.length = length
        self.special_char1 = special_char1
        self.special_char2 = special_char2
        if self.encoding != Encoding.UTF_8:
            self.strip_last_char = (encoded_data[0] & 0x80) != 0
        else:
            self.strip_last_char = False


class MetaStringDecoder:
    """
    Decodes MetaString objects back into their original plain text form.
    """

    def __init__(self, special_char1: str, special_char2: str):
        """
        Creates a MetaStringDecoder with specified special characters used for decoding.

        Args:
            special_char1 (str): The first special character used for encoding.
            special_char2 (str): The second special character used for encoding.
        """
        self.special_char1 = special_char1
        self.special_char2 = special_char2

    def decode(self, encoded_data: bytes, encoding: Encoding) -> str:
        """
        Decodes the encoded data using the specified encoding.

        Args:
            encoded_data (bytes): The data to decode.
            encoding (Encoding): The encoding type used for decoding.

        Returns:
            str: The decoded string.
        """
        if len(encoded_data) == 0:
            return ""
        return self.decode_with_encoding(encoded_data, encoding)

    def decode_with_encoding(self, encoded_data: bytes, encoding: Encoding) -> str:
        """
        Decodes the encoded data with the specified encoding.

        Args:
            encoded_data (bytes): The data to decode.
            encoding (Encoding): The encoding type.

        Returns:
            str: The decoded string.
        """
        if encoding == Encoding.LOWER_SPECIAL:
            return self._decode_lower_special(encoded_data)
        elif encoding == Encoding.LOWER_UPPER_DIGIT_SPECIAL:
            return self._decode_lower_upper_digit_special(encoded_data)
        elif encoding == Encoding.FIRST_TO_LOWER_SPECIAL:
            return self._decode_rep_first_lower_special(encoded_data)
        elif encoding == Encoding.ALL_TO_LOWER_SPECIAL:
            return self._decode_rep_all_to_lower_special(encoded_data)
        elif encoding == Encoding.UTF_8:
            return encoded_data.decode("utf-8")
        else:
            raise ValueError(f"Unexpected encoding flag: {encoding}")

    def _decode_lower_special(self, data: bytes) -> str:
        """
        Decodes data encoded with LOWER_SPECIAL encoding.

        Args:
            data (bytes): The encoded data.

        Returns:
            str: The decoded string.
        """
        decoded = []
        num_bits = len(data) * 8  # Total number of bits in the data
        strip_last_char = (data[0] & 0x80) != 0  # Check the first bit of the first byte
        bit_index = 1
        bit_mask = 0b11111
        while bit_index + 5 <= num_bits and not (strip_last_char and (bit_index + 2 * 5 > num_bits)):
            byte_index = bit_index // 8
            intra_byte_index = bit_index % 8
            # Extract the 5-bit character value across byte boundaries if needed
            char_value = ((data[byte_index] << 8) | (data[byte_index + 1] if byte_index + 1 < len(data) else 0)) >> (11 - intra_byte_index) & bit_mask
            bit_index += 5
            decoded.append(self._decode_lower_special_char(char_value))

        return "".join(decoded)

    def _decode_lower_upper_digit_special(self, data: bytes) -> str:
        """
        Decodes data encoded with LOWER_UPPER_DIGIT_SPECIAL encoding.

        Args:
            data (bytes): The encoded data.

        Returns:
            str: The decoded string.
        """
        decoded = []
        bit_index = 1
        strip_last_char = (data[0] & 0x80) != 0
        bit_mask = 0b111111
        num_bits = len(data) * 8
        while bit_index + 6 <= num_bits and not (strip_last_char and (bit_index + 2 * 6 > num_bits)):
            byte_index = bit_index // 8
            intra_byte_index = bit_index % 8
            # Extract the 6-bit character value across byte boundaries if needed
            char_value = ((data[byte_index] << 8) | (data[byte_index + 1] if byte_index + 1 < len(data) else 0)) >> (10 - intra_byte_index) & bit_mask
            bit_index += 6
            decoded.append(self._decode_lower_upper_digit_special_char(char_value))
        return "".join(decoded)

    def _decode_lower_special_char(self, char_value: int) -> str:
        """
        Decodes a single character encoded with LOWER_SPECIAL encoding.

        Args:
            char_value (int): The encoded character value.

        Returns:
            str: The decoded character.
        """
        if 0 <= char_value <= 25:
            return chr(ord("a") + char_value)
        elif char_value == 26:
            return "."
        elif char_value == 27:
            return "_"
        elif char_value == 28:
            return "$"
        elif char_value == 29:
            return "|"
        else:
            raise ValueError(f"Invalid character value for LOWER_SPECIAL: {char_value}")

    def _decode_lower_upper_digit_special_char(self, char_value: int) -> str:
        """
        Decodes a single character encoded with LOWER_UPPER_DIGIT_SPECIAL encoding.

        Args:
            char_value (int): The encoded character value.

        Returns:
            str: The decoded character.
        """
        if 0 <= char_value <= 25:
            return chr(ord("a") + char_value)
        elif 26 <= char_value <= 51:
            return chr(ord("A") + (char_value - 26))
        elif 52 <= char_value <= 61:
            return chr(ord("0") + (char_value - 52))
        elif char_value == 62:
            return self.special_char1  # Use special_char1 for the encoding
        elif char_value == 63:
            return self.special_char2  # Use special_char2 for the encoding
        else:
            raise ValueError(f"Invalid character value for LOWER_UPPER_DIGIT_SPECIAL: {char_value}")

    def _decode_rep_first_lower_special(self, data: bytes) -> str:
        """
        Decodes data encoded with FIRST_TO_LOWER_SPECIAL encoding.

        Args:
            data (bytes): The encoded data.

        Returns:
            str: The decoded string.
        """
        decoded_str = self._decode_lower_special(data)
        return decoded_str.capitalize()

    def _decode_rep_all_to_lower_special(self, data: bytes) -> str:
        """
        Decodes data encoded with ALL_TO_LOWER_SPECIAL encoding.

        Args:
            data (bytes): The encoded data.

        Returns:
            str: The decoded string.
        """
        decoded_str = self._decode_lower_special(data)
        result = []
        skip = False
        for i, char in enumerate(decoded_str):
            if skip:
                skip = False
                continue
            if char == "|":
                result.append(decoded_str[i + 1].upper())
                skip = True
            else:
                result.append(char)
        return "".join(result)


class MetaStringEncoder:
    def __init__(self, special_char1: str, special_char2: str):
        """
        Creates a MetaStringEncoder with specified special characters used for encoding.

        Args:
            special_char1 (str): The first special character used in custom encoding.
            special_char2 (str): The second special character used in custom encoding.
        """
        self.special_char1 = special_char1
        self.special_char2 = special_char2

    def encode(self, input_string: str, encodings: List[Encoding] = None) -> MetaString:
        """
        Encodes the input string into a MetaString object.

        Args:
            input_string (str): The string to encode.

        Returns:
            MetaString: The encoded MetaString object.
        """
        # Long meta string than _METASTRING_NUM_CHARS_LIMIT is not allowed.
        assert len(input_string) < _METASTRING_NUM_CHARS_LIMIT, "Long meta string than _METASTRING_NUM_CHARS_LIMIT is not allowed."

        if not input_string:
            return MetaString(
                input_string,
                Encoding.UTF_8,
                bytes(),
                0,
                self.special_char1,
                self.special_char2,
            )

        encoding = self.compute_encoding(input_string, encodings)
        return self.encode_with_encoding(input_string, encoding)

    def encode_with_encoding(self, input_string: str, encoding: Encoding) -> MetaString:
        """
        Encodes the input string with the specified encoding.

        Args:
            input_string (str): The string to encode.
            encoding (Encoding): The encoding type.

        Returns:
            MetaString: An encoded MetaString object.
        """
        # Long meta string longer than _METASTRING_NUM_CHARS_LIMIT is not allowed.
        assert len(input_string) < _METASTRING_NUM_CHARS_LIMIT, "Long meta string longer than _METASTRING_NUM_CHARS_LIMIT is not allowed."

        if not input_string:
            return MetaString(
                input_string,
                Encoding.UTF_8,
                bytes(),
                0,
                self.special_char1,
                self.special_char2,
            )

        length = len(input_string)
        if encoding == Encoding.LOWER_SPECIAL:
            encoded_data = self._encode_lower_special(input_string)
            return MetaString(
                input_string,
                encoding,
                encoded_data,
                length * 5,
                self.special_char1,
                self.special_char2,
            )
        elif encoding == Encoding.LOWER_UPPER_DIGIT_SPECIAL:
            encoded_data = self._encode_lower_upper_digit_special(input_string)
            return MetaString(
                input_string,
                encoding,
                encoded_data,
                length * 6,
                self.special_char1,
                self.special_char2,
            )
        elif encoding == Encoding.FIRST_TO_LOWER_SPECIAL:
            encoded_data = self._encode_first_to_lower_special(input_string)
            return MetaString(
                input_string,
                encoding,
                encoded_data,
                length * 5,
                self.special_char1,
                self.special_char2,
            )
        elif encoding == Encoding.ALL_TO_LOWER_SPECIAL:
            chars = list(input_string)
            upper_count = sum(1 for c in chars if c.isupper())
            encoded_data = self._encode_all_to_lower_special(chars)
            return MetaString(
                input_string,
                encoding,
                encoded_data,
                (upper_count + length) * 5,
                self.special_char1,
                self.special_char2,
            )
        else:
            encoded_data = bytes(input_string, "utf-8")
            return MetaString(
                input_string,
                Encoding.UTF_8,
                encoded_data,
                len(encoded_data) * 8,
                self.special_char1,
                self.special_char2,
            )

    def compute_encoding(self, input_string: str, encodings: List[Encoding] = None) -> Encoding:
        """
        Determines the encoding type of the input string.

        Args:
            input_string (str): The string to be encoded.

        Returns:
            Encoding: The encoding type.
        """
        if not input_string:
            return Encoding.LOWER_SPECIAL
        if encodings is None:
            encodings = list(Encoding.__members__.values())

        chars = list(input_string)
        statistics = self._compute_statistics(chars)
        if statistics.can_lower_special_encoded and Encoding.LOWER_SPECIAL in encodings:
            return Encoding.LOWER_SPECIAL
        elif statistics.can_lower_upper_digit_special_encoded and Encoding.LOWER_UPPER_DIGIT_SPECIAL in encodings:
            if statistics.digit_count != 0:
                return Encoding.LOWER_UPPER_DIGIT_SPECIAL
            else:
                upper_count = statistics.upper_count
                if upper_count == 1 and chars[0].isupper():
                    return Encoding.FIRST_TO_LOWER_SPECIAL
                if (len(chars) + upper_count) * 5 < len(chars) * 6 and Encoding.ALL_TO_LOWER_SPECIAL in encodings:
                    return Encoding.ALL_TO_LOWER_SPECIAL
                else:
                    if Encoding.LOWER_UPPER_DIGIT_SPECIAL in encodings:
                        return Encoding.LOWER_UPPER_DIGIT_SPECIAL
        if Encoding.UTF_8 in encodings:
            return Encoding.UTF_8
        raise ValueError(f"No encoding found for string: {input_string}, encodings: {encodings}")

    def _compute_statistics(self, chars: List[str]) -> Statistics:
        """
        Computes statistics for the given characters to determine encoding possibilities.

        Args:
            chars (list): List of characters to analyze.

        Returns:
            Statistics: A named tuple with encoding possibility flags and counts.
        """
        can_lower_upper_digit_special_encoded = True
        can_lower_special_encoded = True
        digit_count = 0
        upper_count = 0
        for c in chars:
            if can_lower_upper_digit_special_encoded:
                if not (c.islower() or c.isupper() or c.isdigit() or c in {self.special_char1, self.special_char2}):
                    can_lower_upper_digit_special_encoded = False
            if can_lower_special_encoded:
                if not (c.islower() or c in {".", "_", "$", "|"}):
                    can_lower_special_encoded = False
            if c.isdigit():
                digit_count += 1
            if c.isupper():
                upper_count += 1

        return Statistics(
            can_lower_upper_digit_special_encoded,
            can_lower_special_encoded,
            digit_count,
            upper_count,
        )

    def _encode_lower_special(self, input_string: str) -> bytes:
        """
        Encodes the input string using LOWER_SPECIAL encoding.

        Args:
            input_string (str): The string to encode.

        Returns:
            bytes: The encoded data.
        """
        chars = list(input_string)
        return self._encode_generic(chars, 5)

    def _encode_lower_upper_digit_special(self, input_string: str) -> bytes:
        """
        Encodes the input string using LOWER_UPPER_DIGIT_SPECIAL encoding.

        Args:
            input_string (str): The string to encode.

        Returns:
            bytes: The encoded data.
        """
        chars = list(input_string)
        return self._encode_generic(chars, 6)

    def _encode_first_to_lower_special(self, input_string: str) -> bytes:
        """
        Encodes the input string using FIRST_TO_LOWER_SPECIAL encoding.

        Args:
            input_string (str): The string to encode.

        Returns:
            bytes: The encoded data.
        """
        chars = list(input_string)
        chars[0] = chars[0].lower()
        return self._encode_generic(chars, 5)

    def _encode_all_to_lower_special(self, chars: List[str]) -> bytes:
        """
        Encodes the input string using ALL_TO_LOWER_SPECIAL encoding.

        Args:
            chars (list): The characters to encode.

        Returns:
            bytes: The encoded data.
        """
        new_chars = []
        for c in chars:
            if c.isupper():
                new_chars.append("|")
                new_chars.append(c.lower())
            else:
                new_chars.append(c)
        return self._encode_generic(new_chars, 5)

    def _encode_generic(self, chars: List[str], bits_per_char: int) -> bytes:
        """
        Generic encoding function for encoding characters into bytes.

        Args:
            chars (list): The characters to encode.
            bits_per_char (int): The number of bits per character.

        Returns:
            bytes: The encoded data.
        """
        total_bits = len(chars) * bits_per_char + 1
        byte_length = (total_bits + 7) // 8
        bytes_array = bytearray(byte_length)
        current_bit = 1
        for c in chars:
            value = self._char_to_value(c, bits_per_char)
            for i in range(bits_per_char - 1, -1, -1):
                if (value & (1 << i)) != 0:
                    byte_pos = current_bit // 8
                    bit_pos = current_bit % 8
                    bytes_array[byte_pos] |= 1 << (7 - bit_pos)
                current_bit += 1
        strip_last_char = len(bytes_array) * 8 >= total_bits + bits_per_char
        if strip_last_char:
            bytes_array[0] = bytes_array[0] | 0x80
        return bytes(bytes_array)

    def _char_to_value(self, c: str, bits_per_char: int) -> int:
        """
        Converts a character to its encoded value based on the number of bits per character.

        Args:
            c (str): The character to convert.
            bits_per_char (int): The number of bits per character.

        Returns:
            int: The encoded value of the character.
        """
        if bits_per_char == 5:
            if "a" <= c <= "z":
                return ord(c) - ord("a")
            elif c == ".":
                return 26
            elif c == "_":
                return 27
            elif c == "$":
                return 28
            elif c == "|":
                return 29
            else:
                raise ValueError(f"Unsupported character for LOWER_SPECIAL encoding: {c}")
        elif bits_per_char == 6:
            if "a" <= c <= "z":
                return ord(c) - ord("a")
            elif "A" <= c <= "Z":
                return 26 + (ord(c) - ord("A"))
            elif "0" <= c <= "9":
                return 52 + (ord(c) - ord("0"))
            elif c == self.special_char1:
                return 62
            elif c == self.special_char2:
                return 63
            else:
                raise ValueError(f"Unsupported character for LOWER_UPPER_DIGIT_SPECIAL encoding: {c}")
