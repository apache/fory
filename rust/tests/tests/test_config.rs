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

use fory_core::{
    fory::{get_default_config, set_default_config, Config},
    Fory,
};

#[test]
fn test_config() {
    let default_cfg = get_default_config();
    assert!(!default_cfg.compatible);
    let fory = Fory::default();
    assert_eq!(fory.is_compatible(), default_cfg.compatible);
    let compatible = true;
    let new_cfg = Config {
        compatible,
        ..default_cfg
    };
    set_default_config(new_cfg);
    let fory = Fory::default();
    assert_eq!(fory.is_compatible(), compatible);
}
