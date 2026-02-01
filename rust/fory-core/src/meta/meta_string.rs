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

use crate::ensure;
use crate::error::Error;
use crate::meta::string_util;
use std::sync::OnceLock;

// equal to "std::i16::MAX"
const SHORT_MAX_VALUE: usize = 32767;
// const HEADER_MASK:i64 = 0xff;

pub static NAMESPACE_ENCODER: MetaStringEncoder = MetaStringEncoder::new('.', '_');
pub static TYPE_NAME_ENCODER: MetaStringEncoder = MetaStringEncoder::new('$', '_');
pub static FIELD_NAME_ENCODER: MetaStringEncoder = MetaStringEncoder::new('$', '_');

pub static NAMESPACE_DECODER: MetaStringDecoder = MetaStringDecoder::new('.', '_');
pub static FIELD_NAME_DECODER: MetaStringDecoder = MetaStringDecoder::new('$', '_');
pub static TYPE_NAME_DECODER: MetaStringDecoder = MetaStringDecoder::new('$', '_');

#[derive(Debug, PartialEq, Hash, Eq, Clone, Copy, Default)]
#[repr(i16)]
pub enum Encoding {
    #[default]
    Extended = 0x00,
    LowerSpecial = 0x01,
    LowerUpperDigitSpecial = 0x02,
    FirstToLowerSpecial = 0x03,
    AllToLowerSpecial = 0x04,
}

#[derive(Debug, PartialEq, Hash, Eq, Clone, Copy)]
#[repr(u8)]
pub enum ExtendedEncoding {
    Utf8 = 0x00,
    NumberString = 0x01,
    NegativeNumberString = 0x02,
}

#[derive(Debug, Clone, Default)]
pub struct MetaString {
    pub original: String,
    pub encoding: Encoding,
    pub bytes: Vec<u8>,
    pub strip_last_char: bool,
    pub special_char1: char,
    pub special_char2: char,
}

impl PartialEq for MetaString {
    fn eq(&self, other: &Self) -> bool {
        self.bytes == other.bytes
    }
}

impl Eq for MetaString {}

impl std::hash::Hash for MetaString {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        self.bytes.hash(state);
    }
}

static EMPTY: OnceLock<MetaString> = OnceLock::new();

impl MetaString {
    pub fn new(
        original: String,
        encoding: Encoding,
        bytes: Vec<u8>,
        special_char1: char,
        special_char2: char,
    ) -> Result<Self, Error> {
        let mut strip_last_char = false;
        if encoding != Encoding::Extended {
            if bytes.is_empty() {
                return Err(Error::encode_error("Encoded data cannot be empty"));
            }
            strip_last_char = (bytes[0] & 0x80) != 0;
        }
        Ok(MetaString {
            original,
            encoding,
            bytes,
            strip_last_char,
            special_char1,
            special_char2,
        })
    }

    pub fn write_to(&self, writer: &mut crate::buffer::Writer) {
        writer.write_var_uint32(self.bytes.len() as u32);
        writer.write_bytes(&self.bytes);
    }

    pub fn get_empty() -> &'static MetaString {
        EMPTY.get_or_init(|| MetaString {
            original: "".to_string(),
            encoding: Encoding::default(),
            bytes: vec![],
            strip_last_char: false,
            special_char1: '\0',
            special_char2: '\0',
        })
    }
}

#[derive(Clone)]
pub struct MetaStringDecoder {
    pub special_char1: char,
    pub special_char2: char,
}

#[derive(Clone)]
pub struct MetaStringEncoder {
    pub special_char1: char,
    pub special_char2: char,
}

#[derive(Debug)]
struct StringStatistics {
    digit_count: usize,
    upper_count: usize,
    can_lower_upper_digit_special_encoded: bool,
    can_lower_special_encoded: bool,
}

fn is_number_string(input: &str) -> bool {
    if input.is_empty() {
        return false;
    }
    let mut chars = input.chars();
    let first = chars.next().unwrap();
    let mut has_digit = false;
    if first == '-' {
        for c in chars {
            if !c.is_ascii_digit() {
                return false;
            }
            has_digit = true;
        }
        return has_digit;
    }
    if !first.is_ascii_digit() {
        return false;
    }
    has_digit = true;
    for c in chars {
        if !c.is_ascii_digit() {
            return false;
        }
    }
    has_digit
}

fn parse_uint64_digits(digits: &str) -> Option<u64> {
    if digits.is_empty() {
        return None;
    }
    let stripped = digits.trim_start_matches('0');
    if stripped.is_empty() {
        return Some(0);
    }
    if stripped.len() > 20 {
        return None;
    }
    if stripped.len() == 20 && stripped > "18446744073709551615" {
        return None;
    }
    stripped.parse::<u64>().ok()
}

fn try_encode_number_string(input: &str) -> Option<Vec<u8>> {
    let negative = input.starts_with('-');
    let digits = if negative { &input[1..] } else { input };
    let value = parse_uint64_digits(digits)?;
    let mut bytes = Vec::with_capacity(9);
    bytes.push(if negative {
        ExtendedEncoding::NegativeNumberString as u8
    } else {
        ExtendedEncoding::NumberString as u8
    });
    let mut v = value;
    loop {
        bytes.push((v & 0xFF) as u8);
        v >>= 8;
        if v == 0 {
            break;
        }
    }
    Some(bytes)
}

fn encode_extended_utf8(input: &str) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(input.len() + 1);
    bytes.push(ExtendedEncoding::Utf8 as u8);
    bytes.extend_from_slice(input.as_bytes());
    bytes
}

fn decode_number_string(bytes: &[u8], negative: bool) -> Result<String, Error> {
    if bytes.is_empty() {
        return Ok(String::new());
    }
    if bytes.len() > 8 {
        return Err(Error::encode_error(format!(
            "NUMBER_STRING payload length exceeds uint64 size: {}",
            bytes.len()
        )));
    }
    let mut value: u64 = 0;
    for (idx, b) in bytes.iter().enumerate() {
        value |= (*b as u64) << (8 * idx);
    }
    let mut result = value.to_string();
    if negative {
        result.insert(0, '-');
    }
    Ok(result)
}

impl MetaStringEncoder {
    pub const fn new(special_char1: char, special_char2: char) -> Self {
        Self {
            special_char1,
            special_char2,
        }
    }

    fn is_latin(&self, s: &str) -> bool {
        string_util::is_latin(s)
    }

    fn _encode(&self, input: &str) -> Result<Option<MetaString>, Error> {
        if input.is_empty() {
            return Ok(Some(MetaString::new(
                input.to_string(),
                Encoding::Extended,
                vec![],
                self.special_char1,
                self.special_char2,
            )?));
        }

        ensure!(
            input.len() < SHORT_MAX_VALUE,
            Error::encode_error(format!(
                "Meta string is too long, max:{SHORT_MAX_VALUE}, current:{}",
                input.len()
            ))
        );

        if is_number_string(input) {
            if let Some(encoded) = try_encode_number_string(input) {
                return Ok(Some(MetaString::new(
                    input.to_string(),
                    Encoding::Extended,
                    encoded,
                    self.special_char1,
                    self.special_char2,
                )?));
            }
            return Ok(Some(MetaString::new(
                input.to_string(),
                Encoding::Extended,
                encode_extended_utf8(input),
                self.special_char1,
                self.special_char2,
            )?));
        }

        if !self.is_latin(input) {
            return Ok(Some(MetaString::new(
                input.to_string(),
                Encoding::Extended,
                encode_extended_utf8(input),
                self.special_char1,
                self.special_char2,
            )?));
        }

        Ok(None)
    }

    pub fn encode(&self, input: &str) -> Result<MetaString, Error> {
        if let Some(ms) = self._encode(input)? {
            return Ok(ms);
        }
        let encoding = self.compute_encoding(input, None);
        self.encode_with_encoding(input, encoding)
    }

    pub fn encode_with_encodings(
        &self,
        input: &str,
        encodings: &[Encoding],
    ) -> Result<MetaString, Error> {
        if let Some(ms) = self._encode(input)? {
            return Ok(ms);
        }
        let encoding = self.compute_encoding(input, Some(encodings));
        self.encode_with_encoding(input, encoding)
    }

    fn compute_encoding(&self, input: &str, encodings: Option<&[Encoding]>) -> Encoding {
        let allow = |e: Encoding| encodings.map_or(true, |opts| opts.contains(&e));
        if is_number_string(input) {
            return Encoding::Extended;
        }
        let statistics = self.compute_statistics(input);
        if statistics.can_lower_special_encoded && allow(Encoding::LowerSpecial) {
            return Encoding::LowerSpecial;
        }
        if statistics.can_lower_upper_digit_special_encoded {
            if statistics.digit_count != 0 && allow(Encoding::LowerUpperDigitSpecial) {
                return Encoding::LowerUpperDigitSpecial;
            }
            let upper_count: usize = statistics.upper_count;
            if upper_count == 1
                && input.chars().next().unwrap().is_uppercase()
                && allow(Encoding::FirstToLowerSpecial)
            {
                return Encoding::FirstToLowerSpecial;
            }
            if ((input.len() + upper_count) * 5) < (input.len() * 6)
                && allow(Encoding::AllToLowerSpecial)
            {
                return Encoding::AllToLowerSpecial;
            }
            if allow(Encoding::LowerUpperDigitSpecial) {
                return Encoding::LowerUpperDigitSpecial;
            }
        }
        Encoding::Extended
    }

    fn compute_statistics(&self, chars: &str) -> StringStatistics {
        let mut can_lower_upper_digit_special_encoded = true;
        let mut can_lower_special_encoded = true;
        let mut digit_count = 0;
        let mut upper_count = 0;
        for c in chars.chars() {
            if can_lower_upper_digit_special_encoded
                && !(c.is_lowercase()
                    || c.is_uppercase()
                    || c.is_ascii_digit()
                    || (c == self.special_char1 || c == self.special_char2))
            {
                can_lower_upper_digit_special_encoded = false;
            }
            if can_lower_special_encoded
                && !(c.is_lowercase() || matches!(c, '.' | '_' | '$' | '|'))
            {
                can_lower_special_encoded = false;
            }
            if c.is_ascii_digit() {
                digit_count += 1;
            }
            if c.is_uppercase() {
                upper_count += 1;
            }
        }
        StringStatistics {
            digit_count,
            upper_count,
            can_lower_upper_digit_special_encoded,
            can_lower_special_encoded,
        }
    }

    pub fn encode_with_encoding(
        &self,
        input: &str,
        encoding: Encoding,
    ) -> Result<MetaString, Error> {
        if input.is_empty() {
            return MetaString::new(
                input.to_string(),
                Encoding::Extended,
                vec![],
                self.special_char1,
                self.special_char2,
            );
        }
        ensure!(
            input.len() < SHORT_MAX_VALUE,
            Error::encode_error(format!(
                "Meta string is too long, max:{SHORT_MAX_VALUE}, current:{}",
                input.len()
            ))
        );
        ensure!(
            encoding == Encoding::Extended || self.is_latin(input),
            Error::encode_error("Non-ASCII characters in meta string are not allowed")
        );

        if input.is_empty() {
            return MetaString::new(
                input.to_string(),
                Encoding::Extended,
                vec![],
                self.special_char1,
                self.special_char2,
            );
        };

        match encoding {
            Encoding::LowerSpecial => {
                let encoded_data = self.encode_lower_special(input)?;
                MetaString::new(
                    input.to_string(),
                    encoding,
                    encoded_data,
                    self.special_char1,
                    self.special_char2,
                )
            }
            Encoding::LowerUpperDigitSpecial => {
                let encoded_data = self.encode_lower_upper_digit_special(input)?;
                MetaString::new(
                    input.to_string(),
                    encoding,
                    encoded_data,
                    self.special_char1,
                    self.special_char2,
                )
            }
            Encoding::FirstToLowerSpecial => {
                let encoded_data = self.encode_first_to_lower_special(input)?;
                MetaString::new(
                    input.to_string(),
                    encoding,
                    encoded_data,
                    self.special_char1,
                    self.special_char2,
                )
            }
            Encoding::AllToLowerSpecial => {
                let upper_count = input.chars().filter(|c| c.is_uppercase()).count();
                let encoded_data = self.encode_all_to_lower_special(input, upper_count)?;
                MetaString::new(
                    input.to_string(),
                    encoding,
                    encoded_data,
                    self.special_char1,
                    self.special_char2,
                )
            }
            Encoding::Extended => {
                let encoded_data = if is_number_string(input) {
                    try_encode_number_string(input).unwrap_or_else(|| encode_extended_utf8(input))
                } else {
                    encode_extended_utf8(input)
                };
                MetaString::new(
                    input.to_string(),
                    Encoding::Extended,
                    encoded_data,
                    self.special_char1,
                    self.special_char2,
                )
            }
        }
    }

    fn encode_generic(&self, input: &str, bits_per_char: u8) -> Result<Vec<u8>, Error> {
        let total_bits: usize = input.len() * bits_per_char as usize + 1;
        let byte_length: usize = (total_bits + 7) / 8;
        let mut bytes = vec![0; byte_length];
        let mut current_bit = 1;
        for c in input.chars() {
            let value = self.char_to_value(c, bits_per_char)?;
            for i in (0..bits_per_char).rev() {
                if (value & (1 << i)) != 0 {
                    let byte_pos: usize = current_bit / 8;
                    let bit_pos: usize = current_bit % 8;
                    bytes[byte_pos] |= 1 << (7 - bit_pos);
                }
                current_bit += 1;
            }
        }
        if byte_length * 8 >= total_bits + bits_per_char as usize {
            bytes[0] |= 0x80;
        }
        Ok(bytes)
    }
    pub fn encode_lower_special(&self, input: &str) -> Result<Vec<u8>, Error> {
        self.encode_generic(input, 5)
    }

    pub fn encode_lower_upper_digit_special(&self, input: &str) -> Result<Vec<u8>, Error> {
        self.encode_generic(input, 6)
    }

    pub fn encode_first_to_lower_special(&self, input: &str) -> Result<Vec<u8>, Error> {
        if input.is_empty() {
            return self.encode_generic("", 5);
        }

        let mut iter = input.char_indices();
        let (first_idx, first_char) = iter.next().unwrap();

        let lower = first_char.to_lowercase().to_string();

        // Fast path: if lowercase has the same byte length and is ASCII,
        // we can modify the first byte directly without rebuilding the string.
        if lower.len() == first_char.len_utf8() && first_char.is_ascii() {
            let mut bytes = input.as_bytes().to_owned();
            bytes[first_idx] = lower.as_bytes()[0];
            return self.encode_generic(std::str::from_utf8(&bytes).unwrap(), 5);
        }

        // rebuild only the necessary prefix + suffix (still efficient).
        let (_, rest) = input.split_at(first_char.len_utf8());
        let mut result = String::with_capacity(input.len() + lower.len() - first_char.len_utf8());
        result.push_str(&lower);
        result.push_str(rest);
        self.encode_generic(&result, 5)
    }

    pub fn encode_all_to_lower_special(
        &self,
        input: &str,
        upper_count: usize,
    ) -> Result<Vec<u8>, Error> {
        let mut new_chars = Vec::with_capacity(input.len() + upper_count);
        for c in input.chars() {
            if c.is_uppercase() {
                new_chars.push('|');
                new_chars.push(c.to_lowercase().next().unwrap());
            } else {
                new_chars.push(c);
            }
        }
        self.encode_generic(&new_chars.iter().collect::<String>(), 5)
    }

    fn char_to_value(&self, c: char, bits_per_char: u8) -> Result<u8, Error> {
        match bits_per_char {
            5 => match c {
                'a'..='z' => Ok(c as u8 - b'a'),
                '.' => Ok(26),
                '_' => Ok(27),
                '$' => Ok(28),
                '|' => Ok(29),
                _ => Err(Error::encode_error(format!(
                    "Unsupported character for LOWER_UPPER_DIGIT_SPECIAL encoding: {c}",
                )))?,
            },
            6 => match c {
                'a'..='z' => Ok(c as u8 - b'a'),
                'A'..='Z' => Ok(c as u8 - b'A' + 26),
                '0'..='9' => Ok(c as u8 - b'0' + 52),
                _ => {
                    if c == self.special_char1 {
                        Ok(62)
                    } else if c == self.special_char2 {
                        Ok(63)
                    } else {
                        Err(Error::encode_error(format!(
                            "Invalid character value for LOWER_SPECIAL decoding: {c:?}",
                        )))?
                    }
                }
            },
            _ => unreachable!(),
        }
    }
}

impl MetaStringDecoder {
    pub const fn new(special_char1: char, special_char2: char) -> Self {
        MetaStringDecoder {
            special_char1,
            special_char2,
        }
    }

    pub fn decode(&self, encoded_data: &[u8], encoding: Encoding) -> Result<MetaString, Error> {
        let str = {
            if encoded_data.is_empty() {
                Ok("".to_string())
            } else {
                match encoding {
                    Encoding::LowerSpecial => self.decode_lower_special(encoded_data),
                    Encoding::LowerUpperDigitSpecial => {
                        self.decode_lower_upper_digit_special(encoded_data)
                    }
                    Encoding::FirstToLowerSpecial => {
                        self.decode_rep_first_lower_special(encoded_data)
                    }
                    Encoding::AllToLowerSpecial => {
                        self.decode_rep_all_to_lower_special(encoded_data)
                    }
                    Encoding::Extended => self.decode_extended(encoded_data),
                }
            }
        }?;
        MetaString::new(
            str,
            encoding,
            Vec::from(encoded_data),
            self.special_char1,
            self.special_char2,
        )
    }

    fn decode_lower_special(&self, data: &[u8]) -> Result<String, Error> {
        let mut decoded = String::new();
        let total_bits: usize = data.len() * 8;
        let strip_last_char = (data[0] & 0x80) != 0;
        let bit_mask: usize = 0b11111;
        let mut bit_index = 1;
        while bit_index + 5 <= total_bits && !(strip_last_char && (bit_index + 2 * 5 > total_bits))
        {
            let byte_index = bit_index / 8;
            let intra_byte_index = bit_index % 8;
            let char_value: usize = if intra_byte_index > 3 {
                ((((data[byte_index] as usize) << 8)
                    | if byte_index + 1 < data.len() {
                        data.get(byte_index + 1).cloned().unwrap() as usize & 0xFF
                    } else {
                        0
                    })
                    >> (11 - intra_byte_index))
                    & bit_mask
            } else {
                ((data[byte_index] as usize) >> (3 - intra_byte_index)) & bit_mask
            };
            bit_index += 5;
            decoded.push(self.decode_lower_special_char(char_value as u8)?);
        }
        Ok(decoded)
    }

    fn decode_lower_upper_digit_special(&self, data: &[u8]) -> Result<String, Error> {
        let mut decoded = String::new();
        let num_bits = data.len() * 8;
        let strip_last_char = (data[0] & 0x80) != 0;
        let mut bit_index = 1;
        let bit_mask: usize = 0b111111;
        while bit_index + 6 <= num_bits && !(strip_last_char && (bit_index + 2 * 6 > num_bits)) {
            let byte_index = bit_index / 8;
            let intra_byte_index = bit_index % 8;
            let char_value: usize = if intra_byte_index > 2 {
                ((((data[byte_index] as usize) << 8)
                    | if byte_index + 1 < data.len() {
                        data.get(byte_index + 1).cloned().unwrap() as usize & 0xFF
                    } else {
                        0
                    })
                    >> (10 - intra_byte_index))
                    & bit_mask
            } else {
                ((data[byte_index] as usize) >> (2 - intra_byte_index)) & bit_mask
            };
            bit_index += 6;
            decoded.push(self.decode_lower_upper_digit_special_char(char_value as u8)?);
        }
        Ok(decoded)
    }

    fn decode_extended(&self, data: &[u8]) -> Result<String, Error> {
        if data.is_empty() {
            return Ok(String::new());
        }
        let actual = data[0];
        let payload = &data[1..];
        match actual {
            x if x == ExtendedEncoding::Utf8 as u8 => {
                Ok(String::from_utf8_lossy(payload).into_owned())
            }
            x if x == ExtendedEncoding::NumberString as u8 => decode_number_string(payload, false),
            x if x == ExtendedEncoding::NegativeNumberString as u8 => {
                decode_number_string(payload, true)
            }
            _ => Err(Error::encode_error(format!(
                "Unsupported extended meta string encoding value: {actual}",
            ))),
        }
    }

    fn decode_lower_special_char(&self, char_value: u8) -> Result<char, Error> {
        match char_value {
            0..=25 => Ok((b'a' + char_value) as char),
            26 => Ok('.'),
            27 => Ok('_'),
            28 => Ok('$'),
            29 => Ok('|'),
            _ => Err(Error::encode_error(format!(
                "Invalid character value for LOWER_SPECIAL decoding: {char_value}",
            )))?,
        }
    }

    fn decode_lower_upper_digit_special_char(&self, char_value: u8) -> Result<char, Error> {
        match char_value {
            0..=25 => Ok((b'a' + char_value) as char),
            26..=51 => Ok((b'A' + char_value - 26) as char),
            52..=61 => Ok((b'0' + char_value - 52) as char),
            62 => Ok(self.special_char1),
            63 => Ok(self.special_char2),
            _ => Err(Error::encode_error(format!(
                "Invalid character value for LOWER_UPPER_DIGIT_SPECIAL decoding: {char_value}",
            )))?,
        }
    }

    fn decode_rep_first_lower_special(&self, data: &[u8]) -> Result<String, Error> {
        let decoded_str = self.decode_lower_special(data)?;
        let mut chars = decoded_str.chars();
        match chars.next() {
            Some(first_char) => {
                let mut result = first_char.to_ascii_uppercase().to_string();
                result.extend(chars);
                Ok(result)
            }
            None => Ok(decoded_str),
        }
    }
    fn decode_rep_all_to_lower_special(&self, data: &[u8]) -> Result<String, Error> {
        let decoded_str = self.decode_lower_special(data)?;
        let mut result = String::new();
        let mut skip = false;
        for (i, char) in decoded_str.chars().enumerate() {
            if skip {
                skip = false;
                continue;
            }
            // Encounter a '|', capitalize the next character
            // and skip the following character.
            if char == '|' {
                if let Some(next_char) = decoded_str.chars().nth(i + 1) {
                    result.push(next_char.to_ascii_uppercase());
                }
                skip = true;
            } else {
                result.push(char);
            }
        }
        Ok(result)
    }
}
