use fory_core::fory::Fory;
use fory_core::serializer::Serializer;
use fory_core::types::Mode;
use std::collections::{HashMap, HashSet};

trait Printable: Serializer {
    fn print_info(&self);
}

fn fory_compatible() -> Fory {
    Fory::default().mode(Mode::Compatible)
}

#[test]
fn test_trait_object_architecture() {
    let _fory = Fory::default();
    let _: Box<dyn Serializer> = Box::new(42i32);
    assert!(true);
}

#[test]
fn test_trait_coercion() {
    #[derive(Default, Debug, PartialEq)]
    struct Book {
        title: String,
    }

    impl Serializer for Book {
        fn fory_write_data(&self, context: &mut fory_core::resolver::context::WriteContext, _is_field: bool) {
            self.title.fory_write_data(context, false);
        }
        fn fory_read_data(context: &mut fory_core::resolver::context::ReadContext, _is_field: bool) -> Result<Self, fory_core::error::Error> {
            Ok(Book {
                title: String::fory_read_data(context, false)?,
            })
        }
        fn fory_type_id_dyn(&self, _fory: &Fory) -> u32 {
            999
        }
    }

    impl Printable for Book {
        fn print_info(&self) {
            println!("Book: {}", self.title);
        }
    }

    let book = Book { title: String::from("Test") };
    let printable: Box<dyn Printable> = Box::new(book);
    let _serializer: Box<dyn Serializer> = printable;
    assert!(true);
}

#[test]
fn test_i32_roundtrip() {
    let fory = fory_compatible();

    let original = 42i32;
    let trait_obj: Box<dyn Serializer> = Box::new(original);
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: i32 = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_i64_roundtrip() {
    let fory = fory_compatible();

    let original = -9223372036854775808i64;
    let trait_obj: Box<dyn Serializer> = Box::new(original);
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: i64 = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_f64_roundtrip() {
    let fory = fory_compatible();

    let original = 3.141592653589793f64;
    let trait_obj: Box<dyn Serializer> = Box::new(original);
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: f64 = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_bool_roundtrip() {
    let fory = fory_compatible();

    let original = true;
    let trait_obj: Box<dyn Serializer> = Box::new(original);
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: bool = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_string_roundtrip() {
    let fory = fory_compatible();

    let original = String::from("Hello, Fury!");
    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: String = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_string_empty_roundtrip() {
    let fory = fory_compatible();

    let original = String::new();
    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: String = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_string_unicode_roundtrip() {
    let fory = fory_compatible();

    let original = String::from("こんにちは世界 🌍");
    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: String = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_vec_i32_roundtrip() {
    let fory = fory_compatible();

    let original = vec![1, 2, 3, 4, 5];
    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: Vec<i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_vec_string_roundtrip() {
    let fory = fory_compatible();

    let original = vec![String::from("a"), String::from("b"), String::from("c")];
    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: Vec<String> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_vec_empty_roundtrip() {
    let fory = fory_compatible();

    let original: Vec<i32> = Vec::new();
    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: Vec<i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]

#[test]

#[test]
fn test_option_some_roundtrip() {
    let fory = fory_compatible();

    let original = Some(42);
    let trait_obj: Box<dyn Serializer> = Box::new(original);
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: Option<i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]

#[test]
fn test_hashmap_roundtrip() {
    let fory = fory_compatible();

    let mut original = HashMap::new();
    original.insert(String::from("one"), 1);
    original.insert(String::from("two"), 2);
    original.insert(String::from("three"), 3);

    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: HashMap<String, i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete.len(), 3);
    assert_eq!(deserialized_concrete.get("one"), Some(&1));
    assert_eq!(deserialized_concrete.get("two"), Some(&2));
    assert_eq!(deserialized_concrete.get("three"), Some(&3));
}

#[test]
fn test_hashset_roundtrip() {
    let fory = fory_compatible();

    let mut original = HashSet::new();
    original.insert(1);
    original.insert(2);
    original.insert(3);

    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: HashSet<i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete.len(), 3);
    assert!(deserialized_concrete.contains(&1));
    assert!(deserialized_concrete.contains(&2));
    assert!(deserialized_concrete.contains(&3));
}

#[test]
fn test_large_vec_roundtrip() {
    let fory = fory_compatible();

    let original: Vec<i32> = (0..1000).collect();
    let trait_obj: Box<dyn Serializer> = Box::new(original.clone());
    let serialized = fory.serialize(&trait_obj);

    let deserialized_trait: Box<dyn Serializer> = fory.deserialize(&serialized).unwrap();
    let deserialized_concrete: Vec<i32> = fory.deserialize(&serialized).unwrap();

    assert_eq!(deserialized_concrete, original);
    assert_eq!(fory.serialize(&deserialized_trait), serialized);
}

#[test]
fn test_multiple_types_in_sequence() {
    let fory = fory_compatible();

    let original1 = 42i32;
    let original2 = String::from("test");
    let original3 = vec![1, 2, 3];

    let val1: Box<dyn Serializer> = Box::new(original1);
    let val2: Box<dyn Serializer> = Box::new(original2.clone());
    let val3: Box<dyn Serializer> = Box::new(original3.clone());

    let ser1 = fory.serialize(&val1);
    let ser2 = fory.serialize(&val2);
    let ser3 = fory.serialize(&val3);

    let de1_trait: Box<dyn Serializer> = fory.deserialize(&ser1).unwrap();
    let de2_trait: Box<dyn Serializer> = fory.deserialize(&ser2).unwrap();
    let de3_trait: Box<dyn Serializer> = fory.deserialize(&ser3).unwrap();

    let de1_concrete: i32 = fory.deserialize(&ser1).unwrap();
    let de2_concrete: String = fory.deserialize(&ser2).unwrap();
    let de3_concrete: Vec<i32> = fory.deserialize(&ser3).unwrap();

    assert_eq!(de1_concrete, original1);
    assert_eq!(de2_concrete, original2);
    assert_eq!(de3_concrete, original3);

    assert_eq!(ser1, fory.serialize(&de1_trait));
    assert_eq!(ser2, fory.serialize(&de2_trait));
    assert_eq!(ser3, fory.serialize(&de3_trait));
}
