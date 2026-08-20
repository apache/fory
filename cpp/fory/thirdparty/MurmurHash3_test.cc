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

#include "fory/thirdparty/MurmurHash3.h"

#include <array>
#include <cstdint>
#include <cstring>

#include "gtest/gtest.h"

namespace fory {

TEST(MurmurHash3Test, AcceptsUnalignedBuffers) {
  const std::array<uint8_t, 32> payload = {
      0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x61, 0x62,
      0x63, 0x64, 0x65, 0x66, 0x31, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76,
      0x77, 0x78, 0x79, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x32,
  };
  std::array<uint8_t, 33> unaligned_input{};
  std::memcpy(unaligned_input.data() + 1, payload.data(), payload.size());

  uint32_t aligned32 = 0;
  std::array<uint8_t, sizeof(uint32_t) + 1> unaligned32{};
  MurmurHash3_x86_32(payload.data(), static_cast<int>(payload.size()), 47,
                     &aligned32);
  MurmurHash3_x86_32(unaligned_input.data() + 1,
                     static_cast<int>(payload.size()), 47,
                     unaligned32.data() + 1);
  EXPECT_EQ(std::memcmp(&aligned32, unaligned32.data() + 1, sizeof(aligned32)),
            0);

  std::array<uint32_t, 4> aligned_x86_128{};
  std::array<uint8_t, sizeof(aligned_x86_128) + 1> unaligned_x86_128{};
  MurmurHash3_x86_128(payload.data(), static_cast<int>(payload.size()), 47,
                      aligned_x86_128.data());
  MurmurHash3_x86_128(unaligned_input.data() + 1,
                      static_cast<int>(payload.size()), 47,
                      unaligned_x86_128.data() + 1);
  EXPECT_EQ(std::memcmp(aligned_x86_128.data(), unaligned_x86_128.data() + 1,
                        sizeof(aligned_x86_128)),
            0);

  std::array<uint64_t, 2> aligned_x64_128{};
  std::array<uint8_t, sizeof(aligned_x64_128) + 1> unaligned_x64_128{};
  MurmurHash3_x64_128(payload.data(), static_cast<int>(payload.size()), 47,
                      aligned_x64_128.data());
  MurmurHash3_x64_128(unaligned_input.data() + 1,
                      static_cast<int>(payload.size()), 47,
                      unaligned_x64_128.data() + 1);
  EXPECT_EQ(std::memcmp(aligned_x64_128.data(), unaligned_x64_128.data() + 1,
                        sizeof(aligned_x64_128)),
            0);
}

} // namespace fory
