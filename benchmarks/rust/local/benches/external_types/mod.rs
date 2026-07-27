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

use criterion::{black_box, Criterion};
use fory::{
    register_trait_type, ArcSerializer, ArcWeak, ArcWeakSerializer, ArraySerializer,
    BTreeMapSerializer, BTreeSetSerializer, BinaryHeapSerializer, BoxSerializer, Error, Fory,
    ForyObject, HashMapSerializer, HashSetSerializer, LinkedListSerializer, MutexSerializer,
    OptionSerializer, RcSerializer, RcWeak, RcWeakSerializer, ReadContext, RefCellSerializer,
    Serializer, Tuple1Serializer, Tuple22Serializer, Tuple2Serializer, Tuple3Serializer,
    VecDequeSerializer, VecSerializer, WriteContext,
};
use fory_derive::{ForyStruct, ForyUnion};
use fory_external_model::{Command, ExternalId, Key, User, UserWithState};
use std::any::Any;
use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, BinaryHeap, HashMap, HashSet, LinkedList, VecDeque};
use std::rc::Rc;
use std::sync::{Arc, Mutex};

const USER_ID: u32 = 200;
const KEY_ID: u32 = 201;
const COMMAND_ID: u32 = 202;
const MANUAL_ID: u32 = 203;
const DIRECT_FIELD_ID: u32 = 204;
const SKIPPED_FIELD_ID: u32 = 205;
const COMPOSITE_FIELD_ID: u32 = 206;
const EXACT_CONTAINER_ID: u32 = 207;
const COMMAND_FIELD_ID: u32 = 208;

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, ForyStruct)]
struct LocalUser {
    name: String,
    age: u32,
}

#[derive(ForyStruct)]
#[fory(target = User)]
struct UserSerializer {
    name: String,
    age: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, ForyStruct)]
struct LocalKey {
    namespace: String,
    id: u64,
}

#[derive(ForyStruct)]
#[fory(target = Key)]
struct KeySerializer {
    namespace: String,
    id: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, ForyUnion)]
enum LocalCommand {
    #[fory(default)]
    Idle,
    Create {
        id: u128,
        label: String,
    },
    Move(i32, i32),
}

#[derive(ForyUnion)]
#[fory(target = Command)]
enum CommandSerializer {
    #[fory(default)]
    Idle,
    Create {
        id: u128,
        label: String,
    },
    Move(i32, i32),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct LocalId(u128);

impl Serializer for LocalId {
    type Target = Self;

    #[inline(always)]
    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u128(value.0);
        Ok(())
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(Self(context.reader.read_u128()?))
    }

    #[inline(always)]
    fn default_value(_: &mut ReadContext) -> Result<Self, Error> {
        Ok(Self(0))
    }
}

struct ExternalIdSerializer;

impl Serializer for ExternalIdSerializer {
    type Target = ExternalId;

    #[inline(always)]
    fn write_data(value: &ExternalId, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u128(value.0);
        Ok(())
    }

    #[inline(always)]
    fn read_data(context: &mut ReadContext) -> Result<ExternalId, Error> {
        Ok(ExternalId(context.reader.read_u128()?))
    }

    #[inline(always)]
    fn default_value(_: &mut ReadContext) -> Result<ExternalId, Error> {
        Ok(ExternalId(0))
    }
}

#[derive(ForyStruct)]
struct LocalDirectField {
    user: LocalUser,
}

#[derive(ForyStruct)]
struct ExternalDirectField {
    #[fory(with = UserSerializer)]
    user: User,
}

#[derive(ForyStruct)]
struct LocalSkippedField {
    name: String,
    #[allow(dead_code)]
    #[fory(skip)]
    state: LocalId,
}

#[derive(ForyStruct)]
#[fory(target = UserWithState)]
struct UserWithStateSerializer {
    name: String,
    #[fory(skip, with = ExternalIdSerializer)]
    state: ExternalId,
}

#[derive(ForyStruct)]
struct LocalCompositeField {
    users: Vec<LocalUser>,
    by_key: HashMap<LocalKey, LocalUser>,
    tuple: (String, Vec<LocalUser>),
}

#[derive(ForyStruct)]
struct ExternalCompositeField {
    #[fory(list(element(with = UserSerializer)))]
    users: Vec<User>,
    #[fory(map(
        key(with = KeySerializer),
        value(with = UserSerializer)
    ))]
    by_key: HashMap<Key, User>,
    #[fory(tuple(element(
        index = 1,
        list(element(with = UserSerializer))
    )))]
    tuple: (String, Vec<User>),
}

#[derive(ForyStruct)]
struct LocalCommandField {
    commands: Vec<LocalCommand>,
    by_name: HashMap<String, LocalCommand>,
    tuple: (String, LocalCommand),
}

#[derive(ForyStruct)]
struct ExternalCommandField {
    #[fory(list(element(with = CommandSerializer)))]
    commands: Vec<Command>,
    #[fory(map(value(with = CommandSerializer)))]
    by_name: HashMap<String, Command>,
    #[fory(tuple(element(index = 1, with = CommandSerializer)))]
    tuple: (String, Command),
}

struct LocalPackedUsersSerializer;

impl Serializer for LocalPackedUsersSerializer {
    type Target = Vec<LocalUser>;

    fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_var_u32(value.len() as u32);
        for user in value {
            LocalUser::write_data(user, context)?;
        }
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
        let len = read_container_len::<LocalUser>(context)?;
        let mut users = Vec::with_capacity(len);
        for _ in 0..len {
            users.push(LocalUser::read_data(context)?);
        }
        Ok(users)
    }

    fn default_value(_: &mut ReadContext) -> Result<Self::Target, Error> {
        Ok(Vec::new())
    }
}

struct PackedUsersSerializer;

impl Serializer for PackedUsersSerializer {
    type Target = Vec<User>;

    fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_var_u32(value.len() as u32);
        for user in value {
            UserSerializer::write_data(user, context)?;
        }
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
        let len = read_container_len::<User>(context)?;
        let mut users = Vec::with_capacity(len);
        for _ in 0..len {
            users.push(UserSerializer::read_data(context)?);
        }
        Ok(users)
    }

    fn default_value(_: &mut ReadContext) -> Result<Self::Target, Error> {
        Ok(Vec::new())
    }
}

fn read_container_len<T>(context: &mut ReadContext) -> Result<usize, Error> {
    let len = context.reader.read_var_u32()? as usize;
    if context.reader.slice_after_cursor().len() < len {
        return Err(Error::invalid_data(
            "container count exceeds the readable body",
        ));
    }
    let bytes = len
        .checked_mul(std::mem::size_of::<T>())
        .ok_or_else(|| Error::invalid_data("container storage estimate overflows"))?;
    context.reserve_graph_memory(bytes)?;
    Ok(len)
}

trait BenchEntity: ForyObject + Send + Sync {}

impl BenchEntity for LocalUser {}
impl BenchEntity for User {}

register_trait_type!(sync BenchEntity, LocalUser, User);

type LocalTuple22 = (
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    LocalUser,
);

type ExternalTuple22Serializer = Tuple22Serializer<
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    i32,
    UserSerializer,
>;

fn local_user(name: &str, age: u32) -> LocalUser {
    LocalUser {
        name: name.to_owned(),
        age,
    }
}

fn external_user(name: &str, age: u32) -> User {
    User {
        name: name.to_owned(),
        age,
    }
}

fn local_key(namespace: &str, id: u64) -> LocalKey {
    LocalKey {
        namespace: namespace.to_owned(),
        id,
    }
}

fn external_key(namespace: &str, id: u64) -> Key {
    Key {
        namespace: namespace.to_owned(),
        id,
    }
}

fn build_pair(compatible: bool) -> (Fory, Fory) {
    let mut local = Fory::builder()
        .xlang(false)
        .compatible(compatible)
        .track_ref(true)
        .build();
    local.register::<LocalUser>(USER_ID).unwrap();
    local.register::<LocalKey>(KEY_ID).unwrap();
    local.register::<LocalCommand>(COMMAND_ID).unwrap();
    local.register_serializer::<LocalId>(MANUAL_ID).unwrap();
    local.register::<LocalDirectField>(DIRECT_FIELD_ID).unwrap();
    local
        .register::<LocalSkippedField>(SKIPPED_FIELD_ID)
        .unwrap();
    local
        .register::<LocalCompositeField>(COMPOSITE_FIELD_ID)
        .unwrap();
    local
        .register_serializer::<LocalPackedUsersSerializer>(EXACT_CONTAINER_ID)
        .unwrap();
    local
        .register::<LocalCommandField>(COMMAND_FIELD_ID)
        .unwrap();

    let mut external = Fory::builder()
        .xlang(false)
        .compatible(compatible)
        .track_ref(true)
        .build();
    external.register::<UserSerializer>(USER_ID).unwrap();
    external.register::<KeySerializer>(KEY_ID).unwrap();
    external.register::<CommandSerializer>(COMMAND_ID).unwrap();
    external
        .register_serializer::<ExternalIdSerializer>(MANUAL_ID)
        .unwrap();
    external
        .register::<ExternalDirectField>(DIRECT_FIELD_ID)
        .unwrap();
    external
        .register::<UserWithStateSerializer>(SKIPPED_FIELD_ID)
        .unwrap();
    external
        .register::<ExternalCompositeField>(COMPOSITE_FIELD_ID)
        .unwrap();
    external
        .register_serializer::<PackedUsersSerializer>(EXACT_CONTAINER_ID)
        .unwrap();
    external
        .register::<ExternalCommandField>(COMMAND_FIELD_ID)
        .unwrap();

    (local, external)
}

fn run_pair<L, S>(
    criterion: &mut Criterion,
    name: &'static str,
    local_fory: &Fory,
    external_fory: &Fory,
    local_value: L::Target,
    external_value: S::Target,
) where
    L: Serializer,
    S: Serializer,
{
    let local_bytes = local_fory.serialize_with::<L>(&local_value).unwrap();
    let external_bytes = external_fory.serialize_with::<S>(&external_value).unwrap();
    assert_eq!(
        local_bytes, external_bytes,
        "{name} must perform equivalent wire work"
    );
    let _: L::Target = local_fory.deserialize_with::<L>(&local_bytes).unwrap();
    let _: S::Target = external_fory
        .deserialize_with::<S>(&external_bytes)
        .unwrap();

    let mut group = criterion.benchmark_group(name);
    group.bench_function("self_serialize", |bencher| {
        bencher.iter(|| {
            black_box(
                local_fory
                    .serialize_with::<L>(black_box(&local_value))
                    .unwrap(),
            )
        })
    });
    group.bench_function("selected_serialize", |bencher| {
        bencher.iter(|| {
            black_box(
                external_fory
                    .serialize_with::<S>(black_box(&external_value))
                    .unwrap(),
            )
        })
    });
    group.bench_function("self_deserialize", |bencher| {
        bencher.iter(|| {
            black_box(
                local_fory
                    .deserialize_with::<L>(black_box(&local_bytes))
                    .unwrap(),
            )
        })
    });
    group.bench_function("selected_deserialize", |bencher| {
        bencher.iter(|| {
            black_box(
                external_fory
                    .deserialize_with::<S>(black_box(&external_bytes))
                    .unwrap(),
            )
        })
    });
    group.finish();
}

pub fn run_external_type_benchmarks(criterion: &mut Criterion) {
    let (local, external) = build_pair(false);

    run_pair::<LocalUser, UserSerializer>(
        criterion,
        "external_user_root",
        &local,
        &external,
        local_user("Ada", 37),
        external_user("Ada", 37),
    );
    run_pair::<LocalDirectField, ExternalDirectField>(
        criterion,
        "external_direct_field",
        &local,
        &external,
        LocalDirectField {
            user: local_user("Ada", 37),
        },
        ExternalDirectField {
            user: external_user("Ada", 37),
        },
    );
    run_pair::<LocalSkippedField, UserWithStateSerializer>(
        criterion,
        "external_skipped_field",
        &local,
        &external,
        LocalSkippedField {
            name: "Ada".to_owned(),
            state: LocalId(42),
        },
        UserWithState {
            name: "Ada".to_owned(),
            state: ExternalId(42),
        },
    );

    let local_users = vec![local_user("Ada", 37), local_user("Grace", 28)];
    let external_users = vec![external_user("Ada", 37), external_user("Grace", 28)];
    run_pair::<LocalCompositeField, ExternalCompositeField>(
        criterion,
        "external_recursive_field",
        &local,
        &external,
        LocalCompositeField {
            users: local_users.clone(),
            by_key: HashMap::from([(local_key("people", 1), local_user("Ada", 37))]),
            tuple: ("team".to_owned(), local_users.clone()),
        },
        ExternalCompositeField {
            users: external_users.clone(),
            by_key: HashMap::from([(external_key("people", 1), external_user("Ada", 37))]),
            tuple: ("team".to_owned(), external_users.clone()),
        },
    );
    run_pair::<LocalId, ExternalIdSerializer>(
        criterion,
        "external_manual_leaf",
        &local,
        &external,
        LocalId(0x1020_3040_5060_7080),
        ExternalId(0x1020_3040_5060_7080),
    );
    run_pair::<LocalPackedUsersSerializer, PackedUsersSerializer>(
        criterion,
        "external_exact_container",
        &local,
        &external,
        local_users.clone(),
        external_users.clone(),
    );

    run_pair::<Option<LocalUser>, OptionSerializer<UserSerializer>>(
        criterion,
        "carrier_option_present",
        &local,
        &external,
        Some(local_user("Ada", 37)),
        Some(external_user("Ada", 37)),
    );
    run_pair::<Option<LocalUser>, OptionSerializer<UserSerializer>>(
        criterion,
        "carrier_option_absent",
        &local,
        &external,
        None,
        None,
    );
    run_pair::<Box<LocalUser>, BoxSerializer<UserSerializer>>(
        criterion,
        "carrier_box",
        &local,
        &external,
        Box::new(local_user("Ada", 37)),
        Box::new(external_user("Ada", 37)),
    );
    run_pair::<Rc<LocalUser>, RcSerializer<UserSerializer>>(
        criterion,
        "carrier_rc",
        &local,
        &external,
        Rc::new(local_user("Ada", 37)),
        Rc::new(external_user("Ada", 37)),
    );
    run_pair::<Arc<LocalUser>, ArcSerializer<UserSerializer>>(
        criterion,
        "carrier_arc",
        &local,
        &external,
        Arc::new(local_user("Ada", 37)),
        Arc::new(external_user("Ada", 37)),
    );

    let local_rc = Rc::new(local_user("Ada", 37));
    let external_rc = Rc::new(external_user("Ada", 37));
    run_pair::<RcWeak<LocalUser>, RcWeakSerializer<UserSerializer>>(
        criterion,
        "carrier_rc_weak",
        &local,
        &external,
        RcWeak::from(&local_rc),
        RcWeak::from(&external_rc),
    );
    let local_arc = Arc::new(local_user("Grace", 28));
    let external_arc = Arc::new(external_user("Grace", 28));
    run_pair::<ArcWeak<LocalUser>, ArcWeakSerializer<UserSerializer>>(
        criterion,
        "carrier_arc_weak",
        &local,
        &external,
        ArcWeak::from(&local_arc),
        ArcWeak::from(&external_arc),
    );
    run_pair::<RefCell<LocalUser>, RefCellSerializer<UserSerializer>>(
        criterion,
        "carrier_refcell",
        &local,
        &external,
        RefCell::new(local_user("Ada", 37)),
        RefCell::new(external_user("Ada", 37)),
    );
    run_pair::<Mutex<LocalUser>, MutexSerializer<UserSerializer>>(
        criterion,
        "carrier_mutex",
        &local,
        &external,
        Mutex::new(local_user("Ada", 37)),
        Mutex::new(external_user("Ada", 37)),
    );
    run_pair::<Vec<LocalUser>, VecSerializer<UserSerializer>>(
        criterion,
        "carrier_vec",
        &local,
        &external,
        local_users.clone(),
        external_users.clone(),
    );
    run_pair::<Vec<LocalUser>, VecSerializer<UserSerializer>>(
        criterion,
        "carrier_vec_empty",
        &local,
        &external,
        Vec::new(),
        Vec::new(),
    );
    run_pair::<VecDeque<LocalUser>, VecDequeSerializer<UserSerializer>>(
        criterion,
        "carrier_vecdeque",
        &local,
        &external,
        local_users.iter().cloned().collect(),
        external_users.iter().cloned().collect(),
    );
    run_pair::<LinkedList<LocalUser>, LinkedListSerializer<UserSerializer>>(
        criterion,
        "carrier_linkedlist",
        &local,
        &external,
        local_users.iter().cloned().collect(),
        external_users.iter().cloned().collect(),
    );
    run_pair::<HashSet<LocalUser>, HashSetSerializer<UserSerializer>>(
        criterion,
        "carrier_hashset",
        &local,
        &external,
        HashSet::from([local_user("Ada", 37)]),
        HashSet::from([external_user("Ada", 37)]),
    );
    run_pair::<BTreeSet<LocalUser>, BTreeSetSerializer<UserSerializer>>(
        criterion,
        "carrier_btreeset",
        &local,
        &external,
        local_users.iter().cloned().collect(),
        external_users.iter().cloned().collect(),
    );
    run_pair::<BinaryHeap<LocalUser>, BinaryHeapSerializer<UserSerializer>>(
        criterion,
        "carrier_binaryheap",
        &local,
        &external,
        local_users.iter().cloned().collect(),
        external_users.iter().cloned().collect(),
    );
    run_pair::<[LocalUser; 2], ArraySerializer<UserSerializer, 2>>(
        criterion,
        "carrier_array",
        &local,
        &external,
        [local_user("Ada", 37), local_user("Grace", 28)],
        [external_user("Ada", 37), external_user("Grace", 28)],
    );
    run_pair::<Vec<LocalId>, VecSerializer<ExternalIdSerializer>>(
        criterion,
        "carrier_manual_vec",
        &local,
        &external,
        vec![LocalId(1), LocalId(2)],
        vec![ExternalId(1), ExternalId(2)],
    );

    run_pair::<HashMap<LocalKey, String>, HashMapSerializer<KeySerializer, String>>(
        criterion,
        "carrier_map_key",
        &local,
        &external,
        HashMap::from([(local_key("people", 1), "Ada".to_owned())]),
        HashMap::from([(external_key("people", 1), "Ada".to_owned())]),
    );
    run_pair::<HashMap<String, LocalUser>, HashMapSerializer<String, UserSerializer>>(
        criterion,
        "carrier_map_value",
        &local,
        &external,
        HashMap::from([("lead".to_owned(), local_user("Ada", 37))]),
        HashMap::from([("lead".to_owned(), external_user("Ada", 37))]),
    );
    run_pair::<HashMap<LocalKey, LocalUser>, HashMapSerializer<KeySerializer, UserSerializer>>(
        criterion,
        "carrier_map_both",
        &local,
        &external,
        HashMap::from([(local_key("people", 1), local_user("Ada", 37))]),
        HashMap::from([(external_key("people", 1), external_user("Ada", 37))]),
    );
    run_pair::<
        HashMap<LocalKey, Vec<LocalUser>>,
        HashMapSerializer<KeySerializer, VecSerializer<UserSerializer>>,
    >(
        criterion,
        "carrier_map_nested",
        &local,
        &external,
        HashMap::from([(local_key("team", 1), local_users.clone())]),
        HashMap::from([(external_key("team", 1), external_users.clone())]),
    );
    run_pair::<BTreeMap<LocalKey, LocalUser>, BTreeMapSerializer<KeySerializer, UserSerializer>>(
        criterion,
        "carrier_btreemap",
        &local,
        &external,
        BTreeMap::from([(local_key("people", 1), local_user("Ada", 37))]),
        BTreeMap::from([(external_key("people", 1), external_user("Ada", 37))]),
    );

    run_pair::<(LocalUser,), Tuple1Serializer<UserSerializer>>(
        criterion,
        "carrier_tuple1",
        &local,
        &external,
        (local_user("Ada", 37),),
        (external_user("Ada", 37),),
    );
    run_pair::<(String, LocalUser), Tuple2Serializer<String, UserSerializer>>(
        criterion,
        "carrier_tuple2",
        &local,
        &external,
        ("lead".to_owned(), local_user("Ada", 37)),
        ("lead".to_owned(), external_user("Ada", 37)),
    );
    run_pair::<
        (String, Vec<LocalUser>, HashMap<LocalKey, LocalUser>),
        Tuple3Serializer<
            String,
            VecSerializer<UserSerializer>,
            HashMapSerializer<KeySerializer, UserSerializer>,
        >,
    >(
        criterion,
        "carrier_tuple_composed",
        &local,
        &external,
        (
            "team".to_owned(),
            local_users.clone(),
            HashMap::from([(local_key("people", 1), local_user("Ada", 37))]),
        ),
        (
            "team".to_owned(),
            external_users.clone(),
            HashMap::from([(external_key("people", 1), external_user("Ada", 37))]),
        ),
    );
    run_pair::<LocalTuple22, ExternalTuple22Serializer>(
        criterion,
        "carrier_tuple22",
        &local,
        &external,
        (
            1,
            2,
            3,
            4,
            5,
            6,
            7,
            8,
            9,
            10,
            11,
            12,
            13,
            14,
            15,
            16,
            17,
            18,
            19,
            20,
            21,
            local_user("Ada", 37),
        ),
        (
            1,
            2,
            3,
            4,
            5,
            6,
            7,
            8,
            9,
            10,
            11,
            12,
            13,
            14,
            15,
            16,
            17,
            18,
            19,
            20,
            21,
            external_user("Ada", 37),
        ),
    );

    run_pair::<Vec<i32>, VecSerializer<i32>>(
        criterion,
        "carrier_vec_i32",
        &local,
        &external,
        (0..64).collect(),
        (0..64).collect(),
    );
    run_pair::<Vec<u8>, VecSerializer<u8>>(
        criterion,
        "carrier_vec_u8",
        &local,
        &external,
        (0..64).collect(),
        (0..64).collect(),
    );
    run_pair::<Vec<Vec<i32>>, VecSerializer<VecSerializer<i32>>>(
        criterion,
        "carrier_vec_nested_i32",
        &local,
        &external,
        vec![(0..32).collect(), (32..64).collect()],
        vec![(0..32).collect(), (32..64).collect()],
    );

    let local_command = LocalCommand::Create {
        id: 42,
        label: "deploy".to_owned(),
    };
    let external_command = Command::Create {
        id: 42,
        label: "deploy".to_owned(),
    };
    run_pair::<LocalCommand, CommandSerializer>(
        criterion,
        "external_command_native",
        &local,
        &external,
        local_command.clone(),
        external_command.clone(),
    );
    run_pair::<LocalCommandField, ExternalCommandField>(
        criterion,
        "external_command_field",
        &local,
        &external,
        LocalCommandField {
            commands: vec![local_command.clone(), LocalCommand::Move(3, 5)],
            by_name: HashMap::from([("next".to_owned(), local_command.clone())]),
            tuple: ("next".to_owned(), local_command),
        },
        ExternalCommandField {
            commands: vec![external_command.clone(), Command::Move(3, 5)],
            by_name: HashMap::from([("next".to_owned(), external_command.clone())]),
            tuple: ("next".to_owned(), external_command),
        },
    );

    let (local_compatible, external_compatible) = build_pair(true);
    run_pair::<LocalCommand, CommandSerializer>(
        criterion,
        "external_command_compatible",
        &local_compatible,
        &external_compatible,
        LocalCommand::Create {
            id: 42,
            label: "deploy".to_owned(),
        },
        Command::Create {
            id: 42,
            label: "deploy".to_owned(),
        },
    );
    run_pair::<LocalCommandField, ExternalCommandField>(
        criterion,
        "external_command_field_compatible",
        &local_compatible,
        &external_compatible,
        LocalCommandField {
            commands: vec![LocalCommand::Idle, LocalCommand::Move(3, 5)],
            by_name: HashMap::from([("next".to_owned(), LocalCommand::Move(3, 5))]),
            tuple: ("next".to_owned(), LocalCommand::Move(3, 5)),
        },
        ExternalCommandField {
            commands: vec![Command::Idle, Command::Move(3, 5)],
            by_name: HashMap::from([("next".to_owned(), Command::Move(3, 5))]),
            tuple: ("next".to_owned(), Command::Move(3, 5)),
        },
    );

    run_pair::<Box<dyn Any>, Box<dyn Any>>(
        criterion,
        "dynamic_any_box",
        &local,
        &external,
        Box::new(local_user("Ada", 37)),
        Box::new(external_user("Ada", 37)),
    );
    run_pair::<Rc<dyn Any>, Rc<dyn Any>>(
        criterion,
        "dynamic_any_rc",
        &local,
        &external,
        Rc::new(local_user("Ada", 37)),
        Rc::new(external_user("Ada", 37)),
    );
    run_pair::<Arc<dyn Any + Send + Sync>, Arc<dyn Any + Send + Sync>>(
        criterion,
        "dynamic_any_arc",
        &local,
        &external,
        Arc::new(local_user("Ada", 37)),
        Arc::new(external_user("Ada", 37)),
    );
    run_pair::<Box<dyn BenchEntity>, Box<dyn BenchEntity>>(
        criterion,
        "dynamic_trait_box",
        &local,
        &external,
        Box::new(local_user("Ada", 37)),
        Box::new(external_user("Ada", 37)),
    );
    run_pair::<BenchEntityRcSerializer, BenchEntityRcSerializer>(
        criterion,
        "dynamic_trait_rc",
        &local,
        &external,
        Rc::new(local_user("Ada", 37)),
        Rc::new(external_user("Ada", 37)),
    );
    run_pair::<BenchEntityArcSerializer, BenchEntityArcSerializer>(
        criterion,
        "dynamic_trait_arc",
        &local,
        &external,
        Arc::new(local_user("Ada", 37)),
        Arc::new(external_user("Ada", 37)),
    );
}
