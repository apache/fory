use std::collections::HashSet;
use std::sync::Arc;
use std::thread;
use fory_core::Fory;

// #[test]
// fn test_multi_thread() {
//     let fory = Arc::new(Fory::default());
//     let data: HashSet<_> = [41, 42, 43].into_iter().collect();
//     let mut handles = vec![];
//     for item in data {
//         let fory_clone = Arc::clone(&fory);
//         let handle = thread::spawn(move || {
//             fory_clone.serialize(&item)
//         });
//         handles.push(handle);
//     }
//     for handle in handles {
//         handle.join().unwrap();
//     }
// }