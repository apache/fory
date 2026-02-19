#[cfg(test)]
mod stream_tests {
    use fory_core::buffer::Reader;
    use fory_core::stream::ForyStreamBuf;
    use fory_core::Fory;
    use std::io::Cursor;

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

    #[test]
    fn test_primitive_stream_roundtrip() {
        let fory = Fory::default();
        let bytes = fory.serialize(&-9876543212345i64).unwrap();
        let result: i64 = fory
            .deserialize_from_stream(OneByte(Cursor::new(bytes)))
            .unwrap();
        assert_eq!(result, -9876543212345i64);

        let bytes = fory.serialize(&"stream-hello-世界".to_string()).unwrap();
        let result: String = fory
            .deserialize_from_stream(OneByte(Cursor::new(bytes)))
            .unwrap();
        assert_eq!(result, "stream-hello-世界");
    }

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

    #[test]
    fn test_truncated_stream_returns_error() {
        let fory = Fory::default();
        let mut bytes = fory.serialize(&"hello world".to_string()).unwrap();
        bytes.pop();
        let result: Result<String, _> = fory.deserialize_from_stream(Cursor::new(bytes));
        assert!(result.is_err());
    }
}
