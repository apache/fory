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

use crate::type_id;
use std::any::Any;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::sync::Arc;

#[derive(Clone)]
pub struct UnknownCase {
    case_id: u32,
    type_id: u32,
    // Keep resolver TypeInfo/Rc out of the carrier. Generated unions can outlive or move
    // independently from the resolver context, so the carrier stores only stable metadata
    // plus a dynamic payload whose `Send + Sync` guarantee is carried by the trait object.
    value: Arc<dyn Any + Send + Sync>,
}

impl UnknownCase {
    /// Creates a fresh carrier for user-supplied unknown payloads.
    ///
    /// Replay metadata is owned by the runtime read path; public construction
    /// always uses the ordinary Any writer.
    pub fn new<T>(case_id: u32, value: T) -> Self
    where
        T: Any + Send + Sync,
    {
        Self {
            case_id,
            type_id: type_id::UNKNOWN,
            value: Arc::new(value),
        }
    }

    pub fn case_id(&self) -> u32 {
        self.case_id
    }

    pub(crate) fn type_id(&self) -> u32 {
        self.type_id
    }

    pub fn value(&self) -> &(dyn Any + Send + Sync) {
        self.value.as_ref()
    }

    pub fn downcast_ref<T: Any>(&self) -> Option<&T> {
        self.value.downcast_ref::<T>()
    }

    pub(crate) fn value_arc(&self) -> &Arc<dyn Any + Send + Sync> {
        &self.value
    }

    pub(crate) fn from_runtime(
        case_id: u32,
        type_id: u32,
        value: Arc<dyn Any + Send + Sync>,
    ) -> Self {
        Self {
            case_id,
            type_id,
            value,
        }
    }
}

impl PartialEq for UnknownCase {
    fn eq(&self, other: &Self) -> bool {
        // UnknownCase equality is carrier identity. The replay metadata controls
        // wire preservation, but it is intentionally not structural equality.
        Arc::ptr_eq(&self.value, &other.value)
    }
}

impl Eq for UnknownCase {}

impl Hash for UnknownCase {
    fn hash<H: Hasher>(&self, state: &mut H) {
        let ptr = Arc::as_ptr(&self.value) as *const () as usize;
        ptr.hash(state);
    }
}

impl fmt::Debug for UnknownCase {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let value_ptr = Arc::as_ptr(&self.value) as *const ();
        f.debug_struct("UnknownCase")
            .field("case_id", &self.case_id)
            .field("value_ptr", &value_ptr)
            .finish_non_exhaustive()
    }
}

#[cfg(test)]
mod tests {
    use super::UnknownCase;
    use std::any::Any;
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    use std::sync::Arc;

    fn hash_of(value: &UnknownCase) -> u64 {
        let mut hasher = DefaultHasher::new();
        value.hash(&mut hasher);
        hasher.finish()
    }

    #[test]
    fn unknown_case_is_send_sync() {
        fn assert_send_sync<T: Send + Sync>() {}

        assert_send_sync::<UnknownCase>();
    }

    #[test]
    fn equality_uses_carrier_identity() {
        let first = UnknownCase::new(7, String::from("future"));
        let same_carrier = first.clone();
        let same_content = UnknownCase::new(7, String::from("future"));

        assert_eq!(first, same_carrier);
        assert_eq!(hash_of(&first), hash_of(&same_carrier));
        assert_ne!(first, same_content);
    }

    #[test]
    fn replay_metadata_does_not_affect_identity() {
        let value: Arc<dyn Any + Send + Sync> = Arc::new(String::from("future"));
        let first = UnknownCase::from_runtime(7, 21, value.clone());
        let same_payload = UnknownCase::from_runtime(8, 5, value);

        assert_eq!(first, same_payload);
        assert_eq!(hash_of(&first), hash_of(&same_payload));
        assert_eq!(first.case_id(), 7);
        assert_eq!(same_payload.case_id(), 8);
    }
}
