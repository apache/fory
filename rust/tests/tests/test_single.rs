// RUSTFLAGS="-Awarnings" cargo expand -p fory-tests --test test_single

use fory_derive::ForyObject;

#[test]
fn complex_struct() {
    #[derive(ForyObject, Debug, PartialEq)]
    struct Animal {
        category: String,
    }
}
