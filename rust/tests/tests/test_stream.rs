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

#[cfg(test)]
mod stream_tests {
    use fory_core::buffer::Reader;
    use fory_core::stream::ForyStreamBuf;
    use fory_core::Fory;
    use std::fmt::Debug;
    use std::io::{Cursor, Read};

    /// Reader that returns exactly one byte per read call.
    /// This stresses the streaming deserializer.
    struct OneByte(Cursor<Vec<u8>>);

    impl Read for OneByte {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if buf.is_empty() {
                return Ok(0);
            }

            let mut one = [0u8; 1];

            match self.0.read(&mut one)? {
                0 => Ok(0),
                _ => {
                    buf[0] = one[0];
                    Ok(1)
                }
            }
        }
    }

    /// Helper that verifies both in-memory and streaming paths produce identical results.
    fn deserialize_helper<T>(fory: &Fory, bytes: &[u8]) -> T
    where
        T: fory_core::Serializer + fory_core::ForyDefault + PartialEq + Debug,
    {
        let expected: T = fory
            .deserialize(bytes)
            .expect("in-memory deserialize failed");

        let actual: T = fory
            .deserialize_from_stream(OneByte(Cursor::new(bytes.to_vec())))
            .expect("stream deserialize failed");

        assert_eq!(
            expected, actual,
            "stream and in-memory deserialization results differ"
        );

        expected
    }

    // ── Primitive and String ────────────────────────────────────────────────

    #[test]
    fn test_primitive_and_string_round_trip() {
        let fory = Fory::default();

        let bytes = fory.serialize(&-9876543212345i64).unwrap();
        assert_eq!(deserialize_helper::<i64>(&fory, &bytes), -9876543212345i64);

        let bytes = fory.serialize(&"stream-hello-世界".to_string()).unwrap();
        assert_eq!(
            deserialize_helper::<String>(&fory, &bytes),
            "stream-hello-世界"
        );
    }

    #[test]
    fn test_additional_primitives() {
        let fory = Fory::default();

        let bytes = fory.serialize(&true).unwrap();
        assert!(deserialize_helper::<bool>(&fory, &bytes));

        let bytes = fory.serialize(&-42i32).unwrap();
        assert_eq!(deserialize_helper::<i32>(&fory, &bytes), -42i32);

        let bytes = fory.serialize(&std::f64::consts::PI).unwrap();
        assert_eq!(
            deserialize_helper::<f64>(&fory, &bytes),
            std::f64::consts::PI
        );
    }

    // ── Large values exercising multi-byte varint paths ─────────────────────

    #[test]
    fn test_varuint64_boundary_round_trip() {
        let fory = Fory::default();

        for val in [i64::MAX, i64::MIN, 1i64 << 56, -(1i64 << 56), i64::MAX - 1] {
            let bytes = fory.serialize(&val).unwrap();
            assert_eq!(
                deserialize_helper::<i64>(&fory, &bytes),
                val,
                "round-trip failed for {}",
                val
            );
        }
    }

    #[test]
    fn test_varuint36small_boundary_round_trip() {
        let fory = Fory::default();

        // (0..500) forces 2-byte varuint36small length encoding
        let large_vec: Vec<i32> = (0..500).collect();
        let bytes = fory.serialize(&large_vec).unwrap();

        assert_eq!(deserialize_helper::<Vec<i32>>(&fory, &bytes), large_vec);
    }

    // ── Vec round-trip ──────────────────────────────────────────────────────

    #[test]
    fn test_vec_round_trip() {
        let fory = Fory::default();
        let vec = vec![1i32, 2, 3, 5, 8];

        let bytes = fory.serialize(&vec).unwrap();

        assert_eq!(deserialize_helper::<Vec<i32>>(&fory, &bytes), vec);
    }

    // ── Struct round-trip ───────────────────────────────────────────────────

    #[derive(Debug, PartialEq, fory_derive::ForyObject)]
    struct Point {
        x: i32,
        y: i32,
    }

    #[test]
    fn test_struct_round_trip() {
        let mut fory = Fory::default();
        fory.register::<Point>(1).unwrap();

        let point = Point { x: 42, y: -7 };
        let bytes = fory.serialize(&point).unwrap();

        assert_eq!(deserialize_helper::<Point>(&fory, &bytes), point);
    }

    // ── Sequential multi-object stream decode ───────────────────────────────
    // FIX: added reader_index() == 0 assertions after each read,
    // mirroring C++ EXPECT_EQ(stream.get_buffer().reader_index(), 0U)

    #[test]
    fn test_sequential_stream_reads() {
        let fory = Fory::default();

        let mut bytes = Vec::new();

        fory.serialize_to(&mut bytes, &12345i32).unwrap();
        fory.serialize_to(&mut bytes, &"next-value".to_string())
            .unwrap();
        fory.serialize_to(&mut bytes, &99i64).unwrap();

        let mut reader = Reader::from_stream(ForyStreamBuf::new(OneByte(Cursor::new(bytes))));

        let first: i32 = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(first, 12345);
        // Mirrors C++: EXPECT_EQ(stream.get_buffer().reader_index(), 0U)
        assert_eq!(
            reader.stream_reader_index().unwrap(),
            0,
            "buffer must be compacted to 0 after first read"
        );

        let second: String = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(second, "next-value");
        // Mirrors C++: EXPECT_EQ(stream.get_buffer().reader_index(), 0U)
        assert_eq!(
            reader.stream_reader_index().unwrap(),
            0,
            "buffer must be compacted to 0 after second read"
        );

        let third: i64 = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(third, 99);
        // Mirrors C++: EXPECT_EQ(stream.get_buffer().reader_index(), 0U)
        assert_eq!(
            reader.stream_reader_index().unwrap(),
            0,
            "buffer must be compacted to 0 after third read"
        );

        // Mirrors C++: EXPECT_EQ(stream.get_buffer().remaining_size(), 0U)
        assert_eq!(
            reader.stream_remaining().unwrap(),
            0,
            "stream must be fully consumed"
        );
    }

    // ── Truncated stream must return Err ────────────────────────────────────
    // FIX: wrapped Cursor with OneByte to exercise streaming refill path,
    // matching C++ OneByteIStream usage in TruncatedStreamReturnsError

    #[test]
    fn test_truncated_stream_returns_error() {
        let fory = Fory::default();

        let mut bytes = fory.serialize(&"hello world".to_string()).unwrap();
        bytes.pop(); // corrupt the stream

        // FIX: OneByte wrapper added — was bare Cursor::new(bytes) before
        let result: Result<String, _> = fory.deserialize_from_stream(OneByte(Cursor::new(bytes)));

        assert!(result.is_err());
    }

    // ── shrink_buffer compaction behavior ───────────────────────────────────

    #[test]
    fn test_shrink_between_sequential_reads() {
        let fory = Fory::default();

        let mut bytes = Vec::new();

        fory.serialize_to(&mut bytes, &42i32).unwrap();
        fory.serialize_to(&mut bytes, &"shrink-test".to_string())
            .unwrap();
        fory.serialize_to(&mut bytes, &100i64).unwrap();

        let mut reader =
            Reader::from_stream(ForyStreamBuf::with_capacity(OneByte(Cursor::new(bytes)), 4));

        assert_eq!(fory.deserialize_from::<i32>(&mut reader).unwrap(), 42);
        assert_eq!(
            fory.deserialize_from::<String>(&mut reader).unwrap(),
            "shrink-test"
        );
        assert_eq!(fory.deserialize_from::<i64>(&mut reader).unwrap(), 100);
    }

    // ── ForyStreamBuf unit tests ────────────────────────────────────────────

    mod buf_tests {
        use fory_core::stream::ForyStreamBuf;
        use std::io::{Cursor, Read};

        struct OneByteCursor(Cursor<Vec<u8>>);

        impl Read for OneByteCursor {
            fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
                if buf.is_empty() {
                    return Ok(0);
                }

                let mut one = [0u8; 1];

                match self.0.read(&mut one)? {
                    0 => Ok(0),
                    _ => {
                        buf[0] = one[0];
                        Ok(1)
                    }
                }
            }
        }

        #[test]
        fn test_rewind_ok() {
            let mut s =
                ForyStreamBuf::with_capacity(OneByteCursor(Cursor::new(vec![1, 2, 3, 4, 5])), 2);

            s.fill_buffer(4).unwrap();
            s.consume(3).unwrap();

            assert_eq!(s.reader_index(), 3);

            s.rewind(2).unwrap();

            assert_eq!(s.reader_index(), 1);
        }

        #[test]
        fn test_rewind_err_on_overrun() {
            let mut s = ForyStreamBuf::new(Cursor::new(vec![1, 2]));
            s.fill_buffer(2).unwrap();
            s.consume(1).unwrap();

            assert!(s.rewind(2).is_err());
        }

        #[test]
        fn test_consume_err_on_overrun() {
            let mut s = ForyStreamBuf::new(Cursor::new(vec![1]));
            s.fill_buffer(1).unwrap();

            assert!(s.consume(2).is_err());
        }

        #[test]
        fn test_short_read_returns_error() {
            let mut s = ForyStreamBuf::new(Cursor::new(vec![1, 2, 3]));
            assert!(s.fill_buffer(4).is_err());
        }

        #[test]
        fn test_sequential_fill() {
            let data: Vec<u8> = (0u8..=9).collect();
            let mut s = ForyStreamBuf::with_capacity(OneByteCursor(Cursor::new(data)), 2);

            s.fill_buffer(3).unwrap();
            assert!(s.remaining() >= 3);

            s.consume(3).unwrap();

            s.fill_buffer(3).unwrap();
            assert!(s.remaining() >= 3);
        }

        #[test]
        fn test_shrink_phase1_compacts() {
            let mut s = ForyStreamBuf::new(Cursor::new(vec![0u8; 8]));
            s.fill_buffer(8).unwrap();
            s.consume(6).unwrap();

            s.shrink_buffer();

            assert_eq!(s.reader_index(), 0);
            assert_eq!(s.remaining(), 2);
        }
    }
}
