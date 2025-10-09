use fory_core::Fory;
use std::collections::HashSet;
use std::sync::Arc;
use std::thread;

#[test]
fn test_multi_thread() {
    let fory = Arc::new(Fory::default());
    let src: HashSet<_> = [41, 42, 43, 45, 46, 47].into_iter().collect();
    // serialize
    let mut handles = vec![];
    for item in &src {
        let fory_clone = Arc::clone(&fory);
        let item = *item;
        let handle = thread::spawn(move || fory_clone.serialize(&item));
        handles.push(handle);
    }
    let mut serialized_data = vec![];
    for handle in handles {
        let bytes = handle.join().unwrap();
        serialized_data.push(bytes);
    }
    // deserialize
    let mut dest = HashSet::new();
    let mut handles = vec![];
    for bytes in serialized_data {
        let fory_clone = Arc::clone(&fory);
        let handle = thread::spawn(move || fory_clone.deserialize::<i32>(&bytes).unwrap());
        handles.push(handle);
    }
    for handle in handles {
        let value = handle.join().unwrap();
        dest.insert(value);
    }
    // verify
    assert_eq!(dest, src);
}
