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

use criterion::{black_box, criterion_group, criterion_main, Criterion};
#[cfg(target_feature = "avx2")]
use std::arch::x86_64::*;

#[cfg(target_feature = "sse2")]
use std::arch::x86_64::*;
use fory_core::buffer::{Reader, Writer};

#[cfg(target_feature = "avx2")]
pub(crate) const MIN_DIM_SIZE_AVX: usize = 32;

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
pub(crate) const MIN_DIM_SIZE_SIMD: usize = 16;

#[cfg(target_feature = "sse2")]
unsafe fn is_latin_sse(s: &str) -> bool {
    let bytes = s.as_bytes();
    let len = s.len();
    let ascii_mask = _mm_set1_epi8(0x80u8 as i8);
    let remaining = len % MIN_DIM_SIZE_SIMD;
    let range_end = len - remaining;
    for i in (0..range_end).step_by(MIN_DIM_SIZE_SIMD) {
        let chunk = _mm_loadu_si128(bytes.as_ptr().add(i) as *const __m128i);
        let masked = _mm_and_si128(chunk, ascii_mask);
        let cmp = _mm_cmpeq_epi8(masked, _mm_setzero_si128());
        if _mm_movemask_epi8(cmp) != 0xFFFF {
            return false;
        }
    }
    for item in bytes.iter().take(range_end).skip(range_end) {
        if !item.is_ascii() {
            return false;
        }
    }
    true
}

#[cfg(target_feature = "avx2")]
unsafe fn is_latin_avx(s: &str) -> bool {
    let bytes = s.as_bytes();
    let len = s.len();
    let ascii_mask = _mm256_set1_epi8(0x80u8 as i8);
    let remaining = len % MIN_DIM_SIZE_AVX;
    let range_end = len - remaining;
    for i in (0..(len - remaining)).step_by(MIN_DIM_SIZE_AVX) {
        let chunk = _mm256_loadu_si256(bytes.as_ptr().add(i) as *const __m256i);
        let masked = _mm256_and_si256(chunk, ascii_mask);
        let cmp = _mm256_cmpeq_epi8(masked, _mm256_setzero_si256());
        if _mm256_movemask_epi8(cmp) != 0xFFFF {
            return false;
        }
    }
    for item in bytes.iter().take(range_end).skip(range_end) {
        if !item.is_ascii() {
            return false;
        }
    }
    true
}

fn is_latin_std(s: &str) -> bool {
    s.bytes().all(|b| b.is_ascii())
}

fn benchmark_write_utf8(c: &mut Criterion) {
    let sizes = [100, 1000, 10000, 100000];
    for &size in &sizes {
        let s = "Hello, 世界! 🌍".repeat(size);

        let name_simd = format!("UTF-8 SIMD size {}", size);
        c.bench_function(&name_simd, |b| {
            b.iter(|| {
                let mut w = Writer::default();
                w.write_utf8_string(black_box(&s));
                black_box(w);
            })
        });

        let name_scalar = format!("UTF-8 Standard size {}", size);
        c.bench_function(&name_scalar, |b| {
            b.iter(|| {
                let mut w = Writer::default();
                w.write_utf8_string_standard(black_box(&s));
                black_box(w);
            })
        });
    }
}

fn benchmark_write_latin1(c: &mut Criterion) {
    let sizes = [100, 1000, 10000, 100000];
    let ascii_string = "abcdefghijklmnopqrstuvwxyz0123456789";

    for &size in &sizes {
        let s_ascii = ascii_string.repeat(size / ascii_string.len() + 1);

        let name_simd = format!("Latin-1 SIMD size {}", size);
        c.bench_function(&name_simd, |b| {
            b.iter(|| {
                let mut w = Writer::default();
                w.write_latin1_string(black_box(&s_ascii));
                black_box(w);
            })
        });

        let name_scalar = format!("Latin-1 Standard size {}", size);
        c.bench_function(&name_scalar, |b| {
            b.iter(|| {
                let mut w = Writer::default();
                w.write_latin1_string_standard(black_box(&s_ascii));
                black_box(w);
            })
        });
    }
}

fn benchmark_read_latin1(c: &mut Criterion) {
    let sizes = [100, 1000, 10000, 100000];
    let ascii_string = "abcdefghijklmnopqrstuvwxyz0123456789";

    for &size in &sizes {
        let s_ascii = ascii_string.repeat(size / ascii_string.len() + 1);
        let mut writer = Writer::default();
        writer.write_latin1_string(&s_ascii);
        let data = writer.dump();

        let name_simd = format!("Read Latin-1 SIMD size {}", size);
        c.bench_function(&name_simd, |b| {
            b.iter(|| {
                let mut reader = Reader::new(black_box(&data));
                let result = reader.read_latin1_string(black_box(s_ascii.len()));
                black_box(result);
            })
        });

        let name_scalar = format!("Read Latin-1 Standard size {}", size);
        c.bench_function(&name_scalar, |b| {
            b.iter(|| {
                let mut reader = Reader::new(black_box(&data));
                let result = reader.read_latin1_string_standard(black_box(s_ascii.len()));
                black_box(result);
            })
        });
    }
}

fn benchmark_read_utf8(c: &mut Criterion) {
    let sizes = [100, 1000, 10000, 100000];
    let test_string = "Hello, 世界! 🌍";

    for &size in &sizes {
        let s = test_string.repeat(size / test_string.len() + 1);
        let mut writer = Writer::default();
        writer.write_utf8_string(&s);
        let data = writer.dump();

        let name_simd = format!("Read UTF-8 SIMD size {}", size);
        c.bench_function(&name_simd, |b| {
            b.iter(|| {
                let mut reader = Reader::new(black_box(&data));
                let result = reader.read_utf8_string_simd(black_box(s.len()));
                black_box(result);
            })
        });

        let name_scalar = format!("Read UTF-8 Standard size {}", size);
        c.bench_function(&name_scalar, |b| {
            b.iter(|| {
                let mut reader = Reader::new(black_box(&data));
                let result = reader.read_utf8_string_standard(black_box(s.len()));
                black_box(result);
            })
        });
    }
}

fn benchmark_read_utf16(c: &mut Criterion) {
    let sizes = [100, 1000, 10000, 100000];
    let test_string = "Hello, 世界! 🌍";

    for &size in &sizes {
        let s = test_string.repeat(size / test_string.len() + 1);
        // 构造 UTF-16 LE 字节数据
        let mut data: Vec<u8> = Vec::with_capacity(s.len() * 2);
        for u in s.encode_utf16() {
            let lo = (u & 0x00FF) as u8;
            let hi = (u >> 8) as u8;
            data.push(lo);
            data.push(hi);
        }

        let name_simd = format!("Read UTF-16 SIMD size {}", size);
        c.bench_function(&name_simd, |b| {
            b.iter(|| {
                let mut reader = Reader::new(black_box(&data));
                let result = reader.read_utf16_string_simd(black_box(data.len()));
                black_box(result);
            })
        });

        let name_scalar = format!("Read UTF-16 Standard size {}", size);
        c.bench_function(&name_scalar, |b| {
            b.iter(|| {
                let mut reader = Reader::new(black_box(&data));
                let result = reader.read_utf16_string(black_box(data.len()));
                black_box(result);
            })
        });
    }
}

fn criterion_benchmark(c: &mut Criterion) {
    let test_str_short = "Hello, World!";
    let test_str_long = "Hello, World! ".repeat(1000);

    #[cfg(target_feature = "sse2")]
    c.bench_function("SIMD sse short", |b| {
        b.iter(|| unsafe { is_latin_sse(black_box(test_str_short)) })
    });
    #[cfg(target_feature = "sse2")]
    c.bench_function("SIMD sse long", |b| {
        b.iter(|| unsafe { is_latin_sse(black_box(&test_str_long)) })
    });
    #[cfg(target_feature = "avx2")]
    c.bench_function("SIMD avx short", |b| {
        b.iter(|| unsafe { is_latin_avx(black_box(test_str_short)) })
    });
    #[cfg(target_feature = "avx2")]
    c.bench_function("SIMD avx long", |b| {
        b.iter(|| unsafe { is_latin_avx(black_box(&test_str_long)) })
    });

    c.bench_function("Standard short", |b| {
        b.iter(|| is_latin_std(black_box(test_str_short)))
    });
    
    c.bench_function("Standard long", |b| {
        b.iter(|| is_latin_std(black_box(&test_str_long)))
    });
    
    benchmark_write_utf8(c);
    benchmark_write_latin1(c);

    benchmark_read_latin1(c);
    benchmark_read_utf8(c);
    
    benchmark_read_utf16(c);
}

criterion_group!(benches, criterion_benchmark);
criterion_main!(benches);
