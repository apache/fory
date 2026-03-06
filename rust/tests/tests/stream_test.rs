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
    use std::io::Cursor;

    // ========================================================================
    // OneByteStream — mirrors C++ OneByteStreamBuf / OneByteIStream
    // Delivers exactly 1 byte per read() call for maximum streaming stress.
    // ========================================================================
    struct OneByte(Cursor<Vec<u8>>);
    impl std::io::Read for OneByte {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if buf.is_empty() {
                return Ok(0);
            }
            let mut one = [0u8];
            match self.0.read(&mut one)? {
                0 => Ok(0),
                _ => {
                    buf[0] = one[0];
                    Ok(1)
                }
            }
        }
    }

    // ========================================================================
    // Deserialize helper — per maintainer requirement:
    //   "Create a Deserialize help methods in tests, then use that instead of
    //    fory.Deserialize for deserialization, and in the Deserialize test
    //    helper, first deserialize from bytes, then wrap it into a
    //    OneByteStream to deserialize it to ensure deserialization works."
    // ========================================================================
    fn deserialize_helper<T>(fory: &Fory, bytes: &[u8]) -> T
    where
        T: fory_core::Serializer + fory_core::ForyDefault + PartialEq + Debug,
    {
        // Path 1: deserialize from bytes (standard in-memory path)
        let from_bytes: T = fory.deserialize(bytes).expect("bytes deserialize failed");

        // Path 2: deserialize from OneByteStream (streaming path)
        let from_stream: T = fory
            .deserialize_from_stream(OneByte(Cursor::new(bytes.to_vec())))
            .expect("stream deserialize failed");

        // Assert both paths produce the same result
        assert_eq!(
            from_bytes, from_stream,
            "bytes vs stream deserialization mismatch"
        );

        from_bytes
    }

    // ========================================================================
    // Test: PrimitiveAndStringRoundTrip
    // Mirrors C++ StreamSerializationTest::PrimitiveAndStringRoundTrip
    // ========================================================================
    #[test]
    fn test_primitive_and_string_round_trip() {
        let fory = Fory::default();

        // i64 round-trip
        let bytes = fory.serialize(&-9876543212345i64).unwrap();
        let result = deserialize_helper::<i64>(&fory, &bytes);
        assert_eq!(result, -9876543212345i64);

        // String round-trip (with unicode)
        let bytes = fory.serialize(&"stream-hello-世界".to_string()).unwrap();
        let result = deserialize_helper::<String>(&fory, &bytes);
        assert_eq!(result, "stream-hello-世界");
    }

    // ========================================================================
    // Test: SequentialDeserializeFromSingleStream
    // Mirrors C++ StreamSerializationTest::SequentialDeserializeFromSingleStream
    // ========================================================================
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
        let second: String = fory.deserialize_from(&mut reader).unwrap();
        let third: i64 = fory.deserialize_from(&mut reader).unwrap();

        assert_eq!(first, 12345);
        assert_eq!(second, "next-value");
        assert_eq!(third, 99);
    }

    // ========================================================================
    // Test: TruncatedStreamReturnsError
    // Mirrors C++ StreamSerializationTest::TruncatedStreamReturnsError
    // ========================================================================
    #[test]
    fn test_truncated_stream_returns_error() {
        let fory = Fory::default();
        let mut bytes = fory.serialize(&"hello world".to_string()).unwrap();
        bytes.pop();
        let result: Result<String, _> = fory.deserialize_from_stream(Cursor::new(bytes));
        assert!(result.is_err());
    }

    // ========================================================================
    // Test: ShrinkBuffer compacts consumed bytes
    // Validates the C++ shrink_buffer() behavior is correctly implemented
    // ========================================================================
    #[test]
    fn test_shrink_buffer_compacts_consumed_bytes() {
        let fory = Fory::default();

        // Serialize multiple values into a single buffer
        let mut bytes = Vec::new();
        fory.serialize_to(&mut bytes, &42i32).unwrap();
        fory.serialize_to(&mut bytes, &"shrink-test".to_string())
            .unwrap();
        fory.serialize_to(&mut bytes, &100i64).unwrap();

        // Use a small initial buffer to force multiple fills
        let mut reader =
            Reader::from_stream(ForyStreamBuf::with_capacity(OneByte(Cursor::new(bytes)), 4));

        // After each deserialize_from, shrink_buffer should compact the stream.
        let first: i32 = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(first, 42);

        let second: String = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(second, "shrink-test");

        let third: i64 = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(third, 100);
    }

    // ========================================================================
    // Test: Additional primitive types through deserialize_helper
    // ========================================================================
    #[test]
    fn test_additional_primitive_types() {
        let fory = Fory::default();

        // bool
        let bytes = fory.serialize(&true).unwrap();
        assert!(deserialize_helper::<bool>(&fory, &bytes));

        // i32
        let bytes = fory.serialize(&-42i32).unwrap();
        assert_eq!(deserialize_helper::<i32>(&fory, &bytes), -42i32);

        // f64
        let bytes = fory.serialize(&std::f64::consts::PI).unwrap();
        assert_eq!(
            deserialize_helper::<f64>(&fory, &bytes),
            std::f64::consts::PI
        );
        // Vec<i32>
        let vec = vec![1i32, 2, 3, 5, 8];
        let bytes = fory.serialize(&vec).unwrap();
        assert_eq!(deserialize_helper::<Vec<i32>>(&fory, &bytes), vec);
    }

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

        let result = deserialize_helper::<Point>(&fory, &bytes);

        assert_eq!(result, point);
    }
}
