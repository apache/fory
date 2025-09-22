use fory_core::fory::Fory;
use fory_core::serializer::{Serializer, StructSerializer};
use fory_core::types::Mode::Compatible;
use fory_derive::Fory;
use std::fs;

// RUSTFLAGS="-Awarnings" cargo expand -p fory-tests --test test_single

// #[derive(Fory, Debug, PartialEq, Default)]
// enum Color {
//     #[default]
//     Green,
//     Red,
//     Blue,
//     White,
// }
//
// #[derive(Fory, Debug, PartialEq, Default)]
// struct Item {
//     name: Option<String>,
// }
//
#[derive(Fory, Debug, PartialEq, Default)]
struct SimpleStruct {
    f6: Vec<Option<String>>,
    // f3: Item,
    // f4: Option<String>,
    // f5: Color,
}
