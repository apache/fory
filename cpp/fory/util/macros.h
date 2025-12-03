/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#pragma once

// Platform detection for SIMD
#if defined(__x86_64__) || defined(_M_X64)
#include <immintrin.h>
#define FORY_HAS_IMMINTRIN
#elif defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define FORY_HAS_NEON
#elif defined(__SSE2__)
#include <emmintrin.h>
#define FORY_HAS_SSE2
#elif defined(__riscv) && __riscv_vector
#include <riscv_vector.h>
#define FORY_HAS_RISCV_VECTOR
#endif

// Force-inline attribute for hot path functions
#if defined(__GNUC__) || defined(__clang__)
#define FORY_ALWAYS_INLINE __attribute__((always_inline)) inline
#define FORY_NOINLINE __attribute__((noinline))
#elif defined(_MSC_VER)
#define FORY_ALWAYS_INLINE __forceinline
#define FORY_NOINLINE __declspec(noinline)
#else
#define FORY_ALWAYS_INLINE inline
#define FORY_NOINLINE
#endif

// GCC branch prediction hints
#if defined(__GNUC__)
#define FORY_PREDICT_FALSE(x) (__builtin_expect(x, 0))
#define FORY_PREDICT_TRUE(x) (__builtin_expect(!!(x), 1))
#define FORY_NORETURN __attribute__((noreturn))
#define FORY_PREFETCH(addr) __builtin_prefetch(addr)
#elif defined(_MSC_VER)
#define FORY_NORETURN __declspec(noreturn)
#define FORY_PREDICT_FALSE(x) x
#define FORY_PREDICT_TRUE(x) x
#define FORY_PREFETCH(addr)
#else
#define FORY_NORETURN
#define FORY_PREDICT_FALSE(x) x
#define FORY_PREDICT_TRUE(x) x
#define FORY_PREFETCH(addr)
#endif

// Endianness detection
#ifdef _WIN32
#define FORY_LITTLE_ENDIAN 1
#else
#ifdef __APPLE__
#include <machine/endian.h>
#else
#include <endian.h>
#endif

#ifndef __BYTE_ORDER__
#error "__BYTE_ORDER__ not defined"
#endif

#ifndef __ORDER_LITTLE_ENDIAN__
#error "__ORDER_LITTLE_ENDIAN__ not defined"
#endif

#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#define FORY_LITTLE_ENDIAN 1
#else
#define FORY_LITTLE_ENDIAN 0
#endif
#endif

// Byte swap macros
#if defined(_MSC_VER)
#include <intrin.h>
#pragma intrinsic(_BitScanReverse)
#pragma intrinsic(_BitScanForward)
#define FORY_BYTE_SWAP64 _byteswap_uint64
#define FORY_BYTE_SWAP32 _byteswap_ulong
#else
#define FORY_BYTE_SWAP64 __builtin_bswap64
#define FORY_BYTE_SWAP32 __builtin_bswap32
#endif

// Target-specific function attributes
#if defined(__GNUC__) && !defined(_MSC_VER)
#define FORY_TARGET_AVX2_ATTR __attribute__((target("avx2")))
#else
#define FORY_TARGET_AVX2_ATTR
#endif
