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

use std::any::Any;
use std::sync::{Mutex, MutexGuard, OnceLock};

use fory_core::fory::Fory;
use fory_core::resolver::context::{ReadContext, WriteContext};
use fory_core::serializer::struct_::{
    reset_struct_debug_hooks, set_after_read_field_func, set_after_write_field_func,
    set_before_read_field_func, set_before_write_field_func,
};

#[derive(fory_derive::ForyObject)]
#[fory(debug)]
struct DebugSample {
    a: i32,
    b: String,
}

fn event_log() -> &'static Mutex<Vec<String>> {
    static LOG: OnceLock<Mutex<Vec<String>>> = OnceLock::new();
    LOG.get_or_init(|| Mutex::new(Vec::new()))
}

fn record_event(prefix: &str, struct_name: &str, field_name: &str) {
    if struct_name == "DebugSample" {
        event_log()
            .lock()
            .unwrap()
            .push(format!("{prefix}:{struct_name}:{field_name}"));
    }
}

fn before_write(
    struct_name: &str,
    field_name: &str,
    _field_value: &dyn Any,
    _context: &mut WriteContext,
) {
    record_event("write", struct_name, field_name);
}

fn after_write(
    struct_name: &str,
    field_name: &str,
    _field_value: &dyn Any,
    _context: &mut WriteContext,
) {
    record_event("after_write", struct_name, field_name);
}

fn before_read(struct_name: &str, field_name: &str, _context: &mut ReadContext) {
    record_event("before_read", struct_name, field_name);
}

fn after_read(
    struct_name: &str,
    field_name: &str,
    _field_value: &dyn Any,
    _context: &mut ReadContext,
) {
    record_event("after_read", struct_name, field_name);
}

struct HookGuard {
    _lock: MutexGuard<'static, ()>,
}

impl HookGuard {
    fn new() -> Self {
        static HOOK_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
        let lock = HOOK_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap();
        reset_struct_debug_hooks();
        event_log().lock().unwrap().clear();
        HookGuard { _lock: lock }
    }
}

impl Drop for HookGuard {
    fn drop(&mut self) {
        reset_struct_debug_hooks();
        event_log().lock().unwrap().clear();
    }
}

#[test]
fn debug_hooks_trigger_for_struct() {
    let _guard = HookGuard::new();

    set_before_write_field_func(before_write);
    set_after_write_field_func(after_write);
    set_before_read_field_func(before_read);
    set_after_read_field_func(after_read);

    let mut fory = Fory::default();
    fory.register::<DebugSample>(4001).unwrap();
    let sample = DebugSample {
        a: 7,
        b: "debug".to_string(),
    };

    let bytes = fory.serialize(&sample).unwrap();
    let _: DebugSample = fory.deserialize(&bytes).unwrap();

    let entries = event_log().lock().unwrap().clone();
    assert_eq!(
        entries.iter().filter(|e| e.starts_with("write:")).count(),
        2
    );
    assert_eq!(
        entries
            .iter()
            .filter(|e| e.starts_with("after_write:"))
            .count(),
        2
    );
    assert_eq!(
        entries
            .iter()
            .filter(|e| e.starts_with("before_read:"))
            .count(),
        2
    );
    assert_eq!(
        entries
            .iter()
            .filter(|e| e.starts_with("after_read:"))
            .count(),
        2
    );
    assert!(entries.contains(&"write:DebugSample:a".to_string()));
    assert!(entries.contains(&"after_write:DebugSample:a".to_string()));
    assert!(entries.contains(&"before_read:DebugSample:a".to_string()));
    assert!(entries.contains(&"after_read:DebugSample:b".to_string()));

    event_log().lock().unwrap().clear();

    let mut fory_compat = Fory::default().compatible(true);
    fory_compat.register::<DebugSample>(4001).unwrap();
    let compat_bytes = fory_compat.serialize(&sample).unwrap();
    let _: DebugSample = fory_compat.deserialize(compat_bytes.as_slice()).unwrap();

    let compat_entries = event_log().lock().unwrap().clone();
    assert!(
        compat_entries
            .iter()
            .filter(|e| e.starts_with("write:"))
            .count()
            >= 2
    );
    assert!(
        compat_entries
            .iter()
            .filter(|e| e.starts_with("after_write:"))
            .count()
            >= 2
    );
    assert!(
        compat_entries
            .iter()
            .filter(|e| e.starts_with("before_read:"))
            .count()
            >= 2
    );
    assert!(
        compat_entries
            .iter()
            .filter(|e| e.starts_with("after_read:"))
            .count()
            >= 2
    );
}
