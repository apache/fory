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
    use fory_derive::ForyObject;
    use std::collections::HashMap;
    use std::fmt::Debug;
    use std::io::{Cursor, Read};
    use std::sync::Arc;

    // ── OneByte ──────────────────────────────────────────────────────────────
    // Mirrors C++ OneByteIStream — returns exactly 1 byte per read()

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

    // ── deserialize_helper ────────────────────────────────────────────────────
    // Verifies both in-memory and streaming paths produce identical results.

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

    // ── Structs — mirrors C++ StreamPoint, StreamEnvelope, SharedIntPair ─────

    #[derive(ForyObject, Debug, PartialEq, Clone)]
    struct StreamPoint {
        x: i32,
        y: i32,
    }

    #[derive(ForyObject, Debug, PartialEq, Clone)]
    struct StreamEnvelope {
        name: String,
        values: Vec<i32>,
        metrics: HashMap<String, i64>,
        point: StreamPoint,
        active: bool,
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct SharedIntPair {
        first: Arc<i32>,
        second: Arc<i32>,
    }

    // Mirrors C++ register_stream_types()
    fn register_stream_types(fory: &mut Fory) {
        fory.register::<StreamPoint>(1).unwrap();
        fory.register::<StreamEnvelope>(2).unwrap();
        fory.register::<SharedIntPair>(3).unwrap();
    }

    // ── TEST 1 ────────────────────────────────────────────────────────────────
    // C++: TEST(StreamSerializationTest, PrimitiveAndStringRoundTrip)

    #[test]
    fn test_primitive_and_string_round_trip() {
        let fory = Fory::default();

        let bytes = fory.serialize(&-9876543212345i64).unwrap();
        assert_eq!(deserialize_helper::<i64>(&fory, &bytes), -9876543212345i64);

        let bytes = fory.serialize(&"stream-hello-".to_string()).unwrap();
        assert_eq!(deserialize_helper::<String>(&fory, &bytes), "stream-hello-");
    }

    // ── TEST 2 ────────────────────────────────────────────────────────────────
    // C++: TEST(StreamSerializationTest, StructRoundTrip)
    // C++ uses ForyInputStream(source, 4)

    #[test]
    fn test_struct_round_trip() {
        let mut fory = Fory::default().track_ref(true);
        register_stream_types(&mut fory);

        // Mirrors C++:
        // StreamEnvelope original{"payload-name", {1,3,5,7,9},
        //     {{"count",5},{"sum",25},{"max",9}}, {42,-7}, true}
        let original = StreamEnvelope {
            name: "payload-name".to_string(),
            values: vec![1, 3, 5, 7, 9],
            metrics: [
                ("count".to_string(), 5i64),
                ("sum".to_string(), 25i64),
                ("max".to_string(), 9i64),
            ]
            .into_iter()
            .collect(),
            point: StreamPoint { x: 42, y: -7 },
            active: true,
        };

        let bytes = fory.serialize(&original).unwrap();

        // Mirrors C++: ForyInputStream stream(source, 4)
        let mut reader =
            Reader::from_stream(ForyStreamBuf::with_capacity(OneByte(Cursor::new(bytes)), 4));
        let result: StreamEnvelope = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(result, original);
    }

    // ── TEST 3 ────────────────────────────────────────────────────────────────
    // C++: TEST(StreamSerializationTest, SequentialDeserializeFromSingleStream)
    // C++ uses ForyInputStream(source, 3)

    #[test]
    fn test_sequential_stream_reads() {
        let mut fory = Fory::default().track_ref(true);
        register_stream_types(&mut fory);

        // Mirrors C++:
        // StreamEnvelope envelope{"batch", {10,20,30},
        //     {{"a",1},{"b",2}}, {9,8}, false}
        let envelope = StreamEnvelope {
            name: "batch".to_string(),
            values: vec![10, 20, 30],
            metrics: [("a".to_string(), 1i64), ("b".to_string(), 2i64)]
                .into_iter()
                .collect(),
            point: StreamPoint { x: 9, y: 8 },
            active: false,
        };

        let mut bytes = Vec::new();
        fory.serialize_to(&mut bytes, &12345i32).unwrap();
        fory.serialize_to(&mut bytes, &"next-value".to_string())
            .unwrap();
        fory.serialize_to(&mut bytes, &envelope).unwrap();

        // Mirrors C++: ForyInputStream stream(source, 3)
        let mut reader =
            Reader::from_stream(ForyStreamBuf::with_capacity(OneByte(Cursor::new(bytes)), 3));

        let first: i32 = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(first, 12345);
        // Mirrors C++: EXPECT_EQ(stream.get_buffer().reader_index(), 0U)
        assert_eq!(reader.stream_reader_index().unwrap(), 0);

        let second: String = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(second, "next-value");
        assert_eq!(reader.stream_reader_index().unwrap(), 0);

        let third: StreamEnvelope = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(third, envelope);
        assert_eq!(reader.stream_reader_index().unwrap(), 0);

        // Mirrors C++: EXPECT_EQ(stream.get_buffer().remaining_size(), 0U)
        assert_eq!(reader.stream_remaining().unwrap(), 0);
    }

    // ── TEST 4 ────────────────────────────────────────────────────────────────
    // C++: TEST(StreamSerializationTest, SharedPointerIdentityRoundTrip)
    // C++ uses ForyInputStream(source, 2)

    #[test]
    fn test_shared_pointer_identity_round_trip() {
        let mut fory = Fory::default().track_ref(true);
        register_stream_types(&mut fory);

        // Mirrors C++:
        // auto shared = std::make_shared<int32_t>(2026);
        // SharedIntPair pair{shared, shared};  ← same pointer
        let shared = Arc::new(2026i32);
        let pair = SharedIntPair {
            first: Arc::clone(&shared),
            second: Arc::clone(&shared),
        };

        let bytes = fory.serialize(&pair).unwrap();

        // Mirrors C++: ForyInputStream stream(source, 2)
        let mut reader =
            Reader::from_stream(ForyStreamBuf::with_capacity(OneByte(Cursor::new(bytes)), 2));
        let result: SharedIntPair = fory.deserialize_from(&mut reader).unwrap();

        // Mirrors C++: ASSERT_NE(result.value().first, nullptr)
        assert_ne!(Arc::as_ptr(&result.first), std::ptr::null());
        assert_ne!(Arc::as_ptr(&result.second), std::ptr::null());

        // Mirrors C++: EXPECT_EQ(*result.value().first, 2026)
        assert_eq!(*result.first, 2026);

        // Mirrors C++: EXPECT_EQ(result.value().first, result.value().second)
        assert!(Arc::ptr_eq(&result.first, &result.second));
    }

    // ── TEST 5 ────────────────────────────────────────────────────────────────
    // C++: TEST(StreamSerializationTest, TruncatedStreamReturnsError)
    // C++ uses ForyInputStream(source, 4)

    #[test]
    fn test_truncated_stream_returns_error() {
        let mut fory = Fory::default().track_ref(true);
        register_stream_types(&mut fory);

        // Mirrors C++:
        // StreamEnvelope original{"truncated", {1,2,3,4},
        //     {{"k",99}}, {7,7}, true}
        let original = StreamEnvelope {
            name: "truncated".to_string(),
            values: vec![1, 2, 3, 4],
            metrics: [("k".to_string(), 99i64)].into_iter().collect(),
            point: StreamPoint { x: 7, y: 7 },
            active: true,
        };

        let mut bytes = fory.serialize(&original).unwrap();
        assert!(bytes.len() > 1);
        bytes.pop(); // mirrors C++: truncated.pop_back()

        // Mirrors C++: ForyInputStream stream(source, 4)
        let mut reader =
            Reader::from_stream(ForyStreamBuf::with_capacity(OneByte(Cursor::new(bytes)), 4));
        let result: Result<StreamEnvelope, _> = fory.deserialize_from(&mut reader);
        assert!(result.is_err());
    }

    // ── ForyStreamBuf unit tests ──────────────────────────────────────────────

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
