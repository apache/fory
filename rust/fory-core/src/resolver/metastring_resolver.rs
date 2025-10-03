use std::collections::HashMap;
use std::convert::TryInto;

use crate::meta::{MetaString, Encoding};
use crate::meta::murmurhash3_x64_128;
use crate::buffer::{Reader, Writer};
use crate::resolver::context::{ReadContext, WriteContext};

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct MetaStringBytes {
    pub bytes: Vec<u8>,
    pub hash_code: i64,
    pub encoding: Encoding,
    pub first8: u64,
    pub second8: u64,
    pub dynamic_write_id: i16,
}

const HEADER_MASK: i64 = 0xff;


fn byte_to_encoding(byte: u8) -> Encoding {
    match byte {
        0 => Encoding::Utf8,
        1 => Encoding::LowerSpecial,
        2 => Encoding::LowerUpperDigitSpecial,
        3 => Encoding::FirstToLowerSpecial,
        4 => Encoding::AllToLowerSpecial,
        _ => unreachable!()
    }
}

impl MetaStringBytes {
    pub const DEFAULT_DYNAMIC_WRITE_STRING_ID: i16 = -1;
    pub const EMPTY: MetaStringBytes = MetaStringBytes {
        bytes: Vec::new(),
        hash_code: 0,
        encoding: Encoding::Utf8,
        first8: 0,
        second8: 0,
        dynamic_write_id: MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID,
    };

    pub fn new(bytes: Vec<u8>, hash_code: i64, encoding: Encoding, first8: u64, second8: u64) -> Self {
        MetaStringBytes {
            bytes,
            hash_code,
            encoding,
            first8,
            second8,
            dynamic_write_id: MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID,
        }
    }

    pub fn decode_lossy(&self) -> String {
        String::from_utf8_lossy(&self.bytes).into_owned()
    }

    pub(crate) fn from_metastring(meta_string: &MetaString) -> Self {
        let bytes = meta_string.bytes.to_vec();
        let mut hash_code = murmurhash3_x64_128(&bytes, 47).0 as i64;
        hash_code = hash_code.abs();
        if hash_code == 0 {
            hash_code += 256;
        }
        hash_code = (hash_code as u64 & 0xffffffffffffff00) as i64;
        let encoding = meta_string.encoding;
        let header = encoding as i64 & HEADER_MASK;
        hash_code |= header;
        let header = (hash_code & HEADER_MASK) as u8;
        let encoding = byte_to_encoding(header);
        let mut data = bytes.clone();
        if data.len() < 16 {
            data.resize(16, 0);
        }
        let first8 = u64::from_le_bytes(data[0..8].try_into().unwrap());
        let second8 = u64::from_le_bytes(data[8..16].try_into().unwrap());
        Self::new(
            bytes,
            hash_code,
            encoding,
            first8,
            second8,
        )
    }
}

/// Resolver 本体
#[derive(Default)]
pub struct MetaStringResolver {
    // 原 Java: metaStringBytes2StringMap
    meta_string_bytes_to_string: HashMap<MetaStringBytes, String>,

    // long hash -> MetaStringBytes (用于 long strings)
    hash_to_meta: HashMap<i64, MetaStringBytes>,

    // (v1, v2, encoding) -> MetaStringBytes 用于 small strings (<= SMALL_STRING_THRESHOLD)
    small_map: HashMap<(u64, u64, u8), MetaStringBytes>,

    // MetaString -> MetaStringBytes
    meta_string_to_bytes: HashMap<MetaString, MetaStringBytes>,

    // dynamic arrays for write/read (用 Vec<Option<...>> 来模拟 Java 数组并能扩容)
    dynamic_written: Vec<Option<MetaStringBytes>>,
    dynamic_read: Vec<Option<MetaStringBytes>>,

    dynamic_write_id: usize,
    dynamic_read_id: usize,
}

impl MetaStringResolver {
    const INITIAL_CAPACITY: usize = 8;
    const SMALL_STRING_THRESHOLD: usize = 16;

    pub fn new() -> Self {
        MetaStringResolver {
            meta_string_bytes_to_string: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            hash_to_meta: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            small_map: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            meta_string_to_bytes: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            dynamic_written: vec![None; 32],
            dynamic_read: vec![None; 32],
            dynamic_write_id: 0,
            dynamic_read_id: 0,
        }
    }

    /// 将 MetaString -> MetaStringBytes 缓存/创建（类似 Java 的 getOrCreateMetaStringBytes）
    pub fn get_or_create_meta_string_bytes(&mut self, m: &MetaString) -> MetaStringBytes {
        if let Some(b) = self.meta_string_to_bytes.get(m) {
            return b.clone();
        }
        // 构造 MetaStringBytes
        let bytes = m.bytes.clone();
        // 计算 hash，使用你给的函数
        let hash_code = murmurhash3_x64_128(&bytes, 47).0 as i64;
        let encoding = m.encoding;
        // first8/second8：把 bytes 前 16 字节（不足填 0）打包为两个 u64（little endian）
        let mut first8: u64 = 0;
        let mut second8: u64 = 0;
        for i in 0..usize::min(8, bytes.len()) {
            first8 |= (bytes[i] as u64) << (8 * i);
        }
        if bytes.len() > 8 {
            for j in 0..usize::min(8, bytes.len() - 8) {
                second8 |= (bytes[8 + j] as u64) << (8 * j);
            }
        }
        let msb = MetaStringBytes::new(bytes.clone(), hash_code, encoding, first8, second8);
        self.meta_string_to_bytes.insert(m.clone(), msb.clone());
        msb
    }

    /// write with flag (对应 Java writeMetaStringBytesWithFlag)
    /// flag-version uses (len << 2) | ... semantics
    pub fn write_meta_string_bytes_with_flag(&mut self, w: &mut Writer, mut mb: MetaStringBytes) {
        let id = mb.dynamic_write_id;
        if id == MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID {
            // allocate id
            let id_usize = self.dynamic_write_id;
            self.dynamic_write_id += 1;
            mb.dynamic_write_id = id_usize as i16;
            // grow dynamic_written if needed
            if id_usize >= self.dynamic_written.len() {
                self.dynamic_written.resize(id_usize * 2, None);
            }
            self.dynamic_written[id_usize] = Some(mb.clone());

            let len = mb.bytes.len();
            // last bit `1` indicates class is written by name instead of registered id.
            let header = ((len as u32) << 2) | 0b1;
            w.write_varuint32(header);

            if len > Self::SMALL_STRING_THRESHOLD {
                w.write_i64(mb.hash_code);
            } else {
                w.write_u8(mb.encoding as i16 as u8);
            }
            w.write_bytes(&mb.bytes);
        } else {
            // write id reference: ((id + 1) << 2) | 0b11
            let header = (((id as u32 + 1) << 2) | 0b11) as u32;
            w.write_varuint32(header);
        }
    }

    /// write without flag (对应 Java writeMetaStringBytes)
    pub fn write_meta_string_bytes(&mut self, context: &mut WriteContext, ms: &MetaString) {
        let mut mb = MetaStringBytes::from_metastring(&ms);
        let id = mb.dynamic_write_id;
        if id == MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID {
            let id_usize = self.dynamic_write_id;
            self.dynamic_write_id += 1;
            mb.dynamic_write_id = id_usize as i16;
            if id_usize >= self.dynamic_written.len() {
                self.dynamic_written.resize(id_usize * 2 + 1, None);
            }
            self.dynamic_written[id_usize] = Some(mb.clone());

            let len = mb.bytes.len();
            context.writer.write_varuint32((len as u32) << 1);
            if len > Self::SMALL_STRING_THRESHOLD {
                context.writer.write_i64(mb.hash_code);
            } else {
                context.writer.write_u8(mb.encoding as i16 as u8);
            }
            context.writer.write_bytes(&mb.bytes);
        } else {
            let header = (((id as u32 + 1) << 1) | 1) as u32;
            context.writer.write_varuint32(header);
        }
    }

    /// read with flag: header 已经由 caller 读入（对应 Java readMetaStringBytesWithFlag）
    pub fn read_meta_string_bytes_with_flag(&mut self, context: &mut ReadContext, header: u32) -> MetaStringBytes {
        let len = (header >> 2) as usize;
        if (header & 0b10) == 0 {
            // by-name path
            if len <= Self::SMALL_STRING_THRESHOLD {
                let mb = self.read_small_meta_string_bytes(context, len);
                self.update_dynamic_string(mb.clone());
                mb
            } else {
                // long string: next is hash then bytes
                let hash_code = context.reader.read_i64();
                let mb = self.read_big_meta_string_bytes(context, len, hash_code);
                self.update_dynamic_string(mb.clone());
                mb
            }
        } else {
            // by-id path
            let idx = len - 1;
            self.dynamic_read[idx].as_ref().expect("dynamic id not found").clone()
        }
    }

    /// read without flag (读取 header 自己)
    pub fn read_meta_string_bytes(&mut self, context: &mut ReadContext) -> MetaStringBytes {
        let header = context.reader.read_varuint32();
        let len = (header >> 1) as usize;
        if (header & 0b1) == 0 {
            if len > Self::SMALL_STRING_THRESHOLD {
                let hash_code = context.reader.read_i64();
                let mb = self.read_big_meta_string_bytes(context, len, hash_code);
                self.update_dynamic_string(mb.clone());
                mb
            } else {
                let mb = self.read_small_meta_string_bytes(context, len);
                self.update_dynamic_string(mb.clone());
                mb
            }
        } else {
            let idx = len - 1;
            self.dynamic_read[idx].as_ref().expect("dynamic id not found").clone()
        }
    }

    /// helper: 读取 big string（通过 hash 决定是否复用）
    fn read_big_meta_string_bytes(&mut self, context: &mut ReadContext, len: usize, hash_code: i64) -> MetaStringBytes {
        if let Some(existing) = self.hash_to_meta.get(&hash_code) {
            // skip bytes
            context.reader.skip(len as u32);
            existing.clone()
        } else {
            let slice = context.reader.read_bytes(len);
            let bytes = slice.to_vec();
            let mb = {
                // compute first8/second8 like createSmall
                let mut first8: u64 = 0;
                let mut second8: u64 = 0;
                for i in 0..usize::min(8, bytes.len()) {
                    first8 |= (bytes[i] as u64) << (8 * i);
                }
                if bytes.len() > 8 {
                    for j in 0..usize::min(8, bytes.len() - 8) {
                        second8 |= (bytes[8 + j] as u64) << (8 * j);
                    }
                }
                MetaStringBytes::new(bytes, hash_code, Encoding::Utf8, first8, second8)
            };
            self.hash_to_meta.insert(hash_code, mb.clone());
            mb
        }
    }

    /// helper: 读取 small string（len ≤ SMALL_STRING_THRESHOLD）
    fn read_small_meta_string_bytes(&mut self, context: &mut ReadContext, len: usize) -> MetaStringBytes {
        let encoding_val = context.reader.read_u8();
        if len == 0 {
            // assert encoding is UTF-8
            debug_assert_eq!(encoding_val, Encoding::Utf8 as i16 as u8);
            return MetaStringBytes::EMPTY.clone();
        }
        // read v1, v2 similar to Java: if len <= 8 -> read bytes as little-endian u64 from those len bytes
        let (v1, v2) = if len <= 8 {
            let v1 = Self::read_bytes_as_u64(context, len);
            (v1, 0u64)
        } else {
            let v1 = context.reader.read_i64() as u64;
            let v2 = Self::read_bytes_as_u64(context, len - 8);
            (v1, v2)
        };
        let key = (v1, v2, encoding_val);
        if let Some(existing) = self.small_map.get(&key) {
            existing.clone()
        } else {
            // create
            // reconstruct bytes: we only know len; assemble from v1/v2
            let mut data = Vec::with_capacity(len);
            for i in 0..usize::min(8, len) {
                data.push(((v1 >> (8 * i)) & 0xFF) as u8);
            }
            if len > 8 {
                for j in 0..(len - 8) {
                    data.push(((v2 >> (8 * j)) & 0xFF) as u8);
                }
            }
            let mut data_trimmed = data;
            data_trimmed.truncate(len);
            let hash_code = (murmurhash3_x64_128(&data_trimmed, 47).0 as i64).abs();
            let hash_code = (hash_code as u64 & 0xffffffffffffff00_u64) as i64 | (encoding_val as i64);
            let mb = MetaStringBytes::new(data_trimmed.clone(), hash_code, byte_to_encoding(encoding_val), v1, v2);
            self.small_map.insert(key, mb.clone());
            mb
        }
    }

    /// helper: 从 reader 按 little-endian 把 len 字节读成 u64（不足 8 字节则低位放数据，高位 0）
    fn read_bytes_as_u64(context: &mut ReadContext, len: usize) -> u64 {
        let mut v: u64 = 0;
        let slice = context.reader.read_bytes(len);
        for i in 0..len {
            v |= (slice[i] as u64) << (8 * i);
        }
        v
    }

    /// update dynamic read array（类似 Java updateDynamicString）
    fn update_dynamic_string(&mut self, mb: MetaStringBytes) {
        let id = self.dynamic_read_id;
        self.dynamic_read_id += 1;
        if id >= self.dynamic_read.len() {
            self.dynamic_read.resize(id * 2 + 1, None);
        }
        self.dynamic_read[id] = Some(mb);
    }

    /// reset 两种状态
    pub fn reset(&mut self) {
        self.reset_read();
        self.reset_write();
    }

    pub fn reset_read(&mut self) {
        if self.dynamic_read_id != 0 {
            for i in 0..self.dynamic_read_id {
                self.dynamic_read[i] = None;
            }
            self.dynamic_read_id = 0;
        }
    }

    pub fn reset_write(&mut self) {
        if self.dynamic_write_id != 0 {
            for i in 0..self.dynamic_write_id {
                if let Some(ref mut mb) = self.dynamic_written[i] {
                    mb.dynamic_write_id = MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID;
                }
                self.dynamic_written[i] = None;
            }
            self.dynamic_write_id = 0;
        }
    }

    /// readMetaString: 读取并返回 decoded String（对应 Java 的 readMetaString）
    pub fn read_meta_string(&mut self, context: &mut ReadContext) -> String {
        let mb = self.read_meta_string_bytes(context);
        if let Some(s) = self.meta_string_bytes_to_string.get(&mb) {
            s.clone()
        } else {
            let s = mb.decode_lossy();
            self.meta_string_bytes_to_string.insert(mb, s.clone());
            s
        }
    }
}
