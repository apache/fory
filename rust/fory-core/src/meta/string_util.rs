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

use std::mem;

#[cfg(target_feature = "neon")]
use std::arch::aarch64::*;

#[cfg(target_feature = "avx2")]
use std::arch::x86_64::*;

#[cfg(target_feature = "sse2")]
use std::arch::x86_64::*;

#[cfg(target_arch = "x86_64")]
pub const MIN_DIM_SIZE_AVX: usize = 32;

#[cfg(any(
    target_arch = "x86",
    target_arch = "x86_64",
    all(target_arch = "aarch64", target_feature = "neon")
))]
pub const MIN_DIM_SIZE_SIMD: usize = 16;

#[cfg(target_arch = "x86_64")]
unsafe fn is_latin_avx(s: &str) -> bool {
    let bytes = s.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    // SIMD skip ASCII
    while i + MIN_DIM_SIZE_AVX <= len {
        let chunk = _mm256_loadu_si256(bytes.as_ptr().add(i) as *const __m256i);
        let hi_mask = _mm256_set1_epi8(0x80u8 as i8);
        let masked = _mm256_and_si256(chunk, hi_mask);
        let cmp = _mm256_cmpeq_epi8(masked, _mm256_setzero_si256());
        if _mm256_movemask_epi8(cmp) != -1 {
            break;
        }
        i += MIN_DIM_SIZE_AVX;
    }
    // check latin in remaining chars
    let s_tail = &s[i..];
    for c in s_tail.chars() {
        if c as u32 > 0xFF {
            return false;
        }
    }
    true
}

#[cfg(target_feature = "sse2")]
unsafe fn is_latin_sse(s: &str) -> bool {
    let bytes = s.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    // SIMD skip ASCII
    while i + MIN_DIM_SIZE_SIMD <= len {
        let chunk = _mm_loadu_si128(bytes.as_ptr().add(i) as *const __m128i);
        let hi_mask = _mm_set1_epi8(0x80u8 as i8);
        let masked = _mm_and_si128(chunk, hi_mask);
        let cmp = _mm_cmpeq_epi8(masked, _mm_setzero_si128());
        if _mm_movemask_epi8(cmp) != 0xFFFF {
            break;
        }
        i += MIN_DIM_SIZE_SIMD;
    }
    // check latin in remaining chars
    let s_tail = &s[i..];
    for c in s_tail.chars() {
        if c as u32 > 0xFF {
            return false;
        }
    }
    true
}

#[cfg(target_feature = "neon")]
unsafe fn is_latin_neon(s: &str) -> bool {
    let bytes = s.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    // SIMD skip ASCII
    while i + MIN_DIM_SIZE_SIMD <= len {
        let chunk = vld1q_u8(bytes.as_ptr().add(i));
        let hi_mask = vdupq_n_u8(0x80);
        let masked = vandq_u8(chunk, hi_mask);
        if vmaxvq_u8(masked) != 0 {
            break;
        }
        i += MIN_DIM_SIZE_SIMD;
    }
    // check latin in remaining chars
    let s_tail = &s[i..];
    for c in s_tail.chars() {
        if c as u32 > 0xFF {
            return false;
        }
    }
    true
}

fn is_latin_standard(s: &str) -> bool {
    s.chars().all(|c| c as u32 <= 0xFF)
}

pub fn is_latin(s: &str) -> bool {
    #[cfg(target_arch = "x86_64")]
    {
        if is_x86_feature_detected!("avx")
            && is_x86_feature_detected!("fma")
            && s.len() >= MIN_DIM_SIZE_AVX
        {
            return unsafe { is_latin_avx(s) };
        }
    }

    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        if is_x86_feature_detected!("sse") && s.len() >= MIN_DIM_SIZE_SIMD {
            return unsafe { is_latin_sse(s) };
        }
    }

    #[cfg(all(target_arch = "aarch64", target_feature = "neon"))]
    {
        if std::arch::is_aarch64_feature_detected!("neon") && s.len() >= MIN_DIM_SIZE_SIMD {
            return unsafe { is_latin_neon(s) };
        }
    }
    is_latin_standard(s)
}

#[cfg(target_arch = "x86_64")]
unsafe fn get_latin1_length_avx(s: &str) -> i32 {
    let bytes = s.as_bytes();
    let len = bytes.len();
    let mut count = 0;
    // SIMD skip ASCII
    while count + MIN_DIM_SIZE_AVX <= len {
        let chunk = _mm256_loadu_si256(bytes.as_ptr().add(count) as *const __m256i);
        let hi_mask = _mm256_set1_epi8(0x80u8 as i8);
        let masked = _mm256_and_si256(chunk, hi_mask);
        let cmp = _mm256_cmpeq_epi8(masked, _mm256_setzero_si256());
        if _mm256_movemask_epi8(cmp) != -1 {
            break;
        }
        count += MIN_DIM_SIZE_AVX;
    }
    // check latin in remaining chars
    let s_tail = &s[count..];
    for c in s_tail.chars() {
        if c as u32 > 0xFF {
            return -1;
        }
        count += 1;
    }
    count as i32
}

#[cfg(target_feature = "sse2")]
unsafe fn get_latin1_length_sse(s: &str) -> i32 {
    let bytes = s.as_bytes();
    let len = bytes.len();
    let mut count = 0;
    // SIMD skip ASCII
    while count + MIN_DIM_SIZE_SIMD <= len {
        let chunk = _mm_loadu_si128(bytes.as_ptr().add(count) as *const __m128i);
        let hi_mask = _mm_set1_epi8(0x80u8 as i8);
        let masked = _mm_and_si128(chunk, hi_mask);
        let cmp = _mm_cmpeq_epi8(masked, _mm_setzero_si128());
        if _mm_movemask_epi8(cmp) != 0xFFFF {
            break;
        }
        count += MIN_DIM_SIZE_SIMD;
    }
    // check latin in remaining chars
    let s_tail = &s[count..];
    for c in s_tail.chars() {
        if c as u32 > 0xFF {
            return -1;
        }
        count += 1;
    }
    count as i32
}

#[cfg(target_feature = "neon")]
unsafe fn get_latin1_length_neon(s: &str) -> i32 {
    let bytes = s.as_bytes();
    let len = bytes.len();
    let mut count = 0;
    // SIMD skip ASCII
    while count + MIN_DIM_SIZE_SIMD <= len {
        let chunk = vld1q_u8(bytes.as_ptr().add(count));
        let hi_mask = vdupq_n_u8(0x80);
        let masked = vandq_u8(chunk, hi_mask);
        if vmaxvq_u8(masked) != 0 {
            break;
        }
        count += MIN_DIM_SIZE_SIMD;
    }
    // check latin in remaining chars
    let s_tail = &s[count..];
    for c in s_tail.chars() {
        if c as u32 > 0xFF {
            return -1;
        }
        count += 1;
    }
    count as i32
}

fn get_latin1_length_standard(s: &str) -> i32 {
    let mut count = 0;
    for c in s.chars() {
        if c as u32 > 0xFF {
            return -1;
        }
        count += 1;
    }
    count
}

pub fn get_latin1_length(s: &str) -> i32 {
    #[cfg(target_arch = "x86_64")]
    {
        if is_x86_feature_detected!("avx")
            && is_x86_feature_detected!("fma")
            && s.len() >= MIN_DIM_SIZE_AVX
        {
            return unsafe { get_latin1_length_avx(s) };
        }
    }

    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        if is_x86_feature_detected!("sse") && s.len() >= MIN_DIM_SIZE_SIMD {
            return unsafe { get_latin1_length_sse(s) };
        }
    }

    #[cfg(all(target_arch = "aarch64", target_feature = "neon"))]
    {
        if std::arch::is_aarch64_feature_detected!("neon") && s.len() >= MIN_DIM_SIZE_SIMD {
            return unsafe { get_latin1_length_neon(s) };
        }
    }
    get_latin1_length_standard(s)
}

#[cfg(test)]
mod tests {
    // Import content from external modules
    use super::*;
    use rand::Rng;

    fn generate_random_string(length: usize) -> String {
        const CHARSET: &[u8] = b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        let mut rng = rand::thread_rng();

        let result: String = (0..length)
            .map(|_| {
                let idx = rng.gen_range(0..CHARSET.len());
                CHARSET[idx] as char
            })
            .collect();

        result
    }

    #[test]
    fn test_is_latin() {
        let s = generate_random_string(1000);
        let not_latin_str = generate_random_string(1000) + "abc\u{1234}";

        #[cfg(target_arch = "x86_64")]
        {
            if is_x86_feature_detected!("avx") && is_x86_feature_detected!("fma") {
                assert!(unsafe { is_latin_avx(&s) });
                assert!(!unsafe { is_latin_avx(&not_latin_str) });
            }
        }

        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        {
            if is_x86_feature_detected!("sse") && s.len() >= MIN_DIM_SIZE_SIMD {
                assert!(unsafe { is_latin_sse(&s) });
                assert!(!unsafe { is_latin_sse(&not_latin_str) });
            }
        }

        #[cfg(all(target_arch = "aarch64", target_feature = "neon"))]
        {
            if std::arch::is_aarch64_feature_detected!("neon") && s.len() >= MIN_DIM_SIZE_SIMD {
                assert!(unsafe { is_latin_neon(&s) });
                assert!(!unsafe { is_latin_neon(&not_latin_str) });
            }
        }
        assert!(is_latin_standard(&s));
        assert!(!is_latin_standard(&not_latin_str));
    }
}

fn fmix64(mut k: u64) -> u64 {
    k ^= k >> 33;
    k = k.wrapping_mul(0xff51afd7ed558ccdu64);
    k ^= k >> 33;
    k = k.wrapping_mul(0xc4ceb9fe1a85ec53u64);
    k ^= k >> 33;

    k
}

pub fn murmurhash3_x64_128(bytes: &[u8], seed: u64) -> (u64, u64) {
    let c1 = 0x87c37b91114253d5u64;
    let c2 = 0x4cf5ad432745937fu64;
    let read_size = 16;
    let len = bytes.len() as u64;
    let block_count = len / read_size;

    let (mut h1, mut h2) = (seed, seed);

    for i in 0..block_count as usize {
        let b64: &[u64] = unsafe { mem::transmute(bytes) };
        let (mut k1, mut k2) = (b64[i * 2], b64[i * 2 + 1]);

        k1 = k1.wrapping_mul(c1);
        k1 = k1.rotate_left(31);
        k1 = k1.wrapping_mul(c2);
        h1 ^= k1;

        h1 = h1.rotate_left(27);
        h1 = h1.wrapping_add(h2);
        h1 = h1.wrapping_mul(5);
        h1 = h1.wrapping_add(0x52dce729);

        k2 = k2.wrapping_mul(c2);
        k2 = k2.rotate_left(33);
        k2 = k2.wrapping_mul(c1);
        h2 ^= k2;

        h2 = h2.rotate_left(31);
        h2 = h2.wrapping_add(h1);
        h2 = h2.wrapping_mul(5);
        h2 = h2.wrapping_add(0x38495ab5);
    }
    let (mut k1, mut k2) = (0u64, 0u64);

    if len & 15 == 15 {
        k2 ^= (bytes[(block_count * read_size) as usize + 14] as u64) << 48;
    }
    if len & 15 >= 14 {
        k2 ^= (bytes[(block_count * read_size) as usize + 13] as u64) << 40;
    }
    if len & 15 >= 13 {
        k2 ^= (bytes[(block_count * read_size) as usize + 12] as u64) << 32;
    }
    if len & 15 >= 12 {
        k2 ^= (bytes[(block_count * read_size) as usize + 11] as u64) << 24;
    }
    if len & 15 >= 11 {
        k2 ^= (bytes[(block_count * read_size) as usize + 10] as u64) << 16;
    }
    if len & 15 >= 10 {
        k2 ^= (bytes[(block_count * read_size) as usize + 9] as u64) << 8;
    }
    if len & 15 >= 9 {
        k2 ^= bytes[(block_count * read_size) as usize + 8] as u64;
        k2 = k2.wrapping_mul(c2);
        k2 = k2.rotate_left(33);
        k2 = k2.wrapping_mul(c1);
        h2 ^= k2;
    }

    if len & 15 >= 8 {
        k1 ^= (bytes[(block_count * read_size) as usize + 7] as u64) << 56;
    }
    if len & 15 >= 7 {
        k1 ^= (bytes[(block_count * read_size) as usize + 6] as u64) << 48;
    }
    if len & 15 >= 6 {
        k1 ^= (bytes[(block_count * read_size) as usize + 5] as u64) << 40;
    }
    if len & 15 >= 5 {
        k1 ^= (bytes[(block_count * read_size) as usize + 4] as u64) << 32;
    }
    if len & 15 >= 4 {
        k1 ^= (bytes[(block_count * read_size) as usize + 3] as u64) << 24;
    }
    if len & 15 >= 3 {
        k1 ^= (bytes[(block_count * read_size) as usize + 2] as u64) << 16;
    }
    if len & 15 >= 2 {
        k1 ^= (bytes[(block_count * read_size) as usize + 1] as u64) << 8;
    }
    if len & 15 >= 1 {
        k1 ^= bytes[(block_count * read_size) as usize] as u64;
        k1 = k1.wrapping_mul(c1);
        k1 = k1.rotate_left(31);
        k1 = k1.wrapping_mul(c2);
        h1 ^= k1;
    }

    h1 ^= bytes.len() as u64;
    h2 ^= bytes.len() as u64;

    h1 = h1.wrapping_add(h2);
    h2 = h2.wrapping_add(h1);

    h1 = fmix64(h1);
    h2 = fmix64(h2);

    h1 = h1.wrapping_add(h2);
    h2 = h2.wrapping_add(h1);

    (h1, h2)
}

#[cfg(test)]
mod test_hash {
    use super::murmurhash3_x64_128;

    #[test]
    fn test_empty_string() {
        assert!(murmurhash3_x64_128("".as_bytes(), 0) == (0, 0));
    }

    #[test]
    fn test_tail_lengths() {
        assert!(
            murmurhash3_x64_128("1".as_bytes(), 0) == (8213365047359667313, 10676604921780958775)
        );
        assert!(
            murmurhash3_x64_128("12".as_bytes(), 0) == (5355690773644049813, 9855895140584599837)
        );
        assert!(
            murmurhash3_x64_128("123".as_bytes(), 0) == (10978418110857903978, 4791445053355511657)
        );
        assert!(
            murmurhash3_x64_128("1234".as_bytes(), 0) == (619023178690193332, 3755592904005385637)
        );
        assert!(
            murmurhash3_x64_128("12345".as_bytes(), 0)
                == (2375712675693977547, 17382870096830835188)
        );
        assert!(
            murmurhash3_x64_128("123456".as_bytes(), 0)
                == (16435832985690558678, 5882968373513761278)
        );
        assert!(
            murmurhash3_x64_128("1234567".as_bytes(), 0)
                == (3232113351312417698, 4025181827808483669)
        );
        assert!(
            murmurhash3_x64_128("12345678".as_bytes(), 0)
                == (4272337174398058908, 10464973996478965079)
        );
        assert!(
            murmurhash3_x64_128("123456789".as_bytes(), 0)
                == (4360720697772133540, 11094893415607738629)
        );
        assert!(
            murmurhash3_x64_128("123456789a".as_bytes(), 0)
                == (12594836289594257748, 2662019112679848245)
        );
        assert!(
            murmurhash3_x64_128("123456789ab".as_bytes(), 0)
                == (6978636991469537545, 12243090730442643750)
        );
        assert!(
            murmurhash3_x64_128("123456789abc".as_bytes(), 0)
                == (211890993682310078, 16480638721813329343)
        );
        assert!(
            murmurhash3_x64_128("123456789abcd".as_bytes(), 0)
                == (12459781455342427559, 3193214493011213179)
        );
        assert!(
            murmurhash3_x64_128("123456789abcde".as_bytes(), 0)
                == (12538342858731408721, 9820739847336455216)
        );
        assert!(
            murmurhash3_x64_128("123456789abcdef".as_bytes(), 0)
                == (9165946068217512774, 2451472574052603025)
        );
        assert!(
            murmurhash3_x64_128("123456789abcdef1".as_bytes(), 0)
                == (9259082041050667785, 12459473952842597282)
        );
    }

    #[test]
    fn test_large_data() {
        assert!(murmurhash3_x64_128("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Etiam at consequat massa. Cras eleifend pellentesque ex, at dignissim libero maximus ut. Sed eget nulla felis".as_bytes(), 0)
            == (9455322759164802692, 17863277201603478371));
    }
}
