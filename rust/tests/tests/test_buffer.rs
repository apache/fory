use fory_core::buffer::{Reader, Writer};

#[test]
fn buffer_test() {
    let test_data: Vec<i32> = vec![
        // 1 byte(0..127)
        0,
        1,
        127,
        // 2 byte(128..16_383)
        128,
        300,
        16_383,
        // 3 byte(16_384..2_097_151)
        16_384,
        20_000,
        2_097_151,
        // 4 byte(2_097_152..268_435_455)
        2_097_152,
        100_000_000,
        268_435_455,
        // 5 byte(268_435_456..i32::MAX)
        268_435_456,
        i32::MAX,
    ];
    for &data in &test_data {
        let mut writer = Writer::default();
        writer.var_int32(data);
        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());
        let res = reader.var_int32();
        assert_eq!(res, data);
    }
}
