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

use anyhow::anyhow;

use crate::ensure;
use crate::error::Error;
use crate::meta::string_util;

// equal to "std::i16::MAX"
const SHORT_MAX_VALUE: usize = 32767;

#[derive(Debug, PartialEq)]
pub enum Encoding {
    Utf8 = 0x00,
    LowerSpecial = 0x01,
    LowerUpperDigitSpecial = 0x02,
    FirstToLowerSpecial = 0x03,
    AllToLowerSpecial = 0x04,
}

#[derive(Debug, PartialEq)]
pub struct MetaString {
    pub original: String,
    pub encoding: Encoding,
    pub bytes: Vec<u8>,
    pub strip_last_char: bool,
}

impl MetaString {
    pub fn new(original: String, encoding: Encoding, bytes: Vec<u8>) -> Result<Self, Error> {
        let mut strip_last_char = false;
        if encoding != Encoding::Utf8 {
            ensure!(!bytes.is_empty(), anyhow!("Encoded data cannot be empty"));

            strip_last_char = (bytes[0] & 0x80) != 0;
        }
        Ok(MetaString {
            original,
            encoding,
            bytes,
            strip_last_char,
        })
    }
}

pub struct MetaStringDecoder {}
impl Default for MetaStringDecoder {
    fn default() -> Self {
        Self::new()
    }
}

pub struct MetaStringEncoder<'a> {
    encoding_options: Option<&'a [Encoding]>,
}
impl<'a> Default for MetaStringEncoder<'a> {
    fn default() -> Self {
        Self {
            encoding_options: None,
        }
    }
}

#[derive(Debug)]
struct StringStatistics {
    digit_count: usize,
    upper_count: usize,
    can_lower_upper_digit_special_encoded: bool,
    can_lower_special_encoded: bool,
}

impl<'a> MetaStringEncoder<'a> {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn set_options(mut self, encoding_options: Option<&'a [Encoding]>) -> Self {
        self.encoding_options = encoding_options.into();
        self
    }

    fn is_latin(&self, s: &str) -> bool {
        string_util::is_latin(s)
    }

    pub fn encode(&self, input: &str) -> Result<MetaString, Error> {
        if input.is_empty() {
            return MetaString::new(input.to_string(), Encoding::Utf8, vec![]);
        }
        ensure!(
            input.len() < SHORT_MAX_VALUE,
            anyhow!(
                "Meta string is too long, max:{SHORT_MAX_VALUE}, current:{}",
                input.len()
            )
        );
        if !self.is_latin(input) {
            return MetaString::new(input.to_string(), Encoding::Utf8, input.as_bytes().to_vec());
        }
        let encoding = self.compute_encoding(input);
        self.encode_with_encoding(input, encoding)
    }

    fn compute_encoding(&self, input: &str) -> Encoding {
        let allow = |e: Encoding| self.encoding_options.map_or(true, |opts| opts.contains(&e));
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
        Encoding::Utf8
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
                    || c == '.'
                    || c == '_')
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
            return MetaString::new(input.to_string(), Encoding::Utf8, vec![]);
        }
        ensure!(
            input.len() < SHORT_MAX_VALUE,
            anyhow!(
                "Meta string is too long, max:{SHORT_MAX_VALUE}, current:{}",
                input.len()
            )
        );
        ensure!(
            encoding == Encoding::Utf8 || self.is_latin(input),
            anyhow!("Non-ASCII characters in meta string are not allowed")
        );

        if input.is_empty() {
            return MetaString::new(input.to_string(), Encoding::Utf8, vec![]);
        };

        match encoding {
            Encoding::LowerSpecial => {
                let encoded_data = self.encode_lower_special(input)?;
                MetaString::new(input.to_string(), encoding, encoded_data)
            }
            Encoding::LowerUpperDigitSpecial => {
                let encoded_data = self.encode_lower_upper_digit_special(input)?;
                MetaString::new(input.to_string(), encoding, encoded_data)
            }
            Encoding::FirstToLowerSpecial => {
                let encoded_data = self.encode_first_to_lower_special(input)?;
                MetaString::new(input.to_string(), encoding, encoded_data)
            }
            Encoding::AllToLowerSpecial => {
                let upper_count = input.chars().filter(|c| c.is_uppercase()).count();
                let encoded_data = self.encode_all_to_lower_special(input, upper_count)?;
                MetaString::new(input.to_string(), encoding, encoded_data)
            }
            Encoding::Utf8 => {
                let encoded_data = input.as_bytes().to_vec();
                MetaString::new(input.to_string(), Encoding::Utf8, encoded_data)
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
        let mut chars: Vec<char> = input.chars().collect();
        chars[0] = chars[0].to_lowercase().next().unwrap();
        self.encode_generic(&chars.iter().collect::<String>(), 5)
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
                _ => Err(anyhow!(
                    "Unsupported character for LOWER_UPPER_DIGIT_SPECIAL encoding: {c}"
                ))?,
            },
            6 => match c {
                'a'..='z' => Ok(c as u8 - b'a'),
                'A'..='Z' => Ok(c as u8 - b'A' + 26),
                '0'..='9' => Ok(c as u8 - b'0' + 52),
                _ => {
                    if c == '.' {
                        Ok(62)
                    } else if c == '_' {
                        Ok(63)
                    } else {
                        Err(anyhow!(
                            "Invalid character value for LOWER_SPECIAL decoding: {c:?}"
                        ))?
                    }
                }
            },
            _ => unreachable!(),
        }
    }
}

impl MetaStringDecoder {
    pub fn new() -> Self {
        MetaStringDecoder {}
    }

    pub fn decode(&self, encoded_data: &[u8], encoding: Encoding) -> Result<String, Error> {
        if encoded_data.is_empty() {
            return Ok("".to_string());
        }
        match encoding {
            Encoding::LowerSpecial => self.decode_lower_special(encoded_data),
            Encoding::LowerUpperDigitSpecial => self.decode_lower_upper_digit_special(encoded_data),
            Encoding::FirstToLowerSpecial => self.decode_rep_first_lower_special(encoded_data),
            Encoding::AllToLowerSpecial => self.decode_rep_all_to_lower_special(encoded_data),
            Encoding::Utf8 => Ok(String::from_utf8_lossy(encoded_data).into_owned()),
        }
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

    fn decode_lower_special_char(&self, char_value: u8) -> Result<char, Error> {
        match char_value {
            0..=25 => Ok((b'a' + char_value) as char),
            26 => Ok('.'),
            27 => Ok('_'),
            28 => Ok('$'),
            29 => Ok('|'),
            _ => Err(anyhow!(
                "Invalid character value for LOWER_SPECIAL decoding: {char_value}"
            ))?,
        }
    }

    fn decode_lower_upper_digit_special_char(&self, char_value: u8) -> Result<char, Error> {
        match char_value {
            0..=25 => Ok((b'a' + char_value) as char),
            26..=51 => Ok((b'A' + char_value - 26) as char),
            52..=61 => Ok((b'0' + char_value - 52) as char),
            62 => Ok('.'),
            63 => Ok('_'),
            _ => Err(anyhow!(
                "Invalid character value for LOWER_UPPER_DIGIT_SPECIAL decoding: {char_value}"
            ))?,
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
