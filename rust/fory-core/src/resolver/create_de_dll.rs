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

use libloading::{Library, Symbol};
use std::collections::HashMap;
use std::fs;
use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use tempfile::TempDir;

use crate::meta::FieldType;
use crate::resolver::context::ReadContext;

pub struct DeFn {
    _lib: Arc<Library>,
    pub func: Symbol<'static, unsafe extern "C" fn(&mut ReadContext)>,
}

pub enum OutputDir {
    #[allow(dead_code)]
    Temp(TempDir),
    // for debug
    Pwd(String),
}

fn get_lib_path(crate_dir: &Path, filename: &str) -> (PathBuf, PathBuf) {
    let src_dir = crate_dir.join("src");
    fs::create_dir_all(&src_dir).unwrap();
    let lib_name = format!(
        "libgenerated_{}{}",
        filename,
        if cfg!(target_os = "linux") {
            ".so"
        } else if cfg!(target_os = "macos") {
            ".dylib"
        } else {
            ".dll"
        }
    );
    (
        src_dir.join("lib.rs"),
        crate_dir.join("target/release").join(lib_name),
    )
}

#[allow(clippy::missing_transmute_annotations)]
#[warn(irrefutable_let_patterns)]
pub fn create_fn(field_type: FieldType, id_typename_map: &HashMap<i16, String>) -> DeFn {
    let output_dir = OutputDir::Pwd("generated".to_string());
    if let OutputDir::Pwd(path) = &output_dir {
        fs::create_dir_all(path).unwrap();
    }

    let crate_folder_name = {
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        field_type.hash(&mut hasher);
        hasher.finish().to_string()
    };
    let crate_dir = match &output_dir {
        OutputDir::Pwd(path) => PathBuf::from(path).join(&crate_folder_name),
        OutputDir::Temp(_) => {
            todo!()
        }
    };
    fs::create_dir_all(&crate_dir).unwrap();

    let (rs_path, lib_path) = get_lib_path(&crate_dir, &crate_folder_name);
    println!("{:?}", id_typename_map);
    let typ = field_type.to_string(id_typename_map);
    let cwd = std::env::current_dir().unwrap();
    let code = format!(
        r#"use fory_core::resolver::context::ReadContext;
use fory_core::serializer::Serializer;
#[no_mangle]
pub extern "C" fn deserialize_field(context: &mut ReadContext) {{
    <{typ} as Serializer>::deserialize(context);
}}"#,
        typ = typ,
    );

    fs::write(&rs_path, code).unwrap();

    let cargo_toml = format!(
        r#"[package]
name = "generated_{filename}"
version = "0.1.0"
edition = "2018"

[lib]
crate-type = ["cdylib"]

[workspace]

[dependencies]
fory-core = {{ path = "../../../fory-core" }}
"#,
        filename = crate_folder_name
    );
    fs::write(crate_dir.join("Cargo.toml"), cargo_toml).unwrap();

    let abs_lib_path = cwd.join(lib_path.clone());
    println!(
        "Start create dylib for {:?}: {:?}",
        typ,
        cwd.join(rs_path).clone()
    );

    let status = Command::new("cargo")
        .current_dir(&crate_dir)
        .env("RUSTFLAGS", "-Awarnings")
        .stdout(Stdio::null())
        .args(["build", "--release"])
        .status()
        .expect("Failed to run cargo build");
    assert!(status.success());
    // if let OutputDir::PWD(_) = output_dir {
    //     fs::remove_file(&rs_path).unwrap();
    // }

    let lib = Arc::new(unsafe { Library::new(&abs_lib_path).unwrap() });
    unsafe {
        let func: Symbol<unsafe extern "C" fn(&mut ReadContext)> =
            lib.get(b"deserialize_field").unwrap();
        DeFn {
            _lib: lib.clone(),
            func: std::mem::transmute(func),
        }
    }
}
