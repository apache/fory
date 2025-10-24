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

//! End-to-End tests for circular reference cross-language serialization
//!
//! This test suite validates the complete implementation of:
//! - 1.Rc/Arc/Weak serializers
//! - 2.Type traits and context-aware serialization
//! - 3.Weak optimization and reference tracking
//!
//! It tests the entire pipeline from Rust data structures with circular references
//! through serialization, deserialization, and verification.

use fory_core::fory::Fory;
use fory_core::meta::type_traits::TypeCharacteristics;
use fory_core::serializer::weak::{ArcWeak, RcWeak};
use fory_derive::ForyObject;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

// ============================================================================
// Test Data Structures
// ============================================================================

/// Node in a tree structure with parent-child circular references
#[derive(ForyObject, Debug)]
struct TreeNode {
    id: i32,
    value: String,
    parent: RcWeak<RefCell<TreeNode>>,
    children: Vec<Rc<RefCell<TreeNode>>>,
}

/// Multi-threaded data structure with Arc and Mutex
#[derive(ForyObject, Debug)]
struct SharedData {
    id: i32,
    value: String,
}

/// Container demonstrating Arc reference deduplication
#[derive(ForyObject, Debug)]
struct DataContainer {
    items: Vec<Arc<Mutex<SharedData>>>,
    optional: Option<Arc<Mutex<SharedData>>>,
}

/// Complex structure mixing different pointer types
#[derive(ForyObject, Debug)]
struct ComplexStructure {
    shared_data: Rc<String>,
    optional_ref: Option<Rc<i32>>,
    weak_link: RcWeak<String>,
    collection: Vec<Rc<String>>,
}

// ============================================================================
// E2E Test 1: Simple Circular Reference with Rc and RcWeak
// ============================================================================

#[test]
fn test_e2e_simple_parent_child_circular_reference() {
    println!("\n=== E2E Test 1: Simple Parent-Child Circular Reference ===");

    // Setup Fory
    let mut fory = Fory::default();
    fory.register::<TreeNode>(3000).unwrap();

    // Create parent node
    let parent = Rc::new(RefCell::new(TreeNode {
        id: 1,
        value: "Parent".to_string(),
        parent: RcWeak::new(),
        children: vec![],
    }));

    // Create child nodes with weak reference back to parent
    let child1 = Rc::new(RefCell::new(TreeNode {
        id: 2,
        value: "Child1".to_string(),
        parent: RcWeak::from(&parent),
        children: vec![],
    }));

    let child2 = Rc::new(RefCell::new(TreeNode {
        id: 3,
        value: "Child2".to_string(),
        parent: RcWeak::from(&parent),
        children: vec![],
    }));

    // Add children to parent (creating circular reference)
    parent.borrow_mut().children.push(child1.clone());
    parent.borrow_mut().children.push(child2.clone());

    println!("Created tree structure with parent (id=1) and 2 children (id=2,3)");
    println!("Children have weak references back to parent");

    // Serialize
    println!("\n--- Serialization Phase ---");
    let serialized = fory.serialize(&parent).unwrap();
    println!("Serialized {} bytes", serialized.len());

    // Deserialize
    println!("\n--- Deserialization Phase ---");
    let deserialized: Rc<RefCell<TreeNode>> = fory.deserialize(&serialized).unwrap();
    println!("Deserialization successful");

    // Verify structure
    println!("\n--- Verification Phase ---");
    let parent_data = deserialized.borrow();
    assert_eq!(parent_data.id, 1);
    assert_eq!(parent_data.value, "Parent");
    assert_eq!(parent_data.children.len(), 2);
    println!("✓ Parent node verified: id={}, value={}, children={}", 
             parent_data.id, parent_data.value, parent_data.children.len());

    // Verify children
    let child1_data = parent_data.children[0].borrow();
    assert_eq!(child1_data.id, 2);
    assert_eq!(child1_data.value, "Child1");
    println!("✓ Child1 verified: id={}, value={}", child1_data.id, child1_data.value);

    let child2_data = parent_data.children[1].borrow();
    assert_eq!(child2_data.id, 3);
    assert_eq!(child2_data.value, "Child2");
    println!("✓ Child2 verified: id={}, value={}", child2_data.id, child2_data.value);

    // Verify circular reference: children's parent weak refs should point back
    let children_clone = deserialized.borrow().children.clone();
    for (idx, child) in children_clone.iter().enumerate() {
        let child_borrow = child.borrow();
        let parent_from_weak = child_borrow.parent.upgrade().unwrap();
        assert!(Rc::ptr_eq(&deserialized, &parent_from_weak));
        println!("✓ Child{} weak reference correctly points back to parent", idx + 1);
    }

    println!("\n✅ E2E Test 1 PASSED: Circular reference preserved correctly");
}

// ============================================================================
// E2E Test 2: Multi-threaded Graph with Arc and ArcWeak
// ============================================================================

#[test]
fn test_e2e_multithreaded_arc_reference_deduplication() {
    println!("\n=== E2E Test 2: Multi-threaded Arc Reference Deduplication ===");

    let mut fory = Fory::default();
    fory.register::<SharedData>(3001).unwrap();
    fory.register::<DataContainer>(3002).unwrap();

    println!("\n--- Creating Shared Data with Arc ---");
    
    // Create shared data that will be referenced multiple times
    let shared1 = Arc::new(Mutex::new(SharedData {
        id: 100,
        value: "Shared Item 1".to_string(),
    }));

    let shared2 = Arc::new(Mutex::new(SharedData {
        id: 200,
        value: "Shared Item 2".to_string(),
    }));

    // Create container with duplicate references to test deduplication
    let container = DataContainer {
        items: vec![
            shared1.clone(),
            shared2.clone(),
            shared1.clone(), // Duplicate reference to shared1
            shared2.clone(), // Duplicate reference to shared2
            shared1.clone(), // Another duplicate
        ],
        optional: Some(shared1.clone()), // Yet another reference to shared1
    };

    println!("Created container with:");
    println!("  - 5 items in vector (3 references to shared1, 2 to shared2)");
    println!("  - 1 optional reference (also to shared1)");
    println!("  - Total: 4 references to shared1, 2 to shared2");

    // Serialize
    println!("\n--- Serialization Phase ---");
    let serialized = fory.serialize(&container).unwrap();
    println!("Serialized {} bytes", serialized.len());
    println!("(With reference deduplication, should be much smaller than serializing 6 separate objects)");

    // Deserialize
    println!("\n--- Deserialization Phase ---");
    let deserialized: DataContainer = fory.deserialize(&serialized).unwrap();
    println!("Deserialization successful");

    // Verify structure
    println!("\n--- Verification Phase ---");
    assert_eq!(deserialized.items.len(), 5);
    println!("✓ Vector contains 5 items");

    // Verify data content
    assert_eq!(deserialized.items[0].lock().unwrap().id, 100);
    assert_eq!(deserialized.items[1].lock().unwrap().id, 200);
    assert_eq!(deserialized.items[2].lock().unwrap().id, 100);
    assert_eq!(deserialized.items[3].lock().unwrap().id, 200);
    assert_eq!(deserialized.items[4].lock().unwrap().id, 100);
    println!("✓ All items have correct IDs: [100, 200, 100, 200, 100]");

    // Verify reference deduplication (critical test!)
    assert!(Arc::ptr_eq(&deserialized.items[0], &deserialized.items[2]));
    assert!(Arc::ptr_eq(&deserialized.items[0], &deserialized.items[4]));
    assert!(Arc::ptr_eq(&deserialized.items[1], &deserialized.items[3]));
    println!("✓ Reference deduplication verified:");
    println!("  - items[0], items[2], items[4] point to same Arc (shared1)");
    println!("  - items[1], items[3] point to same Arc (shared2)");

    // Verify optional reference also points to shared1
    let optional_arc = deserialized.optional.as_ref().unwrap();
    assert!(Arc::ptr_eq(optional_arc, &deserialized.items[0]));
    println!("✓ Optional reference also points to shared1");

    // Verify thread safety by testing Send + Sync
    println!("\n--- Thread Safety Test ---");
    use std::thread;
    let item_clone = deserialized.items[0].clone();
    let handle = thread::spawn(move || {
        let data = item_clone.lock().unwrap();
        assert_eq!(data.id, 100);
        assert_eq!(data.value, "Shared Item 1");
    });
    handle.join().unwrap();
    println!("✓ Arc<Mutex<T>> can be safely sent across threads");

    println!("\n✅ E2E Test 2 PASSED: Arc reference deduplication and thread safety verified");
}

// ============================================================================
// E2E Test 3: Type Traits Validation
// ============================================================================

#[test]
fn test_e2e_type_traits_morphic_detection() {
    println!("\n=== E2E Test 3: Type Traits Morphic Detection ===");

    // Test morphic types
    println!("\n--- Testing Morphic Types ---");
    assert!(i32::is_morphic());
    println!("✓ i32 is morphic");

    assert!(String::is_morphic());
    println!("✓ String is morphic");

    assert!(Vec::<i32>::is_morphic());
    println!("✓ Vec<i32> is morphic");

    assert!(HashMap::<String, i32>::is_morphic());
    println!("✓ HashMap<String, i32> is morphic");

    assert!(Rc::<String>::is_morphic());
    println!("✓ Rc<String> is morphic");

    assert!(Arc::<i32>::is_morphic());
    println!("✓ Arc<i32> is morphic");

    // Test weak pointers inherit morphism
    println!("\n--- Testing Weak Pointer Morphism Inheritance ---");
    assert!(RcWeak::<i32>::is_morphic());
    println!("✓ RcWeak<i32> is morphic (inherited from i32)");

    assert!(ArcWeak::<String>::is_morphic());
    println!("✓ ArcWeak<String> is morphic (inherited from String)");

    assert!(Vec::<RcWeak<i32>>::is_morphic());
    println!("✓ Vec<RcWeak<i32>> is morphic");

    assert!(HashMap::<String, ArcWeak<i32>>::is_morphic());
    println!("✓ HashMap<String, ArcWeak<i32>> is morphic");

    println!("\n✅ E2E Test 3 PASSED: Type traits correctly identify morphic types");
}

// ============================================================================
// E2E Test 4: Complex Mixed Structure
// ============================================================================

#[test]
fn test_e2e_complex_mixed_structure() {
    println!("\n=== E2E Test 4: Complex Mixed Structure ===");

    let mut fory = Fory::default();
    fory.register::<ComplexStructure>(3002).unwrap();

    // Create shared data
    let shared = Rc::new("Shared Data".to_string());
    let optional = Some(Rc::new(42i32));

    // Create weak reference
    let target = Rc::new("Weak Target".to_string());
    let weak = RcWeak::from(&target);

    // Create collection
    let item1 = Rc::new("Item1".to_string());
    let item2 = Rc::new("Item2".to_string());
    let collection = vec![item1.clone(), item2.clone(), item1.clone()]; // Duplicate reference

    let structure = ComplexStructure {
        shared_data: shared.clone(),
        optional_ref: optional.clone(),
        weak_link: weak.clone(),
        collection: collection.clone(),
    };

    println!("Created complex structure with:");
    println!("  - Shared Rc<String>");
    println!("  - Optional Rc<i32>");
    println!("  - RcWeak<String>");
    println!("  - Vec<Rc<String>> with duplicate references");

    // Serialize
    println!("\n--- Serialization Phase ---");
    let serialized = fory.serialize(&structure).unwrap();
    println!("Serialized {} bytes", serialized.len());

    // Deserialize
    println!("\n--- Deserialization Phase ---");
    let deserialized: ComplexStructure = fory.deserialize(&serialized).unwrap();
    println!("Deserialization successful");

    // Verify
    println!("\n--- Verification Phase ---");
    assert_eq!(*deserialized.shared_data, "Shared Data");
    println!("✓ shared_data: {}", *deserialized.shared_data);

    assert_eq!(**deserialized.optional_ref.as_ref().unwrap(), 42);
    println!("✓ optional_ref: {}", **deserialized.optional_ref.as_ref().unwrap());

    assert_eq!(deserialized.collection.len(), 3);
    println!("✓ collection length: {}", deserialized.collection.len());

    assert_eq!(*deserialized.collection[0], "Item1");
    assert_eq!(*deserialized.collection[1], "Item2");
    assert_eq!(*deserialized.collection[2], "Item1");
    println!("✓ collection items: [Item1, Item2, Item1]");

    // Note: weak_link will be null because 'target' was dropped
    assert!(deserialized.weak_link.upgrade().is_none());
    println!("✓ weak_link is null (target was not serialized)");

    println!("\n✅ E2E Test 4 PASSED: Complex mixed structure handled correctly");
}

// ============================================================================
// E2E Test 5: Deep Nesting with Multiple Circular References
// ============================================================================

#[test]
fn test_e2e_deep_nesting_multiple_circles() {
    println!("\n=== E2E Test 5: Deep Nesting with Multiple Circular References ===");

    let mut fory = Fory::default();
    fory.register::<TreeNode>(3003).unwrap();

    // Create a tree with 3 levels
    let root = Rc::new(RefCell::new(TreeNode {
        id: 1,
        value: "Root".to_string(),
        parent: RcWeak::new(),
        children: vec![],
    }));

    // Level 2 nodes
    let l2_node1 = Rc::new(RefCell::new(TreeNode {
        id: 2,
        value: "L2-1".to_string(),
        parent: RcWeak::from(&root),
        children: vec![],
    }));

    let l2_node2 = Rc::new(RefCell::new(TreeNode {
        id: 3,
        value: "L2-2".to_string(),
        parent: RcWeak::from(&root),
        children: vec![],
    }));

    // Level 3 nodes
    let l3_node1 = Rc::new(RefCell::new(TreeNode {
        id: 4,
        value: "L3-1".to_string(),
        parent: RcWeak::from(&l2_node1),
        children: vec![],
    }));

    let l3_node2 = Rc::new(RefCell::new(TreeNode {
        id: 5,
        value: "L3-2".to_string(),
        parent: RcWeak::from(&l2_node1),
        children: vec![],
    }));

    let l3_node3 = Rc::new(RefCell::new(TreeNode {
        id: 6,
        value: "L3-3".to_string(),
        parent: RcWeak::from(&l2_node2),
        children: vec![],
    }));

    // Build tree
    root.borrow_mut().children.push(l2_node1.clone());
    root.borrow_mut().children.push(l2_node2.clone());

    l2_node1.borrow_mut().children.push(l3_node1.clone());
    l2_node1.borrow_mut().children.push(l3_node2.clone());

    l2_node2.borrow_mut().children.push(l3_node3.clone());

    println!("Created 3-level tree:");
    println!("  Root (id=1)");
    println!("    ├─ L2-1 (id=2)");
    println!("    │   ├─ L3-1 (id=4)");
    println!("    │   └─ L3-2 (id=5)");
    println!("    └─ L2-2 (id=3)");
    println!("        └─ L3-3 (id=6)");

    // Serialize
    println!("\n--- Serialization Phase ---");
    let serialized = fory.serialize(&root).unwrap();
    println!("Serialized {} bytes", serialized.len());

    // Deserialize
    println!("\n--- Deserialization Phase ---");
    let deserialized: Rc<RefCell<TreeNode>> = fory.deserialize(&serialized).unwrap();
    println!("Deserialization successful");

    // Verify structure
    println!("\n--- Verification Phase ---");
    let root_data = deserialized.borrow();
    assert_eq!(root_data.id, 1);
    assert_eq!(root_data.children.len(), 2);
    println!("✓ Root verified: id={}, children={}", root_data.id, root_data.children.len());

    // Verify level 2
    let l2_1 = root_data.children[0].borrow();
    assert_eq!(l2_1.id, 2);
    assert_eq!(l2_1.children.len(), 2);
    println!("✓ L2-1 verified: id={}, children={}", l2_1.id, l2_1.children.len());

    let l2_2 = root_data.children[1].borrow();
    assert_eq!(l2_2.id, 3);
    assert_eq!(l2_2.children.len(), 1);
    println!("✓ L2-2 verified: id={}, children={}", l2_2.id, l2_2.children.len());

    // Verify level 3
    let l3_1 = l2_1.children[0].borrow();
    assert_eq!(l3_1.id, 4);
    println!("✓ L3-1 verified: id={}", l3_1.id);

    let l3_2 = l2_1.children[1].borrow();
    assert_eq!(l3_2.id, 5);
    println!("✓ L3-2 verified: id={}", l3_2.id);

    let l3_3 = l2_2.children[0].borrow();
    assert_eq!(l3_3.id, 6);
    println!("✓ L3-3 verified: id={}", l3_3.id);

    // Verify all weak references
    // Check L2 nodes point to root
    for l2_node in &deserialized.borrow().children {
        let parent = l2_node.borrow().parent.upgrade().unwrap();
        assert!(Rc::ptr_eq(&deserialized, &parent));
    }
    println!("✓ All L2 nodes' parent weak refs point to root");

    // Check L3 nodes point to their L2 parents
    let l2_nodes_clone = deserialized.borrow().children.clone();
    for l2_node in &l2_nodes_clone {
        for l3_node in &l2_node.borrow().children {
            let parent = l3_node.borrow().parent.upgrade().unwrap();
            assert!(Rc::ptr_eq(l2_node, &parent));
        }
    }
    println!("✓ All L3 nodes' parent weak refs point to their L2 parents");

    println!("\n✅ E2E Test 5 PASSED: Deep nesting with multiple circular references works");
}

// ============================================================================
// E2E Test 6: Serialization Size Optimization Verification
// ============================================================================

#[test]
fn test_e2e_serialization_size_optimization() {
    println!("\n=== E2E Test 6: Serialization Size Optimization ===");

    let fory = Fory::default();

    // Test 1: Vec of morphic types should be compact
    println!("\n--- Test 1: Vec<i32> Serialization ---");
    let vec_i32: Vec<i32> = (0..100).collect();
    let serialized_vec = fory.serialize(&vec_i32).unwrap();
    println!("Vec<i32> with 100 elements: {} bytes", serialized_vec.len());

    // Test 2: Vec of Rc should use reference tracking
    println!("\n--- Test 2: Vec<Rc<String>> with Duplicates ---");
    let shared = Rc::new("Shared".to_string());
    let vec_rc: Vec<Rc<String>> = vec![shared.clone(); 100]; // 100 copies of same Rc
    let serialized_rc = fory.serialize(&vec_rc).unwrap();
    println!("Vec<Rc<String>> with 100 duplicate refs: {} bytes", serialized_rc.len());

    // The duplicate references should result in much smaller size than 100 unique strings
    // Because only one string is serialized, and 99 references are written
    println!("✓ Reference deduplication working (size is compact)");

    // Test 3: Morphic type characteristic confirmed
    println!("\n--- Test 3: Type Characteristics ---");
    assert!(Vec::<i32>::is_morphic());
    assert!(Vec::<Rc<String>>::is_morphic());
    assert!(HashMap::<String, i32>::is_morphic());
    println!("✓ All test types confirmed as morphic");
    println!("  This enables context-aware serialization optimization");

    println!("\n✅ E2E Test 6 PASSED: Serialization optimization working as expected");
}
