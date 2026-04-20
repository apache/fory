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

//! Tests for field-level `#[fory(...)]` attributes

use fory_core::Fory;
use fory_derive::ForyObject;
use std::rc::Rc;
use std::sync::Arc;

/// Test struct with skip attribute
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithSkip {
    name: String,
    #[fory(skip)]
    secret: String,
    age: i32,
}

#[test]
fn test_skip_field() {
    let mut fory = Fory::default();
    fory.register::<StructWithSkip>(1).unwrap();

    let original = StructWithSkip {
        name: "Alice".to_string(),
        secret: "password123".to_string(),
        age: 30,
    };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithSkip = fory.deserialize(&bytes).unwrap();

    assert_eq!(deserialized.name, "Alice");
    assert_eq!(deserialized.secret, String::default()); // Should be default
    assert_eq!(deserialized.age, 30);
}

/// Test struct with nullable attribute on Option fields
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithNullable {
    name: String,
    #[fory(nullable)]
    description: Option<String>,
    count: i32,
}

#[test]
fn test_nullable_attribute() {
    let mut fory = Fory::default();
    fory.register::<StructWithNullable>(2).unwrap();

    // Test with Some value
    let original = StructWithNullable {
        name: "Test".to_string(),
        description: Some("A description".to_string()),
        count: 42,
    };
    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithNullable = fory.deserialize(&bytes).unwrap();
    assert_eq!(original, deserialized);

    // Test with None value
    let original_none = StructWithNullable {
        name: "Test".to_string(),
        description: None,
        count: 42,
    };
    let bytes = fory.serialize(&original_none).unwrap();
    let deserialized: StructWithNullable = fory.deserialize(&bytes).unwrap();
    assert_eq!(original_none, deserialized);
}

/// Test struct with explicit ref tracking disabled
#[derive(ForyObject, Debug, PartialEq, Clone)]
struct InnerData {
    value: i32,
}

#[derive(ForyObject, Debug, PartialEq)]
struct StructWithRefTracking {
    #[fory(ref = false)]
    data: Rc<InnerData>,
}

#[test]
fn test_ref_tracking_disabled() {
    let mut fory = Fory::default();
    fory.register::<InnerData>(3).unwrap();
    fory.register::<StructWithRefTracking>(4).unwrap();

    let inner = Rc::new(InnerData { value: 100 });
    let original = StructWithRefTracking { data: inner };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithRefTracking = fory.deserialize(&bytes).unwrap();
    assert_eq!(deserialized.data.value, 100);
}

/// Test struct with explicit nullable = false
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithExplicitNotNull {
    #[fory(nullable = false)]
    required_option: Option<String>,
}

#[test]
fn test_explicit_not_nullable() {
    let mut fory = Fory::default();
    fory.register::<StructWithExplicitNotNull>(5).unwrap();

    let original = StructWithExplicitNotNull {
        required_option: Some("value".to_string()),
    };
    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithExplicitNotNull = fory.deserialize(&bytes).unwrap();
    assert_eq!(original, deserialized);
}

/// Test struct with Arc and ref tracking
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithArc {
    data: Arc<InnerData>,
}

#[test]
fn test_arc_default_ref_tracking() {
    let mut fory = Fory::default();
    fory.register::<InnerData>(6).unwrap();
    fory.register::<StructWithArc>(7).unwrap();

    let inner = Arc::new(InnerData { value: 200 });
    let original = StructWithArc { data: inner };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithArc = fory.deserialize(&bytes).unwrap();
    assert_eq!(deserialized.data.value, 200);
}

/// Test struct with multiple attributes combined
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithCombinedAttrs {
    name: String,
    #[fory(skip)]
    internal_state: i32,
    #[fory(nullable)]
    optional_data: Option<String>,
}

#[test]
fn test_combined_attributes() {
    let mut fory = Fory::default();
    fory.register::<StructWithCombinedAttrs>(8).unwrap();

    let original = StructWithCombinedAttrs {
        name: "Test".to_string(),
        internal_state: 999,
        optional_data: Some("data".to_string()),
    };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithCombinedAttrs = fory.deserialize(&bytes).unwrap();

    assert_eq!(deserialized.name, "Test");
    assert_eq!(deserialized.internal_state, 0); // Skipped, default value
    assert_eq!(deserialized.optional_data, Some("data".to_string()));
}

/// Test struct with primitive types (should be non-nullable by default)
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithPrimitives {
    count: i32,
    value: f64,
    flag: bool,
}

#[test]
fn test_primitive_defaults() {
    let mut fory = Fory::default();
    fory.register::<StructWithPrimitives>(9).unwrap();

    let original = StructWithPrimitives {
        count: 42,
        value: 1.23456,
        flag: true,
    };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithPrimitives = fory.deserialize(&bytes).unwrap();
    assert_eq!(original, deserialized);
}

/// Test struct with field IDs for compact encoding
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithFieldIds {
    #[fory(id = 0)]
    name: String,
    #[fory(id = 1)]
    age: i32,
    #[fory(id = 2)]
    email: String,
}

#[test]
fn test_field_id_attribute() {
    let mut fory = Fory::default();
    fory.register::<StructWithFieldIds>(10).unwrap();

    let original = StructWithFieldIds {
        name: "Bob".to_string(),
        age: 25,
        email: "bob@example.com".to_string(),
    };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithFieldIds = fory.deserialize(&bytes).unwrap();
    assert_eq!(original, deserialized);
}

/// Test struct with mixed field IDs and non-ID fields
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithMixedIds {
    #[fory(id = 0)]
    id_field: i32,
    regular_field: String,
    #[fory(id = 2)]
    another_id_field: f64,
}

#[test]
fn test_mixed_field_ids() {
    let mut fory = Fory::default();
    fory.register::<StructWithMixedIds>(11).unwrap();

    let original = StructWithMixedIds {
        id_field: 100,
        regular_field: "test".to_string(),
        another_id_field: 99.99,
    };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithMixedIds = fory.deserialize(&bytes).unwrap();
    assert_eq!(original, deserialized);
}

/// Test field ID with skip and nullable combined
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithCombinedFieldAttrs {
    #[fory(id = 0)]
    name: String,
    #[fory(id = 1, nullable)]
    description: Option<String>,
    #[fory(skip)]
    internal_id: i64,
    #[fory(id = 2)]
    count: i32,
}

#[test]
fn test_field_id_with_other_attrs() {
    let mut fory = Fory::default();
    fory.register::<StructWithCombinedFieldAttrs>(12).unwrap();

    let original = StructWithCombinedFieldAttrs {
        name: "Test".to_string(),
        description: Some("A description".to_string()),
        internal_id: 999999,
        count: 42,
    };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithCombinedFieldAttrs = fory.deserialize(&bytes).unwrap();

    assert_eq!(deserialized.name, "Test");
    assert_eq!(deserialized.description, Some("A description".to_string()));
    assert_eq!(deserialized.internal_id, 0); // Skipped, should be default
    assert_eq!(deserialized.count, 42);
}

// ============================================================================
// Compatible Mode Tests with Struct Versioning
// ============================================================================

mod compatible_v1 {
    use fory_derive::ForyObject;

    /// Version 1 of a user struct - original version
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct UserV1 {
        #[fory(id = 0)]
        pub name: String,
        #[fory(id = 1)]
        pub age: i32,
    }
}

mod compatible_v2 {
    use fory_derive::ForyObject;

    /// Version 2 of a user struct - added email field
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct UserV2 {
        #[fory(id = 0)]
        pub name: String,
        #[fory(id = 1)]
        pub age: i32,
        #[fory(id = 2, nullable)]
        pub email: Option<String>,
    }
}

#[test]
fn test_compatible_mode_v1_to_v2() {
    // Serialize with V1, deserialize with V2 (forward compatibility)
    let mut fory_v1 = Fory::builder().compatible(true).build();
    fory_v1.register::<compatible_v1::UserV1>(100).unwrap();

    let mut fory_v2 = Fory::builder().compatible(true).build();
    fory_v2.register::<compatible_v2::UserV2>(100).unwrap();

    let user_v1 = compatible_v1::UserV1 {
        name: "Alice".to_string(),
        age: 30,
    };

    // Serialize with V1
    let bytes = fory_v1.serialize(&user_v1).unwrap();

    // Deserialize with V2 - new field should get default value
    let user_v2: compatible_v2::UserV2 = fory_v2.deserialize(&bytes).unwrap();

    assert_eq!(user_v2.name, "Alice");
    assert_eq!(user_v2.age, 30);
    assert_eq!(user_v2.email, None); // New field should be None
}

#[test]
fn test_compatible_mode_v2_to_v1() {
    // Serialize with V2, deserialize with V1 (backward compatibility)
    let mut fory_v1 = Fory::builder().compatible(true).build();
    fory_v1.register::<compatible_v1::UserV1>(100).unwrap();

    let mut fory_v2 = Fory::builder().compatible(true).build();
    fory_v2.register::<compatible_v2::UserV2>(100).unwrap();

    let user_v2 = compatible_v2::UserV2 {
        name: "Bob".to_string(),
        age: 25,
        email: Some("bob@example.com".to_string()),
    };

    // Serialize with V2
    let bytes = fory_v2.serialize(&user_v2).unwrap();

    // Deserialize with V1 - extra field should be skipped
    let user_v1: compatible_v1::UserV1 = fory_v1.deserialize(&bytes).unwrap();

    assert_eq!(user_v1.name, "Bob");
    assert_eq!(user_v1.age, 25);
    // email field is ignored since V1 doesn't have it
}

mod compatible_reorder_v1 {
    use fory_derive::ForyObject;

    /// Version with specific field order
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct DataV1 {
        #[fory(id = 0)]
        pub field_a: String,
        #[fory(id = 1)]
        pub field_b: i32,
        #[fory(id = 2)]
        pub field_c: f64,
    }
}

mod compatible_reorder_v2 {
    use fory_derive::ForyObject;

    /// Version with reordered fields (same IDs, different order in struct)
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct DataV2 {
        #[fory(id = 2)]
        pub field_c: f64,
        #[fory(id = 0)]
        pub field_a: String,
        #[fory(id = 1)]
        pub field_b: i32,
    }
}

#[test]
fn test_compatible_mode_field_reorder() {
    // Test that field IDs allow fields to be reordered between versions
    let mut fory_v1 = Fory::builder().compatible(true).build();
    fory_v1
        .register::<compatible_reorder_v1::DataV1>(200)
        .unwrap();

    let mut fory_v2 = Fory::builder().compatible(true).build();
    fory_v2
        .register::<compatible_reorder_v2::DataV2>(200)
        .unwrap();

    let data_v1 = compatible_reorder_v1::DataV1 {
        field_a: "hello".to_string(),
        field_b: 42,
        field_c: 3.5,
    };

    // Serialize with V1
    let bytes = fory_v1.serialize(&data_v1).unwrap();

    // Deserialize with V2 - fields should match by ID regardless of order
    let data_v2: compatible_reorder_v2::DataV2 = fory_v2.deserialize(&bytes).unwrap();

    assert_eq!(data_v2.field_a, "hello");
    assert_eq!(data_v2.field_b, 42);
    assert_eq!(data_v2.field_c, 3.5);
}

mod compatible_remove_field_v1 {
    use fory_derive::ForyObject;

    /// Version with 3 fields
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct ConfigV1 {
        #[fory(id = 0)]
        pub name: String,
        #[fory(id = 1)]
        pub value: i32,
        #[fory(id = 2)]
        pub extra_field: String,
    }
}

mod compatible_remove_field_v2 {
    use fory_derive::ForyObject;

    /// Version with extra_field removed (simulates field removal)
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct ConfigV2 {
        #[fory(id = 0)]
        pub name: String,
        #[fory(id = 1)]
        pub value: i32,
        // extra_field removed in this version
    }
}

#[test]
fn test_compatible_mode_field_removed() {
    // Test that removed fields are handled in compatible mode
    let mut fory_v1 = Fory::builder().compatible(true).build();
    fory_v1
        .register::<compatible_remove_field_v1::ConfigV1>(300)
        .unwrap();

    let mut fory_v2 = Fory::builder().compatible(true).build();
    fory_v2
        .register::<compatible_remove_field_v2::ConfigV2>(300)
        .unwrap();

    let config_v1 = compatible_remove_field_v1::ConfigV1 {
        name: "config".to_string(),
        value: 100,
        extra_field: "extra_value".to_string(),
    };

    // Serialize with V1 (3 fields)
    let bytes = fory_v1.serialize(&config_v1).unwrap();

    // Deserialize with V2 (2 fields) - extra_field should be skipped
    let config_v2: compatible_remove_field_v2::ConfigV2 = fory_v2.deserialize(&bytes).unwrap();

    assert_eq!(config_v2.name, "config");
    assert_eq!(config_v2.value, 100);
}

/// Test skip attribute in non-compatible mode (simpler case)
#[derive(ForyObject, Debug, PartialEq)]
struct StructWithSkipAndId {
    #[fory(id = 0)]
    name: String,
    #[fory(id = 1, skip)]
    internal: i64,
    #[fory(id = 2)]
    count: i32,
}

#[test]
fn test_skip_with_field_id() {
    let mut fory = Fory::default();
    fory.register::<StructWithSkipAndId>(350).unwrap();

    let original = StructWithSkipAndId {
        name: "test".to_string(),
        internal: 999999,
        count: 42,
    };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: StructWithSkipAndId = fory.deserialize(&bytes).unwrap();

    assert_eq!(deserialized.name, "test");
    assert_eq!(deserialized.internal, 0); // Skipped, default value
    assert_eq!(deserialized.count, 42);
}

#[test]
fn test_compatible_mode_roundtrip() {
    // Test full roundtrip with compatible mode and field IDs
    let mut fory = Fory::builder().compatible(true).build();
    fory.register::<compatible_v2::UserV2>(400).unwrap();

    let original = compatible_v2::UserV2 {
        name: "Charlie".to_string(),
        age: 35,
        email: Some("charlie@test.com".to_string()),
    };

    let bytes = fory.serialize(&original).unwrap();
    let deserialized: compatible_v2::UserV2 = fory.deserialize(&bytes).unwrap();

    assert_eq!(original, deserialized);
}

// ============================================================================
// Payload Size Tests - Field IDs vs Field Names
// ============================================================================

mod payload_with_field_ids {
    use fory_derive::ForyObject;

    /// Struct using field IDs for compact encoding
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct CompactUser {
        #[fory(id = 0)]
        pub username: String,
        #[fory(id = 1)]
        pub email_address: String,
        #[fory(id = 2)]
        pub phone_number: String,
        #[fory(id = 3)]
        pub street_address: String,
        #[fory(id = 4)]
        pub postal_code: i32,
    }
}

mod payload_without_field_ids {
    use fory_derive::ForyObject;

    /// Struct using field names (no field IDs)
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    pub struct VerboseUser {
        pub username: String,
        pub email_address: String,
        pub phone_number: String,
        pub street_address: String,
        pub postal_code: i32,
    }
}

#[test]
fn test_field_id_payload_compatible_mode() {
    // Test that structs with field IDs produce smaller payloads in compatible mode.
    // Field IDs are encoded as compact 1-2 byte integers instead of full field names,
    // following the xlang serialization spec (TAG_ID encoding with 2-bit marker 0b11).
    let mut fory_compact = Fory::builder().compatible(true).build();
    fory_compact
        .register::<payload_with_field_ids::CompactUser>(500)
        .unwrap();

    let mut fory_verbose = Fory::builder().compatible(true).build();
    fory_verbose
        .register::<payload_without_field_ids::VerboseUser>(501)
        .unwrap();

    let compact_user = payload_with_field_ids::CompactUser {
        username: "john_doe".to_string(),
        email_address: "john@example.com".to_string(),
        phone_number: "+1-555-123-4567".to_string(),
        street_address: "123 Main Street".to_string(),
        postal_code: 12345,
    };

    let verbose_user = payload_without_field_ids::VerboseUser {
        username: "john_doe".to_string(),
        email_address: "john@example.com".to_string(),
        phone_number: "+1-555-123-4567".to_string(),
        street_address: "123 Main Street".to_string(),
        postal_code: 12345,
    };

    let compact_bytes = fory_compact.serialize(&compact_user).unwrap();
    let verbose_bytes = fory_verbose.serialize(&verbose_user).unwrap();

    // Log payload sizes for reference
    println!(
        "Payload sizes - with field IDs: {} bytes, with field names: {} bytes",
        compact_bytes.len(),
        verbose_bytes.len()
    );

    // Verify data integrity - both should deserialize correctly
    let deserialized_compact: payload_with_field_ids::CompactUser =
        fory_compact.deserialize(&compact_bytes).unwrap();
    let deserialized_verbose: payload_without_field_ids::VerboseUser =
        fory_verbose.deserialize(&verbose_bytes).unwrap();

    assert_eq!(compact_user, deserialized_compact);
    assert_eq!(verbose_user, deserialized_verbose);

    // Verify that field ID encoding produces smaller payloads
    // Field IDs (1-2 bytes each) are much smaller than field names (variable length strings)
    assert!(
        compact_bytes.len() < verbose_bytes.len(),
        "Compact encoding with field IDs ({} bytes) should be smaller than field names ({} bytes)",
        compact_bytes.len(),
        verbose_bytes.len()
    );
}
