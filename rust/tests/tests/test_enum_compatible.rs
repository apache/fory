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

// RUSTFLAGS="-Awarnings" cargo expand -p tests --test test_enum

use fory_core::Fory;
use fory_derive::ForyObject;

/// Test schema evolution for unnamed enum variants in compatible mode
#[test]
fn test_unnamed_enum_variant_compatible() {
    // Original enum with 2 fields in variant
    #[derive(ForyObject, Debug, PartialEq)]
    enum EventV1 {
        #[fory(default)]
        Unknown,
        Message(i32, String),
    }

    // Evolved enum with 3 fields in variant (added f64)
    #[derive(ForyObject, Debug, PartialEq)]
    enum EventV2 {
        #[fory(default)]
        Unknown,
        Message(i32, String, f64),
    }

    // Test 1: Serialize v1 (2 fields), deserialize as v2 (3 fields)
    let mut fory_v1 = Fory::default().xlang(false).compatible(true);
    fory_v1.register::<EventV1>(2000).unwrap();

    let mut fory_v2 = Fory::default().xlang(false).compatible(true);
    fory_v2.register::<EventV2>(2000).unwrap();

    let event_v1 = EventV1::Message(42, "hello".to_string());
    let bin = fory_v1.serialize(&event_v1).unwrap();
    let event_v2: EventV2 = fory_v2.deserialize(&bin).expect("deserialize v1 to v2");
    match event_v2 {
        EventV2::Message(a, b, c) => {
            assert_eq!(a, 42);
            assert_eq!(b, "hello");
            assert_eq!(c, 0.0); // Default value for missing field
        }
        _ => panic!("Expected Message variant"),
    }

    // Test 2: Serialize v2 (3 fields), deserialize as v1 (2 fields)
    let event_v2 = EventV2::Message(100, "world".to_string(), std::f64::consts::PI);
    let bin = fory_v2.serialize(&event_v2).unwrap();
    let event_v1: EventV1 = fory_v1.deserialize(&bin).expect("deserialize v2 to v1");
    match event_v1 {
        EventV1::Message(a, b) => {
            assert_eq!(a, 100);
            assert_eq!(b, "world");
            // Extra field (3.14) is skipped during deserialization
        }
        _ => panic!("Expected Message variant"),
    }

    // Test 3: Unknown variant falls back to default
    #[derive(ForyObject, Debug, PartialEq)]
    enum EventV3 {
        #[fory(default)]
        Unknown,
        Message(i32, String),
        NewVariant(bool), // This variant doesn't exist in EventV1
    }

    let mut fory_v3 = Fory::default().xlang(false).compatible(true);
    fory_v3.register::<EventV3>(2000).unwrap();

    let event_v3 = EventV3::NewVariant(true);
    let bin = fory_v3.serialize(&event_v3).unwrap();
    let event_v1: EventV1 = fory_v1
        .deserialize(&bin)
        .expect("deserialize unknown variant");
    assert_eq!(event_v1, EventV1::Unknown); // Falls back to default variant
}

/// Test schema evolution for named enum variants in compatible mode
#[test]
fn test_named_enum_variant_compatible() {
    // Original enum with 2 fields
    #[derive(ForyObject, Debug, PartialEq)]
    enum CommandV1 {
        #[fory(default)]
        Noop,
        Execute {
            args: i32,
            name: String,
        },
    }

    // Evolved enum with 3 fields - added 'env' field
    #[derive(ForyObject, Debug, PartialEq)]
    enum CommandV2 {
        #[fory(default)]
        Noop,
        Execute {
            args: i32,
            env: String,
            name: String,
        },
    }

    let mut fory_v1 = Fory::default().xlang(false).compatible(true);
    fory_v1.register::<CommandV1>(3000).unwrap();

    let mut fory_v2 = Fory::default().xlang(false).compatible(true);
    fory_v2.register::<CommandV2>(3000).unwrap();

    // Test 1: Serialize v1, deserialize as v2 (new field gets default value)
    let cmd_v1 = CommandV1::Execute {
        args: 42,
        name: "run".to_string(),
    };
    let bin = fory_v1.serialize(&cmd_v1).unwrap();
    let cmd_v2: CommandV2 = fory_v2.deserialize(&bin).expect("deserialize v1 to v2");
    match cmd_v2 {
        CommandV2::Execute { args, env, name } => {
            assert_eq!(args, 42);
            assert_eq!(name, "run");
            assert_eq!(env, ""); // Default value for missing field
        }
        _ => panic!("Expected Execute variant"),
    }

    // Test 2: Serialize v2, deserialize as v1 (extra field is skipped)
    let cmd_v2 = CommandV2::Execute {
        args: 100,
        env: "prod".to_string(),
        name: "test".to_string(),
    };
    let bin = fory_v2.serialize(&cmd_v2).unwrap();
    let cmd_v1: CommandV1 = fory_v1.deserialize(&bin).expect("deserialize v2 to v1");
    match cmd_v1 {
        CommandV1::Execute { args, name } => {
            assert_eq!(args, 100);
            assert_eq!(name, "test");
            // 'env' field is skipped
        }
        _ => panic!("Expected Execute variant"),
    }
}

/// Test 1: Named enum variant with field changes (add/change/reduce simple and complex fields)
#[test]
fn test_named_enum_field_evolution() {
    // Version 1: Original schema with basic fields
    #[derive(ForyObject, Debug, PartialEq)]
    enum ConfigV1 {
        #[fory(default)]
        Empty,
        Settings {
            timeout: i32,
            retry: bool,
        },
    }

    // Version 2: Add simple field and complex field (Vec)
    #[derive(ForyObject, Debug, PartialEq)]
    enum ConfigV2 {
        #[fory(default)]
        Empty,
        Settings {
            timeout: i32,
            retry: bool,
            max_connections: u32,       // New simple field
            allowed_hosts: Vec<String>, // New complex field
        },
    }

    // Version 3: Change field type and remove field
    #[derive(ForyObject, Debug, PartialEq)]
    enum ConfigV3 {
        #[fory(default)]
        Empty,
        Settings {
            timeout: i32, // Keep same type for compatibility
            // retry field removed
            max_connections: u32,
            allowed_hosts: Vec<String>,
            metadata: Option<String>, // New optional field
        },
    }

    let mut fory_v1 = Fory::default().xlang(false).compatible(true);
    fory_v1.register::<ConfigV1>(4000).unwrap();

    let mut fory_v2 = Fory::default().xlang(false).compatible(true);
    fory_v2.register::<ConfigV2>(4000).unwrap();

    let mut fory_v3 = Fory::default().xlang(false).compatible(true);
    fory_v3.register::<ConfigV3>(4000).unwrap();

    // Test V1 -> V2: New fields get default values
    let config_v1 = ConfigV1::Settings {
        timeout: 30,
        retry: true,
    };
    let bin = fory_v1.serialize(&config_v1).unwrap();
    let config_v2: ConfigV2 = fory_v2.deserialize(&bin).expect("v1 to v2");
    match config_v2 {
        ConfigV2::Settings {
            timeout,
            retry,
            max_connections,
            allowed_hosts,
        } => {
            assert_eq!(timeout, 30);
            assert!(retry);
            assert_eq!(max_connections, 0); // Default value
            assert_eq!(allowed_hosts, Vec::<String>::new()); // Empty vector
        }
        _ => panic!("Expected Settings variant"),
    }

    // Test V2 -> V1: Extra fields are skipped
    let config_v2 = ConfigV2::Settings {
        timeout: 60,
        retry: false,
        max_connections: 100,
        allowed_hosts: vec!["localhost".to_string(), "example.com".to_string()],
    };
    let bin = fory_v2.serialize(&config_v2).unwrap();
    let config_v1: ConfigV1 = fory_v1.deserialize(&bin).expect("v2 to v1");
    match config_v1 {
        ConfigV1::Settings { timeout, retry } => {
            assert_eq!(timeout, 60);
            assert!(!retry);
            // max_connections and allowed_hosts are skipped
        }
        _ => panic!("Expected Settings variant"),
    }

    // Test V2 -> V3: Field removal (retry field is removed)
    let config_v2_2 = ConfigV2::Settings {
        timeout: 45,
        retry: true,
        max_connections: 50,
        allowed_hosts: vec!["api.example.com".to_string()],
    };
    let bin = fory_v2.serialize(&config_v2_2).unwrap();
    let config_v3: ConfigV3 = fory_v3.deserialize(&bin).expect("v2 to v3");
    match config_v3 {
        ConfigV3::Settings {
            timeout,
            max_connections,
            allowed_hosts,
            metadata,
        } => {
            assert_eq!(timeout, 45); // Same type preserved
            assert_eq!(max_connections, 50);
            assert_eq!(allowed_hosts, vec!["api.example.com".to_string()]);
            assert_eq!(metadata, None); // New optional field gets None
        }
        _ => panic!("Expected Settings variant"),
    }
}

/// Test 2: Enum add/remove named variant with complex fields
#[test]
fn test_named_enum_variant_add_remove() {
    // Version 1: Two variants
    #[derive(ForyObject, Debug, PartialEq)]
    enum TaskV1 {
        #[fory(default)]
        Idle,
        Running {
            task_id: u64,
            progress: f32,
        },
    }

    // Version 2: Add new named variant with complex fields
    #[derive(ForyObject, Debug, PartialEq)]
    enum TaskV2 {
        #[fory(default)]
        Idle,
        Running {
            task_id: u64,
            progress: f32,
        },
        Completed {
            task_id: u64,
            result: Vec<String>,
            metadata: Option<Vec<i32>>,
        },
    }

    // Version 3: Remove Running, add Failed variant
    #[derive(ForyObject, Debug, PartialEq)]
    enum TaskV3 {
        #[fory(default)]
        Idle,
        // Running removed
        Completed {
            task_id: u64,
            result: Vec<String>,
            metadata: Option<Vec<i32>>,
        },
        Failed {
            error_code: i32,
            error_message: String,
        },
    }

    let mut fory_v1 = Fory::default().xlang(false).compatible(true);
    fory_v1.register::<TaskV1>(5000).unwrap();

    let mut fory_v2 = Fory::default().xlang(false).compatible(true);
    fory_v2.register::<TaskV2>(5000).unwrap();

    let mut fory_v3 = Fory::default().xlang(false).compatible(true);
    fory_v3.register::<TaskV3>(5000).unwrap();

    // Test V2 (new variant Completed) -> V1: Unknown variant falls back to default
    let task_v2 = TaskV2::Completed {
        task_id: 123,
        result: vec!["success".to_string(), "data".to_string()],
        metadata: Some(vec![1, 2, 3]),
    };
    let bin = fory_v2.serialize(&task_v2).unwrap();
    let task_v1: TaskV1 = fory_v1.deserialize(&bin).expect("v2 to v1");
    assert_eq!(task_v1, TaskV1::Idle); // Falls back to default

    // Test V1 (Running) -> V2: Existing variant deserializes correctly
    let task_v1 = TaskV1::Running {
        task_id: 456,
        progress: 0.75,
    };
    let bin = fory_v1.serialize(&task_v1).unwrap();
    let task_v2: TaskV2 = fory_v2.deserialize(&bin).expect("v1 to v2");
    match task_v2 {
        TaskV2::Running { task_id, progress } => {
            assert_eq!(task_id, 456);
            assert_eq!(progress, 0.75);
        }
        _ => panic!("Expected Running variant"),
    }

    // Test V2 (Running) -> V3: Running (index 1) maps to Completed (index 1)
    // In compatible mode, variant indices are preserved, so they match by position
    let task_v2_running = TaskV2::Running {
        task_id: 789,
        progress: 0.5,
    };
    let bin = fory_v2.serialize(&task_v2_running).unwrap();
    let task_v3: TaskV3 = fory_v3.deserialize(&bin).expect("v2 running to v3");
    // V2's Running (index 1) maps to V3's Completed (index 1) by variant index
    match task_v3 {
        TaskV3::Completed {
            task_id,
            result,
            metadata,
        } => {
            // Fields are read based on V3's Completed schema, but data was from V2's Running
            // This demonstrates schema mismatch - fields get default values or partial data
            assert_eq!(task_id, 789); // task_id exists in both, so preserved
            assert_eq!(result, Vec::<String>::new()); // Not in source, gets default
            assert_eq!(metadata, None); // Not in source, gets default
        }
        _ => panic!("Expected Completed variant (mapped from Running by index)"),
    }

    // Test V3 (Failed) -> V2: V3's Failed (index 2) maps to V2's Completed (index 2)
    let task_v3 = TaskV3::Failed {
        error_code: 500,
        error_message: "Internal error".to_string(),
    };
    let bin = fory_v3.serialize(&task_v3).unwrap();
    let task_v2: TaskV2 = fory_v2.deserialize(&bin).expect("v3 failed to v2");
    // V3's Failed (index 2) maps to V2's Completed (index 2) by variant index
    match task_v2 {
        TaskV2::Completed {
            task_id,
            result,
            metadata,
        } => {
            // Fields get default values since schemas don't match
            assert_eq!(task_id, 0); // Default value
            assert_eq!(result, Vec::<String>::new()); // Default value
            assert_eq!(metadata, None); // Default value
        }
        _ => panic!("Expected Completed variant (mapped from Failed by index)"),
    }

    // Test V3 (Completed) -> V2: V3's Completed (index 1) maps to V2's Running (index 1)
    let task_v3_completed = TaskV3::Completed {
        task_id: 999,
        result: vec!["done".to_string()],
        metadata: None,
    };
    let bin = fory_v3.serialize(&task_v3_completed).unwrap();
    let task_v2: TaskV2 = fory_v2.deserialize(&bin).expect("v3 completed to v2");
    // V3's Completed (index 1) maps to V2's Running (index 1) by variant index
    match task_v2 {
        TaskV2::Running { task_id, progress } => {
            // Fields are read based on V2's Running schema
            assert_eq!(task_id, 999); // task_id exists in both, preserved
            assert_eq!(progress, 0.0); // Default value (not in V3's Completed)
        }
        _ => panic!("Expected Running variant (mapped from Completed by index)"),
    }
}

/// Test 3: Change enum variant type between unit/unnamed/named
#[test]
fn test_enum_variant_type_change() {
    // Version 1: Different variant types
    #[derive(ForyObject, Debug, PartialEq)]
    enum StatusV1 {
        #[fory(default)]
        Unknown,
        Active,          // Unit variant
        Processing(i32), // Unnamed variant
        Finished {
            code: i32,
        }, // Named variant
    }

    // Version 2: Change variant types
    #[derive(ForyObject, Debug, PartialEq)]
    enum StatusV2 {
        #[fory(default)]
        Unknown,
        Active {
            timestamp: i64,
        }, // Unit -> Named
        Processing {
            value: i32,
            name: String,
        }, // Unnamed -> Named
        Finished(i32), // Named -> Unnamed
    }

    // Version 3: More type changes
    #[derive(ForyObject, Debug, PartialEq)]
    enum StatusV3 {
        #[fory(default)]
        Unknown,
        Active,                  // Named -> Unit
        Processing(i32, String), // Named -> Unnamed (2 fields)
        Finished,                // Unnamed -> Unit
    }

    let mut fory_v1 = Fory::default().xlang(false).compatible(true);
    fory_v1.register::<StatusV1>(6000).unwrap();

    let mut fory_v2 = Fory::default().xlang(false).compatible(true);
    fory_v2.register::<StatusV2>(6000).unwrap();

    let mut fory_v3 = Fory::default().xlang(false).compatible(true);
    fory_v3.register::<StatusV3>(6000).unwrap();

    // Test V1 Unit -> V2 Named: Type mismatch, uses default value
    let status_v1_active = StatusV1::Active;
    let bin = fory_v1.serialize(&status_v1_active).unwrap();
    let status_v2: StatusV2 = fory_v2.deserialize(&bin).expect("v1 unit to v2 named");
    match status_v2 {
        StatusV2::Active { timestamp } => {
            assert_eq!(timestamp, 0); // Default value for the named variant
        }
        _ => panic!("Expected Active variant with default fields"),
    }

    // Test V1 Unnamed -> V2 Named: Type mismatch, uses default value
    let status_v1_processing = StatusV1::Processing(42);
    let bin = fory_v1.serialize(&status_v1_processing).unwrap();
    let status_v2: StatusV2 = fory_v2.deserialize(&bin).expect("v1 unnamed to v2 named");
    match status_v2 {
        StatusV2::Processing { value, name } => {
            assert_eq!(value, 0); // Default value
            assert_eq!(name, ""); // Default value
        }
        _ => panic!("Expected Processing variant with default fields"),
    }

    // Test V1 Named -> V2 Unnamed: Type mismatch, uses default value
    let status_v1_finished = StatusV1::Finished { code: 200 };
    let bin = fory_v1.serialize(&status_v1_finished).unwrap();
    let status_v2: StatusV2 = fory_v2.deserialize(&bin).expect("v1 named to v2 unnamed");
    match status_v2 {
        StatusV2::Finished(code) => {
            assert_eq!(code, 0); // Default value for unnamed variant
        }
        _ => panic!("Expected Finished variant with default value"),
    }

    // Test V2 Named -> V3 Unit: Type mismatch, uses default unit variant
    let status_v2_active = StatusV2::Active { timestamp: 123456 };
    let bin = fory_v2.serialize(&status_v2_active).unwrap();
    let status_v3: StatusV3 = fory_v3.deserialize(&bin).expect("v2 named to v3 unit");
    assert_eq!(status_v3, StatusV3::Active); // Unit variant

    // Test V2 Named -> V3 Unnamed (2 fields): Type mismatch, uses default values
    let status_v2_processing = StatusV2::Processing {
        value: 99,
        name: "test".to_string(),
    };
    let bin = fory_v2.serialize(&status_v2_processing).unwrap();
    let status_v3: StatusV3 = fory_v3.deserialize(&bin).expect("v2 named to v3 unnamed");
    match status_v3 {
        StatusV3::Processing(value, name) => {
            assert_eq!(value, 0); // Default value
            assert_eq!(name, ""); // Default value
        }
        _ => panic!("Expected Processing variant with default values"),
    }

    // Test V2 Unnamed -> V3 Unit: Type mismatch, uses default unit variant
    let status_v2_finished = StatusV2::Finished(404);
    let bin = fory_v2.serialize(&status_v2_finished).unwrap();
    let status_v3: StatusV3 = fory_v3.deserialize(&bin).expect("v2 unnamed to v3 unit");
    assert_eq!(status_v3, StatusV3::Finished); // Unit variant
}

/// Test 4: Struct containing enum with schema evolution
#[test]
fn test_struct_with_enum_field_evolution() {
    // Version 1: Struct with enum field
    #[derive(ForyObject, Debug, PartialEq)]
    enum StateV1 {
        #[fory(default)]
        Init,
        Ready {
            id: u32,
        },
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct MessageV1 {
        timestamp: i64,
        state: StateV1,
        payload: String,
    }

    // Version 2: Enum evolved with new fields and variant
    #[derive(ForyObject, Debug, PartialEq)]
    enum StateV2 {
        #[fory(default)]
        Init,
        Ready {
            id: u32,
            version: String, // New field
        },
        Error {
            code: i32,
            message: String,
        }, // New variant
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct MessageV2 {
        timestamp: i64,
        state: StateV2,
        payload: String,
        priority: i32, // New field in struct
    }

    // Version 3: Enum variant type changed
    #[derive(ForyObject, Debug, PartialEq)]
    enum StateV3 {
        #[fory(default)]
        Init,
        Ready(u32, String), // Changed from named to unnamed
        Error {
            code: i32,
            message: String,
        },
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct MessageV3 {
        timestamp: i64,
        state: StateV3,
        payload: String,
        priority: i32,
        sender: Option<String>, // New optional field
    }

    let mut fory_v1 = Fory::default().xlang(false).compatible(true);
    fory_v1.register::<StateV1>(7001).unwrap();
    fory_v1.register::<MessageV1>(7000).unwrap();

    let mut fory_v2 = Fory::default().xlang(false).compatible(true);
    fory_v2.register::<StateV2>(7001).unwrap();
    fory_v2.register::<MessageV2>(7000).unwrap();

    let mut fory_v3 = Fory::default().xlang(false).compatible(true);
    fory_v3.register::<StateV3>(7001).unwrap();
    fory_v3.register::<MessageV3>(7000).unwrap();

    // Test V1 -> V2: Enum field evolution
    let msg_v1 = MessageV1 {
        timestamp: 1000,
        state: StateV1::Ready { id: 42 },
        payload: "hello".to_string(),
    };
    let bin = fory_v1.serialize(&msg_v1).unwrap();
    let msg_v2: MessageV2 = fory_v2.deserialize(&bin).expect("v1 to v2");
    assert_eq!(msg_v2.timestamp, 1000);
    match msg_v2.state {
        StateV2::Ready { id, version } => {
            assert_eq!(id, 42);
            assert_eq!(version, ""); // Default value for new enum field
        }
        _ => panic!("Expected Ready variant"),
    }
    assert_eq!(msg_v2.payload, "hello");
    assert_eq!(msg_v2.priority, 0); // Default value for new struct field

    // Test V2 -> V1: Extra fields skipped
    let msg_v2 = MessageV2 {
        timestamp: 2000,
        state: StateV2::Ready {
            id: 100,
            version: "v2.0".to_string(),
        },
        payload: "world".to_string(),
        priority: 5,
    };
    let bin = fory_v2.serialize(&msg_v2).unwrap();
    let msg_v1: MessageV1 = fory_v1.deserialize(&bin).expect("v2 to v1");
    assert_eq!(msg_v1.timestamp, 2000);
    match msg_v1.state {
        StateV1::Ready { id } => {
            assert_eq!(id, 100);
            // version field skipped
        }
        _ => panic!("Expected Ready variant"),
    }
    assert_eq!(msg_v1.payload, "world");
    // priority field skipped

    // Test V2 (new variant) -> V1: Unknown enum variant falls back to default
    let msg_v2_error = MessageV2 {
        timestamp: 3000,
        state: StateV2::Error {
            code: 500,
            message: "error".to_string(),
        },
        payload: "fail".to_string(),
        priority: 10,
    };
    let bin = fory_v2.serialize(&msg_v2_error).unwrap();
    let msg_v1: MessageV1 = fory_v1.deserialize(&bin).expect("v2 error to v1");
    assert_eq!(msg_v1.timestamp, 3000);
    assert_eq!(msg_v1.state, StateV1::Init); // Falls back to default
    assert_eq!(msg_v1.payload, "fail");

    // Test V2 -> V3: Enum variant type change (named to unnamed)
    let msg_v2_ready = MessageV2 {
        timestamp: 4000,
        state: StateV2::Ready {
            id: 200,
            version: "v2.5".to_string(),
        },
        payload: "test".to_string(),
        priority: 3,
    };
    let bin = fory_v2.serialize(&msg_v2_ready).unwrap();
    let msg_v3: MessageV3 = fory_v3.deserialize(&bin).expect("v2 to v3");
    assert_eq!(msg_v3.timestamp, 4000);
    match msg_v3.state {
        StateV3::Ready(id, version) => {
            assert_eq!(id, 0); // Default value due to type mismatch
            assert_eq!(version, ""); // Default value due to type mismatch
        }
        _ => panic!("Expected Ready variant with default values"),
    }
    assert_eq!(msg_v3.payload, "test");
    assert_eq!(msg_v3.priority, 3);
    assert_eq!(msg_v3.sender, None); // New optional field

    // Test V3 -> V2: Enum variant type change (unnamed to named)
    let msg_v3 = MessageV3 {
        timestamp: 5000,
        state: StateV3::Ready(300, "v3.0".to_string()),
        payload: "data".to_string(),
        priority: 7,
        sender: Some("system".to_string()),
    };
    let bin = fory_v3.serialize(&msg_v3).unwrap();
    let msg_v2: MessageV2 = fory_v2.deserialize(&bin).expect("v3 to v2");
    assert_eq!(msg_v2.timestamp, 5000);
    match msg_v2.state {
        StateV2::Ready { id, version } => {
            assert_eq!(id, 0); // Default value due to type mismatch
            assert_eq!(version, ""); // Default value due to type mismatch
        }
        _ => panic!("Expected Ready variant with default values"),
    }
    assert_eq!(msg_v2.payload, "data");
    assert_eq!(msg_v2.priority, 7);
    // sender field skipped

    // Test V3 (Error variant) -> V2 (Error variant): Same variant works
    let msg_v3_error = MessageV3 {
        timestamp: 6000,
        state: StateV3::Error {
            code: 404,
            message: "not found".to_string(),
        },
        payload: "error".to_string(),
        priority: 1,
        sender: None,
    };
    let bin = fory_v3.serialize(&msg_v3_error).unwrap();
    let msg_v2: MessageV2 = fory_v2.deserialize(&bin).expect("v3 error to v2");
    assert_eq!(msg_v2.timestamp, 6000);
    match msg_v2.state {
        StateV2::Error { code, message } => {
            assert_eq!(code, 404);
            assert_eq!(message, "not found");
        }
        _ => panic!("Expected Error variant"),
    }
    assert_eq!(msg_v2.payload, "error");
    assert_eq!(msg_v2.priority, 1);
}
