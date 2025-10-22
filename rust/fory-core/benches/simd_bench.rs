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

use fory_core::buffer::{Reader, Writer};
use fory_core::meta::buffer_rw_string::{
    read_latin1_simd, read_latin1_standard, write_latin1_simd, write_latin1_standard,
};
#[cfg(target_feature = "sse2")]
use std::arch::x86_64::*;

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

fn benchmark_write_latin1(c: &mut Criterion) {
    let sizes = [100, 1000, 10000, 100000];
    let ascii_string = "abcdefghijklmnopqrstuvwxyz0123456789";

    for &size in &sizes {
        let s_ascii = ascii_string.repeat(size / ascii_string.len() + 1);

        let name_simd = format!("Write Latin-1 SIMD size {}", size);
        c.bench_function(&name_simd, |b| {
            b.iter(|| {
                let mut w = Writer::default();
                write_latin1_simd(black_box(&mut w), black_box(&s_ascii));
            })
        });

        let name_scalar = format!("Write Latin-1 Standard size {}", size);
        c.bench_function(&name_scalar, |b| {
            b.iter(|| {
                let mut w = Writer::default();
                write_latin1_standard(black_box(&mut w), black_box(&s_ascii));
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
                read_latin1_simd(black_box(&mut reader), black_box(s_ascii.len())).unwrap();
            })
        });

        let name_scalar = format!("Read Latin-1 Standard size {}", size);
        c.bench_function(&name_scalar, |b| {
            b.iter(|| {
                let mut reader = Reader::new(black_box(&data));
                read_latin1_standard(black_box(&mut reader), black_box(s_ascii.len())).unwrap();
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

    benchmark_write_latin1(c);

    benchmark_read_latin1(c);
}

criterion_group!(benches, criterion_benchmark);
criterion_main!(benches);
