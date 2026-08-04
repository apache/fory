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

use fory_core::error::Error;
use fory_core::fory::Fory;
use fory_core::serializer::{
    ArcSerializer, ArcWeakSerializer, ArraySerializer, BTreeMapSerializer, BTreeSetSerializer,
    BinaryHeapSerializer, BoxSerializer, HashMapSerializer, HashSetSerializer,
    LinkedListSerializer, MutexSerializer, OptionSerializer, RcSerializer, RcWeakSerializer,
    RefCellSerializer, Serializer, Tuple1Serializer, Tuple22Serializer, Tuple2Serializer,
    Tuple3Serializer, VecDequeSerializer, VecSerializer,
};
use fory_core::{
    register_trait_type, ArcWeak, BFloat16, Float16, ForyObject, RcWeak, ReadContext, Reader,
    TypeId, UnknownCase, WriteContext,
};
use fory_derive::{ForyEnum, ForyStruct, ForyUnion};
use fory_external_model::{
    Command, ExternalId, GenericRecord, Key, Marker, Point, Status, User, UserV1, UserV2,
    UserWithState, Value, Workflow,
};
use std::any::Any;
use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, BinaryHeap, HashMap, HashSet, LinkedList, VecDeque};
use std::fmt::Debug;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

#[derive(ForyStruct)]
#[fory(target = User)]
struct UserSerializer {
    name: String,
    age: u32,
}

#[derive(ForyStruct)]
#[fory(target = Key)]
struct KeySerializer {
    namespace: String,
    id: u64,
}

#[derive(ForyStruct)]
#[fory(target = Point)]
struct PointSerializer(i32, i32);

#[derive(ForyStruct)]
#[fory(target = Marker)]
struct MarkerSerializer;

#[derive(ForyStruct)]
#[allow(clippy::box_collection)]
struct WrappedMapsFixed(
    #[fory(map(key(encoding = fixed), value(encoding = fixed)))] Box<HashMap<i32, i32>>,
    #[fory(map(key(encoding = fixed), value(encoding = fixed)))] Option<HashMap<i32, i32>>,
    // Tuple-struct fields preserve source order, so each weak value publishes
    // the body before its following strong keeper references the same owner.
    #[fory(map(key(encoding = fixed), value(encoding = fixed)))] RcWeak<HashMap<i32, i32>>,
    #[fory(map(key(encoding = fixed), value(encoding = fixed)))] Rc<HashMap<i32, i32>>,
    #[fory(map(key(encoding = fixed), value(encoding = fixed)))] ArcWeak<HashMap<i32, i32>>,
    #[fory(map(key(encoding = fixed), value(encoding = fixed)))] Arc<HashMap<i32, i32>>,
    #[fory(map(key(encoding = fixed), value(encoding = fixed)))] RefCell<HashMap<i32, i32>>,
    #[fory(map(key(encoding = fixed), value(encoding = fixed)))] Mutex<HashMap<i32, i32>>,
);

#[derive(ForyStruct)]
#[allow(clippy::box_collection)]
struct WrappedMapsVarint(
    #[fory(map(key(encoding = varint), value(encoding = varint)))] Box<HashMap<i32, i32>>,
    #[fory(map(key(encoding = varint), value(encoding = varint)))] Option<HashMap<i32, i32>>,
    #[fory(map(key(encoding = varint), value(encoding = varint)))] RcWeak<HashMap<i32, i32>>,
    #[fory(map(key(encoding = varint), value(encoding = varint)))] Rc<HashMap<i32, i32>>,
    #[fory(map(key(encoding = varint), value(encoding = varint)))] ArcWeak<HashMap<i32, i32>>,
    #[fory(map(key(encoding = varint), value(encoding = varint)))] Arc<HashMap<i32, i32>>,
    #[fory(map(key(encoding = varint), value(encoding = varint)))] RefCell<HashMap<i32, i32>>,
    #[fory(map(key(encoding = varint), value(encoding = varint)))] Mutex<HashMap<i32, i32>>,
);

#[derive(ForyEnum)]
#[fory(target = Status)]
enum StatusSerializer {
    Active,
    Inactive,
}

#[derive(ForyUnion)]
#[fory(target = Value<UnknownCase>)]
enum ValueSerializer {
    #[fory(default)]
    Null,
    Text(String),
    Named {
        text: String,
    },
    Count(i64),
    #[fory(unknown)]
    Unknown(UnknownCase),
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

#[derive(ForyUnion)]
enum LocalCommandWire {
    #[fory(default)]
    Idle,
    Create {
        id: u128,
        label: String,
    },
    Move(i32, i32),
}

#[derive(ForyStruct)]
#[fory(target = GenericRecord<T>)]
struct GenericRecordSerializer<T: Serializer<Target = T> + 'static> {
    value: T,
}

#[derive(ForyStruct)]
#[fory(target = UserV1)]
struct UserV1Serializer {
    name: String,
}

#[derive(ForyStruct)]
#[fory(target = UserV2)]
struct UserV2Serializer {
    name: String,
    age: u32,
}

#[derive(ForyStruct)]
struct CompatibleSharedV1(
    #[fory(with = RcWeakSerializer<UserV1Serializer>)] RcWeak<UserV1>,
    #[fory(with = RcSerializer<UserV1Serializer>)] Rc<UserV1>,
    #[fory(with = ArcWeakSerializer<UserV1Serializer>)] ArcWeak<UserV1>,
    #[fory(with = ArcSerializer<UserV1Serializer>)] Arc<UserV1>,
);

#[derive(ForyStruct)]
struct CompatibleSharedV2(
    #[fory(with = RcWeakSerializer<UserV2Serializer>)] RcWeak<UserV2>,
    #[fory(with = RcSerializer<UserV2Serializer>)] Rc<UserV2>,
    #[fory(with = ArcWeakSerializer<UserV2Serializer>)] ArcWeak<UserV2>,
    #[fory(with = ArcSerializer<UserV2Serializer>)] Arc<UserV2>,
);

#[derive(ForyStruct)]
struct CompatibleCarriersV1 {
    #[fory(with = VecSerializer<UserV1Serializer>)]
    direct_list: Vec<UserV1>,
    #[fory(list(element(with = UserV1Serializer)))]
    recursive_list: Vec<UserV1>,
    #[fory(with = HashMapSerializer<String, UserV1Serializer>)]
    direct_map: HashMap<String, UserV1>,
    #[fory(map(value(with = UserV1Serializer)))]
    recursive_map: HashMap<String, UserV1>,
    #[fory(with = Tuple2Serializer<String, UserV1Serializer>)]
    direct_tuple: (String, UserV1),
    #[fory(tuple(element(index = 1, with = UserV1Serializer)))]
    recursive_tuple: (String, UserV1),
}

#[derive(ForyStruct)]
struct CompatibleCarriersV2 {
    #[fory(with = VecSerializer<UserV2Serializer>)]
    direct_list: Vec<UserV2>,
    #[fory(list(element(with = UserV2Serializer)))]
    recursive_list: Vec<UserV2>,
    #[fory(with = HashMapSerializer<String, UserV2Serializer>)]
    direct_map: HashMap<String, UserV2>,
    #[fory(map(value(with = UserV2Serializer)))]
    recursive_map: HashMap<String, UserV2>,
    #[fory(with = Tuple2Serializer<String, UserV2Serializer>)]
    direct_tuple: (String, UserV2),
    #[fory(tuple(element(index = 1, with = UserV2Serializer)))]
    recursive_tuple: (String, UserV2),
}

struct ExternalIdSerializer;

impl Serializer for ExternalIdSerializer {
    type Target = ExternalId;

    fn write_data(value: &ExternalId, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u128(value.0);
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<ExternalId, Error> {
        Ok(ExternalId(context.reader.read_u128()?))
    }

    fn default_value(_context: &mut ReadContext) -> Result<ExternalId, Error> {
        Ok(ExternalId(0))
    }

    fn read_arc_any(context: &mut ReadContext) -> Result<Arc<dyn Any + Send + Sync>, Error> {
        Ok(Arc::new(Self::read_data(context)?))
    }
}

struct BoxExternalIdSerializer;

impl Serializer for BoxExternalIdSerializer {
    type Target = Box<ExternalId>;

    fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
        ExternalIdSerializer::write_data(value, context)
    }

    fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
        context.reserve_graph_memory(std::mem::size_of::<ExternalId>())?;
        Ok(Box::new(ExternalIdSerializer::read_data(context)?))
    }

    fn default_value(context: &mut ReadContext) -> Result<Self::Target, Error> {
        context.reserve_graph_memory(std::mem::size_of::<ExternalId>())?;
        Ok(Box::new(ExternalIdSerializer::default_value(context)?))
    }
}

#[derive(ForyStruct)]
#[fory(target = UserWithState)]
struct UserWithStateSerializer {
    name: String,
    #[fory(skip, with = ExternalIdSerializer)]
    state: ExternalId,
}

struct FailingExternalIdSerializer;

impl Serializer for FailingExternalIdSerializer {
    type Target = ExternalId;

    fn write_data(value: &ExternalId, context: &mut WriteContext) -> Result<(), Error> {
        ExternalIdSerializer::write_data(value, context)
    }

    fn read_data(context: &mut ReadContext) -> Result<ExternalId, Error> {
        ExternalIdSerializer::read_data(context)
    }

    fn default_value(_context: &mut ReadContext) -> Result<ExternalId, Error> {
        Err(Error::type_error("external id default rejected"))
    }
}

#[derive(ForyStruct)]
#[fory(target = UserWithState)]
struct UserWithFailingStateSerializer {
    name: String,
    #[fory(skip, with = FailingExternalIdSerializer)]
    state: ExternalId,
}

#[derive(ForyUnion)]
#[fory(target = Workflow)]
enum WorkflowSerializer {
    #[fory(default)]
    Idle,
    Assign {
        #[fory(with = ExternalIdSerializer)]
        id: ExternalId,
        #[fory(with = UserSerializer)]
        user: User,
    },
    Batch(
        #[fory(list(element(with = UserSerializer)))] Vec<User>,
        #[fory(map(
            key(with = KeySerializer),
            value(with = UserSerializer)
        ))]
        HashMap<Key, User>,
        #[fory(tuple(element(index = 1, with = UserSerializer)))] (String, User),
    ),
}

struct PackedUsersSerializer;

impl Serializer for PackedUsersSerializer {
    type Target = Vec<User>;

    fn write_data(value: &Vec<User>, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_var_u32(value.len() as u32);
        for user in value {
            UserSerializer::write_data(user, context)?;
        }
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<Vec<User>, Error> {
        let len = context.reader.read_var_u32()? as usize;
        if context.reader.slice_after_cursor().len() < len {
            return Err(Error::invalid_data(
                "packed user count exceeds the readable body",
            ));
        }
        let bytes = len
            .checked_mul(std::mem::size_of::<User>())
            .ok_or_else(|| Error::invalid_data("user storage estimate overflows"))?;
        context.reserve_graph_memory(bytes)?;
        let mut users = Vec::with_capacity(len);
        for _ in 0..len {
            users.push(UserSerializer::read_data(context)?);
        }
        Ok(users)
    }

    fn default_value(_context: &mut ReadContext) -> Result<Vec<User>, Error> {
        Ok(Vec::new())
    }
}

struct PackedEntrySerializer;

impl Serializer for PackedEntrySerializer {
    type Target = (String, User);

    fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
        String::write_data(&value.0, context)?;
        UserSerializer::write_data(&value.1, context)
    }

    fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
        Ok((
            String::read_data(context)?,
            UserSerializer::read_data(context)?,
        ))
    }

    fn default_value(context: &mut ReadContext) -> Result<Self::Target, Error> {
        Ok((
            String::default_value(context)?,
            UserSerializer::default_value(context)?,
        ))
    }
}

struct I32Serializer;

impl Serializer for I32Serializer {
    type Target = i32;

    fn write_data(value: &i32, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_i32(*value);
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<i32, Error> {
        context.reader.read_i32()
    }

    fn default_value(_context: &mut ReadContext) -> Result<i32, Error> {
        Ok(0)
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
struct SilentValue(u64);

struct SilentValueSerializer;

impl Serializer for SilentValueSerializer {
    type Target = SilentValue;

    fn write_data(_value: &SilentValue, _context: &mut WriteContext) -> Result<(), Error> {
        Ok(())
    }

    fn read_data(_context: &mut ReadContext) -> Result<SilentValue, Error> {
        Ok(SilentValue(0))
    }

    fn default_value(_context: &mut ReadContext) -> Result<SilentValue, Error> {
        Ok(SilentValue(0))
    }
}

const _: [(); 1] = [(); UserSerializer::READ_DATA_ALWAYS_ADVANCES as usize];
const _: [(); 0] = [(); MarkerSerializer::READ_DATA_ALWAYS_ADVANCES as usize];
const _: [(); 0] = [(); SilentValueSerializer::READ_DATA_ALWAYS_ADVANCES as usize];

#[derive(ForyStruct, Clone, Debug, PartialEq)]
struct LocalUser {
    name: String,
    age: u32,
}

#[derive(ForyStruct, Debug, PartialEq)]
struct DirectUserList {
    #[fory(with = VecSerializer<UserSerializer>)]
    users: Vec<User>,
}

#[derive(ForyStruct, Debug, PartialEq)]
struct RecursiveUserList {
    #[fory(list(element(with = UserSerializer)))]
    users: Vec<User>,
}

#[derive(ForyStruct)]
struct DirectCarrierFields {
    #[fory(with = VecDequeSerializer<UserSerializer>)]
    deque: VecDeque<User>,
    #[fory(with = LinkedListSerializer<UserSerializer>)]
    linked: LinkedList<User>,
    #[fory(with = HashSetSerializer<UserSerializer>)]
    hash_set: HashSet<User>,
    #[fory(with = BTreeSetSerializer<UserSerializer>)]
    tree_set: BTreeSet<User>,
    #[fory(with = BinaryHeapSerializer<UserSerializer>)]
    heap: BinaryHeap<User>,
    #[fory(with = ArraySerializer<UserSerializer, 2>)]
    array: [User; 2],
    #[fory(with = HashMapSerializer<KeySerializer, UserSerializer>)]
    hash_map: HashMap<Key, User>,
    #[fory(with = BTreeMapSerializer<KeySerializer, UserSerializer>)]
    tree_map: BTreeMap<Key, User>,
    #[fory(with = Tuple1Serializer<UserSerializer>)]
    tuple1: (User,),
    #[fory(with = Tuple2Serializer<String, UserSerializer>)]
    tuple2: (String, User),
    #[fory(
        with = HashMapSerializer<
            KeySerializer,
            VecSerializer<Tuple2Serializer<String, UserSerializer>>,
        >
    )]
    nested: HashMap<Key, Vec<(String, User)>>,
    #[fory(with = VecSerializer<Box<dyn Animal>>)]
    animals: Vec<Box<dyn Animal>>,
}

type UserListSerializer = VecSerializer<UserSerializer>;

#[derive(ForyStruct)]
struct AliasedUserList {
    #[fory(with = UserListSerializer)]
    users: Vec<User>,
}

type SkippedUserListSerializer = VecSerializer<UserSerializer>;

#[derive(ForyStruct, Debug, PartialEq)]
struct SkippedCarrierDefaults {
    #[fory(skip, with = SkippedUserListSerializer)]
    users: Vec<User>,
    #[fory(skip, with = HashMapSerializer<KeySerializer, UserSerializer>)]
    by_key: HashMap<Key, User>,
    #[fory(skip, with = ArraySerializer<UserSerializer, 2>)]
    array: [User; 2],
    #[fory(skip, with = Tuple2Serializer<String, UserSerializer>)]
    tuple: (String, User),
}

#[derive(ForyStruct)]
struct ExternalFields {
    #[fory(with = UserSerializer)]
    direct: User,
    #[fory(with = OptionSerializer<UserSerializer>)]
    optional: Option<User>,
    #[fory(with = BoxSerializer<UserSerializer>)]
    boxed: Box<User>,
    #[fory(with = RcSerializer<UserSerializer>)]
    rc: Rc<User>,
    #[fory(with = RcWeakSerializer<UserSerializer>)]
    rc_weak: RcWeak<User>,
    #[fory(with = ArcSerializer<UserSerializer>)]
    arc: Arc<User>,
    #[fory(with = ArcWeakSerializer<UserSerializer>)]
    arc_weak: ArcWeak<User>,
    #[fory(with = RefCellSerializer<UserSerializer>)]
    cell: RefCell<User>,
    #[fory(with = MutexSerializer<UserSerializer>)]
    mutex: Mutex<User>,
    #[fory(with = VecSerializer<UserSerializer>)]
    direct_vec: Vec<User>,
    #[fory(list(element(with = UserSerializer)))]
    vec: Vec<User>,
    #[fory(with = PackedUsersSerializer)]
    packed: Vec<User>,
    #[fory(list(element(with = UserSerializer)))]
    deque: VecDeque<User>,
    #[fory(list(element(with = UserSerializer)))]
    linked: LinkedList<User>,
    #[fory(list(element(with = UserSerializer)))]
    hash_set: HashSet<User>,
    #[fory(list(element(with = UserSerializer)))]
    tree_set: BTreeSet<User>,
    #[fory(list(element(with = UserSerializer)))]
    heap: BinaryHeap<User>,
    #[fory(list(element(with = UserSerializer)))]
    array: [User; 2],
    #[fory(map(
        key(with = KeySerializer),
        value(with = UserSerializer)
    ))]
    hash_map: HashMap<Key, User>,
    #[fory(map(
        key(with = KeySerializer),
        value(with = UserSerializer)
    ))]
    tree_map: BTreeMap<Key, User>,
    #[fory(tuple(
        element(index = 1, with = UserSerializer),
        element(index = 2, list(element(with = UserSerializer)))
    ))]
    tuple: (String, User, Vec<User>),
    #[fory(with = PackedEntrySerializer)]
    packed_entry: (String, User),
}

#[derive(ForyStruct, Debug, PartialEq)]
struct CommandFields {
    #[fory(with = CommandSerializer)]
    direct: Command,
    #[fory(list(element(with = CommandSerializer)))]
    list: Vec<Command>,
    #[fory(map(value(with = CommandSerializer)))]
    map: HashMap<String, Command>,
    #[fory(tuple(element(index = 1, with = CommandSerializer)))]
    tuple: (String, Command),
}

trait Animal: ForyObject {
    fn name(&self) -> &str;
}

impl Animal for User {
    fn name(&self) -> &str {
        &self.name
    }
}

register_trait_type!(Animal, User);

trait SharedAnimal: ForyObject + Send + Sync {
    fn name(&self) -> &str;
}

impl SharedAnimal for User {
    fn name(&self) -> &str {
        &self.name
    }
}

register_trait_type!(sync SharedAnimal, User);

trait Operation: ForyObject {
    fn kind(&self) -> &'static str;
}

impl Operation for Command {
    fn kind(&self) -> &'static str {
        match self {
            Command::Idle => "idle",
            Command::Create { .. } => "create",
            Command::Move(_, _) => "move",
        }
    }
}

register_trait_type!(Operation, Command);

trait Identifier: ForyObject + Send + Sync {
    fn value(&self) -> u128;
}

impl Identifier for ExternalId {
    fn value(&self) -> u128 {
        self.0
    }
}

register_trait_type!(sync Identifier, ExternalId);

fn user(name: &str, age: u32) -> User {
    User {
        name: name.to_string(),
        age,
    }
}

fn key(namespace: &str, id: u64) -> Key {
    Key {
        namespace: namespace.to_string(),
        id,
    }
}

fn configured_fory() -> Fory {
    configured_fory_mode(false)
}

fn configured_fory_mode(compatible: bool) -> Fory {
    let mut fory = Fory::builder()
        .xlang(false)
        .compatible(compatible)
        .track_ref(true)
        .build();
    register_types(&mut fory);
    fory
}

fn register_types(fory: &mut Fory) {
    fory.register::<UserSerializer>(100).unwrap();
    fory.register::<KeySerializer>(101).unwrap();
    fory.register::<PointSerializer>(102).unwrap();
    fory.register::<MarkerSerializer>(103).unwrap();
    fory.register::<StatusSerializer>(104).unwrap();
    fory.register_union::<ValueSerializer>(105).unwrap();
    fory.register::<CommandSerializer>(106).unwrap();
    fory.register_serializer::<ExternalIdSerializer>(107)
        .unwrap();
    fory.register::<UserWithStateSerializer>(108).unwrap();
    fory.register::<WorkflowSerializer>(109).unwrap();
    fory.register::<ExternalFields>(110).unwrap();
    fory.register::<CommandFields>(111).unwrap();
    fory.register_serializer::<PackedUsersSerializer>(112)
        .unwrap();
    fory.register_serializer::<PackedEntrySerializer>(113)
        .unwrap();
}

fn roundtrip<S>(fory: &Fory, value: &S::Target)
where
    S: Serializer,
    S::Target: Debug + PartialEq,
{
    let bytes = fory.serialize_with::<S>(value).unwrap();
    let decoded = fory.deserialize_with::<S>(&bytes).unwrap();
    assert_eq!(&decoded, value);

    let mut buffer = vec![0xaa, 0xbb];
    let written = fory.serialize_to_with::<S>(&mut buffer, value).unwrap();
    assert_eq!(written, buffer.len() - 2);
    let mut reader = Reader::new(&buffer[2..]);
    let decoded = fory.deserialize_from_with::<S>(&mut reader).unwrap();
    assert_eq!(&decoded, value);
}

fn roundtrip_tuple22<S>(fory: &Fory, value: &S::Target)
where
    S: Serializer<
        Target = (
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
            User,
        ),
    >,
{
    let bytes = fory.serialize_with::<S>(value).unwrap();
    let decoded = fory.deserialize_with::<S>(&bytes).unwrap();
    assert_eq!(decoded.0, value.0);
    assert_eq!(decoded.1, value.1);
    assert_eq!(decoded.2, value.2);
    assert_eq!(decoded.3, value.3);
    assert_eq!(decoded.4, value.4);
    assert_eq!(decoded.5, value.5);
    assert_eq!(decoded.6, value.6);
    assert_eq!(decoded.7, value.7);
    assert_eq!(decoded.8, value.8);
    assert_eq!(decoded.9, value.9);
    assert_eq!(decoded.10, value.10);
    assert_eq!(decoded.11, value.11);
    assert_eq!(decoded.12, value.12);
    assert_eq!(decoded.13, value.13);
    assert_eq!(decoded.14, value.14);
    assert_eq!(decoded.15, value.15);
    assert_eq!(decoded.16, value.16);
    assert_eq!(decoded.17, value.17);
    assert_eq!(decoded.18, value.18);
    assert_eq!(decoded.19, value.19);
    assert_eq!(decoded.20, value.20);
    assert_eq!(decoded.21, value.21);

    let mut buffer = vec![0xaa, 0xbb];
    let written = fory.serialize_to_with::<S>(&mut buffer, value).unwrap();
    assert_eq!(written, buffer.len() - 2);
    let mut reader = Reader::new(&buffer[2..]);
    let decoded = fory.deserialize_from_with::<S>(&mut reader).unwrap();
    assert_eq!(decoded.0, value.0);
    assert_eq!(decoded.1, value.1);
    assert_eq!(decoded.2, value.2);
    assert_eq!(decoded.3, value.3);
    assert_eq!(decoded.4, value.4);
    assert_eq!(decoded.5, value.5);
    assert_eq!(decoded.6, value.6);
    assert_eq!(decoded.7, value.7);
    assert_eq!(decoded.8, value.8);
    assert_eq!(decoded.9, value.9);
    assert_eq!(decoded.10, value.10);
    assert_eq!(decoded.11, value.11);
    assert_eq!(decoded.12, value.12);
    assert_eq!(decoded.13, value.13);
    assert_eq!(decoded.14, value.14);
    assert_eq!(decoded.15, value.15);
    assert_eq!(decoded.16, value.16);
    assert_eq!(decoded.17, value.17);
    assert_eq!(decoded.18, value.18);
    assert_eq!(decoded.19, value.19);
    assert_eq!(decoded.20, value.20);
    assert_eq!(decoded.21, value.21);
}

#[allow(clippy::assertions_on_constants)]
const _: () = {
    type Optional = OptionSerializer<ExternalIdSerializer>;
    type BoxedOptional = BoxSerializer<Optional>;
    type CellOptional = RefCellSerializer<Optional>;
    type MutexOptional = MutexSerializer<Optional>;

    assert!(Optional::IS_OPTIONAL);
    assert!(Optional::IS_WRAPPER);
    assert!(!Optional::REQUIRES_SCOPED_ACCESS);
    assert!(BoxedOptional::IS_OPTIONAL);
    assert!(BoxedOptional::IS_WRAPPER);
    assert!(!BoxedOptional::REQUIRES_SCOPED_ACCESS);
    assert!(CellOptional::IS_OPTIONAL);
    assert!(CellOptional::IS_WRAPPER);
    assert!(CellOptional::REQUIRES_SCOPED_ACCESS);
    assert!(MutexOptional::IS_OPTIONAL);
    assert!(MutexOptional::IS_WRAPPER);
    assert!(MutexOptional::REQUIRES_SCOPED_ACCESS);

    assert!(!RcSerializer::<Optional>::IS_OPTIONAL);
    assert!(RcSerializer::<Optional>::IS_SHARED_REF);
    assert!(RcSerializer::<Optional>::IS_WRAPPER);
    assert!(!RcSerializer::<Optional>::REQUIRES_SCOPED_ACCESS);
    assert!(!ArcSerializer::<Optional>::IS_OPTIONAL);
    assert!(ArcSerializer::<Optional>::IS_SHARED_REF);
    assert!(ArcSerializer::<Optional>::IS_WRAPPER);
    assert!(!ArcSerializer::<Optional>::REQUIRES_SCOPED_ACCESS);
    assert!(RcWeakSerializer::<ExternalIdSerializer>::IS_SHARED_REF);
    assert!(RcWeakSerializer::<ExternalIdSerializer>::IS_WRAPPER);
    assert!(RcWeakSerializer::<ExternalIdSerializer>::REQUIRES_SCOPED_ACCESS);
    assert!(ArcWeakSerializer::<ExternalIdSerializer>::IS_SHARED_REF);
    assert!(ArcWeakSerializer::<ExternalIdSerializer>::IS_WRAPPER);
    assert!(ArcWeakSerializer::<ExternalIdSerializer>::REQUIRES_SCOPED_ACCESS);

    assert!(!VecSerializer::<ExternalIdSerializer>::IS_WRAPPER);
    assert!(!ArraySerializer::<ExternalIdSerializer, 2>::IS_WRAPPER);
    assert!(!HashMapSerializer::<String, ExternalIdSerializer>::IS_WRAPPER);
    assert!(!Tuple2Serializer::<String, ExternalIdSerializer>::IS_WRAPPER);
    assert!(!BoxExternalIdSerializer::IS_WRAPPER);

    assert!(<Box<dyn Any> as Serializer>::IS_POLYMORPHIC);
    assert!(<Box<dyn Any> as Serializer>::IS_WRAPPER);
    assert!(!<Box<dyn Any> as Serializer>::REQUIRES_SCOPED_ACCESS);
    assert!(AnimalRcSerializer::IS_POLYMORPHIC);
    assert!(AnimalRcSerializer::IS_SHARED_REF);
    assert!(AnimalRcSerializer::IS_WRAPPER);
    assert!(!AnimalRcSerializer::REQUIRES_SCOPED_ACCESS);
    assert!(SharedAnimalArcSerializer::IS_POLYMORPHIC);
    assert!(SharedAnimalArcSerializer::IS_SHARED_REF);
    assert!(SharedAnimalArcSerializer::IS_WRAPPER);
    assert!(!SharedAnimalArcSerializer::REQUIRES_SCOPED_ACCESS);
};

#[test]
fn structural_shapes() {
    let fory = configured_fory();

    roundtrip::<UserSerializer>(&fory, &user("Ada", 37));
    roundtrip::<PointSerializer>(&fory, &Point(3, 5));
    roundtrip::<MarkerSerializer>(&fory, &Marker);
    roundtrip::<StatusSerializer>(&fory, &Status::Inactive);
    roundtrip::<ValueSerializer>(&fory, &Value::Text("value".to_string()));
    roundtrip::<ValueSerializer>(
        &fory,
        &Value::Named {
            text: "named".to_string(),
        },
    );

    for command in [
        Command::Idle,
        Command::Create {
            id: 7,
            label: "build".to_string(),
        },
        Command::Move(3, 5),
    ] {
        roundtrip::<CommandSerializer>(&fory, &command);
    }

    for workflow in [
        Workflow::Idle,
        Workflow::Assign {
            id: ExternalId(9),
            user: user("Ada", 37),
        },
        Workflow::Batch(
            vec![user("Ada", 37)],
            HashMap::from([(key("people", 1), user("Grace", 28))]),
            ("lead".to_string(), user("Linus", 33)),
        ),
    ] {
        roundtrip::<WorkflowSerializer>(&fory, &workflow);
    }

    let mut generic = Fory::builder().xlang(false).compatible(false).build();
    generic
        .register::<GenericRecordSerializer<i32>>(120)
        .unwrap();
    roundtrip::<GenericRecordSerializer<i32>>(&generic, &GenericRecord { value: 42 });
}

#[test]
fn native_struct_enum_composition() {
    for compatible in [false, true] {
        let fory = configured_fory_mode(compatible);
        let idle = Command::Idle;
        let create = Command::Create {
            id: 7,
            label: "build".to_string(),
        };
        let moved = Command::Move(3, 5);

        roundtrip::<CommandSerializer>(&fory, &idle);
        roundtrip::<CommandSerializer>(&fory, &create);
        roundtrip::<CommandSerializer>(&fory, &moved);

        let fields = CommandFields {
            direct: create.clone(),
            list: vec![idle.clone(), moved.clone()],
            map: HashMap::from([("job".to_string(), create.clone())]),
            tuple: ("next".to_string(), moved.clone()),
        };
        let bytes = fory.serialize(&fields).unwrap();
        let decoded: CommandFields = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded, fields);

        roundtrip::<VecSerializer<CommandSerializer>>(
            &fory,
            &vec![idle.clone(), create.clone(), moved.clone()],
        );
        roundtrip::<HashMapSerializer<String, CommandSerializer>>(
            &fory,
            &HashMap::from([("job".to_string(), create.clone())]),
        );
        roundtrip::<Tuple2Serializer<String, CommandSerializer>>(
            &fory,
            &("next".to_string(), moved.clone()),
        );

        let dynamic: Box<dyn Any> = Box::new(create.clone());
        let bytes = fory.serialize(&dynamic).unwrap();
        let decoded: Box<dyn Any> = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded.downcast_ref::<Command>(), Some(&create));

        let operation: Box<dyn Operation> = Box::new(moved.clone());
        let bytes = fory.serialize(&operation).unwrap();
        let decoded: Box<dyn Operation> = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded.kind(), "move");
        assert_eq!(
            decoded.as_ref().as_any().downcast_ref::<Command>(),
            Some(&moved)
        );
    }
}

#[test]
fn external_enum_bytes_match() {
    fn assert_bytes(compatible: bool, local: &LocalCommandWire, external: &Command) {
        let mut local_fory = Fory::builder()
            .xlang(false)
            .compatible(compatible)
            .track_ref(true)
            .build();
        local_fory.register::<LocalCommandWire>(780).unwrap();
        let mut external_fory = Fory::builder()
            .xlang(false)
            .compatible(compatible)
            .track_ref(true)
            .build();
        external_fory.register::<CommandSerializer>(780).unwrap();

        assert_eq!(
            local_fory.serialize(local).unwrap(),
            external_fory
                .serialize_with::<CommandSerializer>(external)
                .unwrap()
        );
    }

    for compatible in [false, true] {
        assert_bytes(compatible, &LocalCommandWire::Idle, &Command::Idle);
        assert_bytes(
            compatible,
            &LocalCommandWire::Move(3, 5),
            &Command::Move(3, 5),
        );
        assert_bytes(
            compatible,
            &LocalCommandWire::Create {
                id: 7,
                label: "build".to_string(),
            },
            &Command::Create {
                id: 7,
                label: "build".to_string(),
            },
        );
    }
}

#[test]
fn explicit_root_family() {
    let fory = configured_fory();
    let value = user("Ada", 37);

    let bytes = fory.serialize_with::<UserSerializer>(&value).unwrap();
    assert_eq!(
        fory.deserialize_with::<UserSerializer>(&bytes).unwrap(),
        value
    );

    let mut buffer = vec![1, 2, 3];
    let written = fory
        .serialize_to_with::<UserSerializer>(&mut buffer, &value)
        .unwrap();
    assert_eq!(written, buffer.len() - 3);
    let mut reader = Reader::new(&buffer[3..]);
    assert_eq!(
        fory.deserialize_from_with::<UserSerializer>(&mut reader)
            .unwrap(),
        value
    );

    let id = ExternalId(0x1020_3040_5060_7080_90a0_b0c0_d0e0_f000);
    roundtrip::<ExternalIdSerializer>(&fory, &id);
    roundtrip::<PackedEntrySerializer>(&fory, &("lead".to_string(), user("Ada", 37)));

    let users = vec![user("Ada", 37), user("Grace", 28)];
    let mut opaque = Fory::builder().xlang(false).compatible(false).build();
    opaque
        .register_serializer::<PackedUsersSerializer>(130)
        .unwrap();
    roundtrip::<PackedUsersSerializer>(&opaque, &users);
}

#[test]
fn registration_by_name() {
    let mut structural = Fory::builder().xlang(false).compatible(false).build();
    structural
        .register_by_name::<UserSerializer>("example.User")
        .unwrap();
    roundtrip::<UserSerializer>(&structural, &user("Ada", 37));

    let mut union = Fory::builder().xlang(true).compatible(false).build();
    union
        .register_union_by_name::<ValueSerializer>("example.Value")
        .unwrap();
    roundtrip::<ValueSerializer>(&union, &Value::Count(42));

    let mut custom = Fory::builder().xlang(false).compatible(false).build();
    custom
        .register_serializer_by_name::<ExternalIdSerializer>("example.ExternalId")
        .unwrap();
    roundtrip::<ExternalIdSerializer>(&custom, &ExternalId(9));
}

#[test]
fn exact_container_coexists() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<UserSerializer>(130).unwrap();
    fory.register_serializer::<PackedUsersSerializer>(131)
        .unwrap();

    let users = vec![user("Ada", 37), user("Grace", 28)];
    let structural = fory
        .serialize_with::<VecSerializer<UserSerializer>>(&users)
        .unwrap();
    let opaque = fory
        .serialize_with::<PackedUsersSerializer>(&users)
        .unwrap();
    assert_ne!(structural, opaque);
    assert_eq!(
        fory.deserialize_with::<VecSerializer<UserSerializer>>(&structural)
            .unwrap(),
        users
    );
    assert_eq!(
        fory.deserialize_with::<PackedUsersSerializer>(&opaque)
            .unwrap(),
        users
    );

    let dynamic: Box<dyn Any> = Box::new(users.clone());
    let bytes = fory.serialize(&dynamic).unwrap();
    let decoded: Box<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.downcast_ref::<Vec<User>>(), Some(&users));
}

#[test]
fn carrier_roots() {
    let fory = configured_fory();
    let ada = user("Ada", 37);
    let grace = user("Grace", 28);

    roundtrip::<OptionSerializer<UserSerializer>>(&fory, &Some(ada.clone()));
    roundtrip::<OptionSerializer<UserSerializer>>(&fory, &None);
    roundtrip::<BoxSerializer<UserSerializer>>(&fory, &Box::new(ada.clone()));
    roundtrip::<RcSerializer<UserSerializer>>(&fory, &Rc::new(ada.clone()));
    roundtrip::<ArcSerializer<UserSerializer>>(&fory, &Arc::new(ada.clone()));

    let cell = RefCell::new(ada.clone());
    let bytes = fory
        .serialize_with::<RefCellSerializer<UserSerializer>>(&cell)
        .unwrap();
    let decoded = fory
        .deserialize_with::<RefCellSerializer<UserSerializer>>(&bytes)
        .unwrap();
    assert_eq!(*decoded.borrow(), ada);
    let mut buffer = vec![0xaa];
    fory.serialize_to_with::<RefCellSerializer<UserSerializer>>(&mut buffer, &cell)
        .unwrap();
    let mut reader = Reader::new(&buffer[1..]);
    let decoded = fory
        .deserialize_from_with::<RefCellSerializer<UserSerializer>>(&mut reader)
        .unwrap();
    assert_eq!(*decoded.borrow(), ada);

    let mutex = Mutex::new(grace.clone());
    let bytes = fory
        .serialize_with::<MutexSerializer<UserSerializer>>(&mutex)
        .unwrap();
    let decoded = fory
        .deserialize_with::<MutexSerializer<UserSerializer>>(&bytes)
        .unwrap();
    assert_eq!(*decoded.lock().unwrap(), grace);
    let mut buffer = vec![0xaa];
    fory.serialize_to_with::<MutexSerializer<UserSerializer>>(&mut buffer, &mutex)
        .unwrap();
    let mut reader = Reader::new(&buffer[1..]);
    let decoded = fory
        .deserialize_from_with::<MutexSerializer<UserSerializer>>(&mut reader)
        .unwrap();
    assert_eq!(*decoded.lock().unwrap(), grace);

    let values = vec![user("Ada", 37), user("Grace", 28)];
    roundtrip::<VecSerializer<UserSerializer>>(&fory, &values);
    roundtrip::<VecSerializer<ExternalIdSerializer>>(&fory, &vec![ExternalId(1), ExternalId(2)]);
    roundtrip::<VecDequeSerializer<UserSerializer>>(&fory, &values.iter().cloned().collect());
    roundtrip::<LinkedListSerializer<UserSerializer>>(&fory, &values.iter().cloned().collect());
    roundtrip::<HashSetSerializer<UserSerializer>>(&fory, &values.iter().cloned().collect());
    roundtrip::<BTreeSetSerializer<UserSerializer>>(&fory, &values.iter().cloned().collect());

    let heap: BinaryHeap<User> = values.iter().cloned().collect();
    let bytes = fory
        .serialize_with::<BinaryHeapSerializer<UserSerializer>>(&heap)
        .unwrap();
    let decoded = fory
        .deserialize_with::<BinaryHeapSerializer<UserSerializer>>(&bytes)
        .unwrap();
    assert_eq!(decoded.into_sorted_vec(), heap.clone().into_sorted_vec());
    let mut buffer = vec![0xaa];
    fory.serialize_to_with::<BinaryHeapSerializer<UserSerializer>>(&mut buffer, &heap)
        .unwrap();
    let mut reader = Reader::new(&buffer[1..]);
    let decoded = fory
        .deserialize_from_with::<BinaryHeapSerializer<UserSerializer>>(&mut reader)
        .unwrap();
    assert_eq!(decoded.into_sorted_vec(), heap.into_sorted_vec());

    roundtrip::<ArraySerializer<UserSerializer, 2>>(&fory, &[values[0].clone(), values[1].clone()]);

    let hash_map = HashMap::from([
        (key("people", 1), values[0].clone()),
        (key("people", 2), values[1].clone()),
    ]);
    roundtrip::<HashMapSerializer<KeySerializer, UserSerializer>>(&fory, &hash_map);
    roundtrip::<BTreeMapSerializer<KeySerializer, UserSerializer>>(
        &fory,
        &hash_map.clone().into_iter().collect(),
    );
    roundtrip::<HashMapSerializer<KeySerializer, String>>(
        &fory,
        &HashMap::from([
            (key("people", 1), "Ada".to_string()),
            (key("people", 2), "Grace".to_string()),
        ]),
    );
    roundtrip::<HashMapSerializer<String, UserSerializer>>(
        &fory,
        &HashMap::from([
            ("lead".to_string(), values[0].clone()),
            ("reviewer".to_string(), values[1].clone()),
        ]),
    );
    roundtrip::<HashMapSerializer<KeySerializer, VecSerializer<UserSerializer>>>(
        &fory,
        &HashMap::from([(key("team", 1), values.clone())]),
    );

    roundtrip::<Tuple1Serializer<UserSerializer>>(&fory, &(values[0].clone(),));
    roundtrip::<Tuple2Serializer<String, UserSerializer>>(
        &fory,
        &("lead".to_string(), values[0].clone()),
    );
    roundtrip::<Tuple3Serializer<UserSerializer, String, i32>>(
        &fory,
        &(values[0].clone(), "first".to_string(), 1),
    );
    roundtrip::<Tuple3Serializer<String, UserSerializer, i32>>(
        &fory,
        &("middle".to_string(), values[0].clone(), 2),
    );
    roundtrip::<Tuple3Serializer<String, i32, UserSerializer>>(
        &fory,
        &("last".to_string(), 3, values[0].clone()),
    );
}

#[test]
fn empty_carriers_skip_children() {
    let fory = Fory::builder()
        .xlang(false)
        .compatible(false)
        .track_ref(true)
        .build();

    roundtrip::<VecSerializer<UserSerializer>>(&fory, &Vec::new());
    roundtrip::<HashSetSerializer<UserSerializer>>(&fory, &HashSet::new());
    roundtrip::<HashMapSerializer<KeySerializer, UserSerializer>>(&fory, &HashMap::new());
    roundtrip::<ArraySerializer<UserSerializer, 0>>(&fory, &[]);
    roundtrip::<OptionSerializer<UserSerializer>>(&fory, &None);
    roundtrip::<RcWeakSerializer<UserSerializer>>(&fory, &RcWeak::new());
    roundtrip::<ArcWeakSerializer<UserSerializer>>(&fory, &ArcWeak::new());
    assert!(fory
        .serialize_with::<VecSerializer<UserSerializer>>(&vec![user("Ada", 37)])
        .is_err());
}

#[test]
fn zero_body_carrier_bounds() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<MarkerSerializer>(180).unwrap();
    let markers = vec![Marker; 64];

    roundtrip::<VecSerializer<MarkerSerializer>>(&fory, &markers);
    roundtrip::<VecDequeSerializer<MarkerSerializer>>(&fory, &markers.iter().copied().collect());
    let heap: BinaryHeap<Marker> = markers.iter().copied().collect();
    let bytes = fory
        .serialize_with::<BinaryHeapSerializer<MarkerSerializer>>(&heap)
        .unwrap();
    let decoded = fory
        .deserialize_with::<BinaryHeapSerializer<MarkerSerializer>>(&bytes)
        .unwrap();
    assert_eq!(decoded.len(), heap.len());
    roundtrip::<ArraySerializer<MarkerSerializer, 64>>(&fory, &[Marker; 64]);
    roundtrip::<HashSetSerializer<MarkerSerializer>>(&fory, &markers.iter().copied().collect());
    roundtrip::<BTreeSetSerializer<MarkerSerializer>>(&fory, &markers.iter().copied().collect());

    let linked: LinkedList<Marker> = markers.iter().copied().collect();
    roundtrip::<LinkedListSerializer<MarkerSerializer>>(&fory, &linked);
}

#[test]
fn non_zst_zero_body_bounds() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register_serializer::<SilentValueSerializer>(181)
        .unwrap();

    let fixed = [SilentValue(0); 64];
    let fixed_bytes = fory
        .serialize_with::<ArraySerializer<SilentValueSerializer, 64>>(&fixed)
        .unwrap();
    assert_eq!(
        fory.deserialize_with::<ArraySerializer<SilentValueSerializer, 64>>(&fixed_bytes)
            .unwrap(),
        fixed
    );
    let values = vec![SilentValue(0); 256];
    let values_bytes = fory
        .serialize_with::<VecSerializer<SilentValueSerializer>>(&values)
        .unwrap();

    let hash_map: HashMap<_, _> = (0u64..256)
        .map(|id| (SilentValue(id), SilentValue(id)))
        .collect();
    let hash_bytes = fory
        .serialize_with::<HashMapSerializer<SilentValueSerializer, SilentValueSerializer>>(
            &hash_map,
        )
        .unwrap();

    let tree_map: BTreeMap<_, _> = (0u64..256)
        .map(|id| (SilentValue(id), SilentValue(id)))
        .collect();
    let tree_bytes = fory
        .serialize_with::<BTreeMapSerializer<SilentValueSerializer, SilentValueSerializer>>(
            &tree_map,
        )
        .unwrap();

    let mut limited = Fory::builder()
        .xlang(false)
        .compatible(false)
        .max_unbacked_container_items(255)
        .build();
    limited
        .register_serializer::<SilentValueSerializer>(181)
        .unwrap();
    assert!(limited
        .deserialize_with::<VecSerializer<SilentValueSerializer>>(&values_bytes)
        .is_err());
    assert!(limited
        .deserialize_with::<HashMapSerializer<SilentValueSerializer, SilentValueSerializer>>(
            &hash_bytes,
        )
        .is_err());
    assert!(limited
        .deserialize_with::<BTreeMapSerializer<SilentValueSerializer, SilentValueSerializer>>(
            &tree_bytes,
        )
        .is_err());
}

#[test]
fn reference_carrier_roots() {
    let fory = configured_fory();

    let shared = Rc::new(user("Ada", 37));
    let values = vec![shared.clone(), shared.clone()];
    let bytes = fory
        .serialize_with::<VecSerializer<RcSerializer<UserSerializer>>>(&values)
        .unwrap();
    let decoded = fory
        .deserialize_with::<VecSerializer<RcSerializer<UserSerializer>>>(&bytes)
        .unwrap();
    assert!(Rc::ptr_eq(&decoded[0], &decoded[1]));

    let shared = Arc::new(user("Grace", 28));
    let values = vec![shared.clone(), shared.clone()];
    let bytes = fory
        .serialize_with::<VecSerializer<ArcSerializer<UserSerializer>>>(&values)
        .unwrap();
    let decoded = fory
        .deserialize_with::<VecSerializer<ArcSerializer<UserSerializer>>>(&bytes)
        .unwrap();
    assert!(Arc::ptr_eq(&decoded[0], &decoded[1]));

    let rc = Rc::new(user("Linus", 33));
    let weak = RcWeak::from(&rc);
    let bytes = fory
        .serialize_with::<RcWeakSerializer<UserSerializer>>(&weak)
        .unwrap();
    let _: RcWeak<User> = fory
        .deserialize_with::<RcWeakSerializer<UserSerializer>>(&bytes)
        .unwrap();
    let mut buffer = vec![0xaa];
    fory.serialize_to_with::<RcWeakSerializer<UserSerializer>>(&mut buffer, &weak)
        .unwrap();
    let mut reader = Reader::new(&buffer[1..]);
    let _: RcWeak<User> = fory
        .deserialize_from_with::<RcWeakSerializer<UserSerializer>>(&mut reader)
        .unwrap();

    let arc = Arc::new(user("Margaret", 41));
    let weak = ArcWeak::from(&arc);
    let bytes = fory
        .serialize_with::<ArcWeakSerializer<UserSerializer>>(&weak)
        .unwrap();
    let _: ArcWeak<User> = fory
        .deserialize_with::<ArcWeakSerializer<UserSerializer>>(&bytes)
        .unwrap();
    let mut buffer = vec![0xaa];
    fory.serialize_to_with::<ArcWeakSerializer<UserSerializer>>(&mut buffer, &weak)
        .unwrap();
    let mut reader = Reader::new(&buffer[1..]);
    let _: ArcWeak<User> = fory
        .deserialize_from_with::<ArcWeakSerializer<UserSerializer>>(&mut reader)
        .unwrap();
}

#[test]
fn recursive_field_codegen() {
    let fory = configured_fory();
    let ada = user("Ada", 37);
    let grace = user("Grace", 28);
    let values = vec![ada.clone(), grace.clone()];
    let rc = Rc::new(ada.clone());
    let arc = Arc::new(grace.clone());
    let value = ExternalFields {
        direct: ada.clone(),
        optional: Some(grace.clone()),
        boxed: Box::new(ada.clone()),
        rc: rc.clone(),
        rc_weak: RcWeak::from(&rc),
        arc: arc.clone(),
        arc_weak: ArcWeak::from(&arc),
        cell: RefCell::new(ada.clone()),
        mutex: Mutex::new(grace.clone()),
        direct_vec: values.clone(),
        vec: values.clone(),
        packed: values.clone(),
        deque: values.iter().cloned().collect(),
        linked: values.iter().cloned().collect(),
        hash_set: values.iter().cloned().collect(),
        tree_set: values.iter().cloned().collect(),
        heap: values.iter().cloned().collect(),
        array: [ada.clone(), grace.clone()],
        hash_map: HashMap::from([(key("people", 1), ada.clone())]),
        tree_map: BTreeMap::from([(key("people", 2), grace.clone())]),
        tuple: ("team".to_string(), ada.clone(), values.clone()),
        packed_entry: ("lead".to_string(), ada.clone()),
    };

    let bytes = fory.serialize(&value).unwrap();
    let decoded: ExternalFields = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.direct, value.direct);
    assert_eq!(decoded.optional, value.optional);
    assert_eq!(decoded.boxed, value.boxed);
    assert_eq!(*decoded.rc, *value.rc);
    assert!(Rc::ptr_eq(&decoded.rc, &decoded.rc_weak.upgrade().unwrap()));
    assert_eq!(*decoded.arc, *value.arc);
    assert!(Arc::ptr_eq(
        &decoded.arc,
        &decoded.arc_weak.upgrade().unwrap()
    ));
    assert_eq!(*decoded.cell.borrow(), *value.cell.borrow());
    assert_eq!(*decoded.mutex.lock().unwrap(), *value.mutex.lock().unwrap());
    assert_eq!(decoded.direct_vec, value.direct_vec);
    assert_eq!(decoded.vec, value.vec);
    assert_eq!(decoded.packed, value.packed);
    assert_eq!(decoded.deque, value.deque);
    assert_eq!(decoded.linked, value.linked);
    assert_eq!(decoded.hash_set, value.hash_set);
    assert_eq!(decoded.tree_set, value.tree_set);
    assert_eq!(decoded.heap.into_sorted_vec(), value.heap.into_sorted_vec());
    assert_eq!(decoded.array, value.array);
    assert_eq!(decoded.hash_map, value.hash_map);
    assert_eq!(decoded.tree_map, value.tree_map);
    assert_eq!(decoded.tuple, value.tuple);
    assert_eq!(decoded.packed_entry, value.packed_entry);

    let skipped = UserWithState {
        name: "Ada".to_string(),
        state: ExternalId(99),
    };
    let bytes = fory
        .serialize_with::<UserWithStateSerializer>(&skipped)
        .unwrap();
    let decoded = fory
        .deserialize_with::<UserWithStateSerializer>(&bytes)
        .unwrap();
    assert_eq!(decoded.name, skipped.name);
    assert_eq!(decoded.state, ExternalId(0));

    let mut failing = Fory::builder().xlang(false).compatible(false).build();
    failing
        .register::<UserWithFailingStateSerializer>(111)
        .unwrap();
    let bytes = failing
        .serialize_with::<UserWithFailingStateSerializer>(&skipped)
        .unwrap();
    let error = failing
        .deserialize_with::<UserWithFailingStateSerializer>(&bytes)
        .unwrap_err();
    assert!(error.to_string().contains("external id default rejected"));
}

#[test]
fn direct_carrier_field() {
    let users = vec![user("Ada", 37), user("Grace", 28)];
    for compatible in [false, true] {
        let mut direct = Fory::builder().xlang(false).compatible(compatible).build();
        direct.register::<UserSerializer>(100).unwrap();
        direct.register::<DirectUserList>(101).unwrap();

        let mut recursive = Fory::builder().xlang(false).compatible(compatible).build();
        recursive.register::<UserSerializer>(100).unwrap();
        recursive.register::<RecursiveUserList>(101).unwrap();

        let direct_value = DirectUserList {
            users: users.clone(),
        };
        let recursive_value = RecursiveUserList {
            users: users.clone(),
        };
        let direct_bytes = direct.serialize(&direct_value).unwrap();
        let recursive_bytes = recursive.serialize(&recursive_value).unwrap();
        assert_eq!(direct_bytes, recursive_bytes);
        assert_eq!(
            direct.deserialize::<DirectUserList>(&direct_bytes).unwrap(),
            direct_value
        );
        assert_eq!(
            recursive
                .deserialize::<RecursiveUserList>(&recursive_bytes)
                .unwrap(),
            recursive_value
        );
    }
}

#[test]
fn direct_carrier_field_family() {
    let mut fory = configured_fory();
    fory.register::<DirectCarrierFields>(190).unwrap();

    let ada = user("Ada", 37);
    let grace = user("Grace", 28);
    let users = [ada.clone(), grace.clone()];
    let value = DirectCarrierFields {
        deque: users.iter().cloned().collect(),
        linked: users.iter().cloned().collect(),
        hash_set: users.iter().cloned().collect(),
        tree_set: users.iter().cloned().collect(),
        heap: users.iter().cloned().collect(),
        array: [ada.clone(), grace.clone()],
        hash_map: HashMap::from([(key("people", 1), ada.clone())]),
        tree_map: BTreeMap::from([(key("people", 2), grace.clone())]),
        tuple1: (ada.clone(),),
        tuple2: ("lead".to_string(), grace.clone()),
        nested: HashMap::from([(
            key("team", 1),
            vec![
                ("lead".to_string(), ada.clone()),
                ("reviewer".to_string(), grace.clone()),
            ],
        )]),
        animals: vec![Box::new(ada.clone()), Box::new(grace.clone())],
    };

    let bytes = fory.serialize(&value).unwrap();
    let decoded: DirectCarrierFields = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.deque, value.deque);
    assert_eq!(decoded.linked, value.linked);
    assert_eq!(decoded.hash_set, value.hash_set);
    assert_eq!(decoded.tree_set, value.tree_set);
    assert_eq!(decoded.heap.into_sorted_vec(), value.heap.into_sorted_vec());
    assert_eq!(decoded.array, value.array);
    assert_eq!(decoded.hash_map, value.hash_map);
    assert_eq!(decoded.tree_map, value.tree_map);
    assert_eq!(decoded.tuple1, value.tuple1);
    assert_eq!(decoded.tuple2, value.tuple2);
    assert_eq!(decoded.nested, value.nested);
    assert_eq!(
        decoded
            .animals
            .iter()
            .map(|animal| animal.name())
            .collect::<Vec<_>>(),
        vec!["Ada", "Grace"]
    );
}

#[test]
fn carrier_alias_field_is_rejected() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<UserSerializer>(100).unwrap();
    fory.register::<AliasedUserList>(101).unwrap();

    let error = fory
        .serialize(&AliasedUserList {
            users: vec![user("Ada", 37)],
        })
        .unwrap_err();
    assert!(error.to_string().contains("canonical carrier syntax"));
}

#[test]
fn skipped_carrier_defaults() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<SkippedCarrierDefaults>(182).unwrap();

    let value = SkippedCarrierDefaults {
        users: vec![user("Ada", 37)],
        by_key: HashMap::from([(key("people", 1), user("Grace", 28))]),
        array: [user("Ada", 37), user("Grace", 28)],
        tuple: ("lead".to_string(), user("Linus", 33)),
    };
    let bytes = fory.serialize(&value).unwrap();
    let decoded: SkippedCarrierDefaults = fory.deserialize(&bytes).unwrap();
    let default_user = user("", 0);
    assert_eq!(
        decoded,
        SkippedCarrierDefaults {
            users: Vec::new(),
            by_key: HashMap::new(),
            array: [default_user.clone(), default_user.clone()],
            tuple: (String::new(), default_user),
        }
    );
}

#[test]
fn primitive_carriers_match_roots() {
    let fory = Fory::builder().xlang(false).compatible(false).build();

    macro_rules! assert_vec {
        ($ty:ty, $value:expr) => {{
            let value: Vec<$ty> = $value;
            let ordinary = fory.serialize(&value).unwrap();
            let selected = fory.serialize_with::<VecSerializer<$ty>>(&value).unwrap();
            assert_eq!(selected, ordinary);
            assert_eq!(
                fory.deserialize_with::<VecSerializer<$ty>>(&selected)
                    .unwrap(),
                value
            );
        }};
    }

    assert_vec!(bool, vec![true, false, true]);
    assert_vec!(i8, vec![-1, 2, 3]);
    assert_vec!(u8, vec![1, 2, 3]);
    assert_vec!(i16, vec![-1, 2, 3]);
    assert_vec!(i32, vec![-1, 2, 3]);
    assert_vec!(i64, vec![-1, 2, 3]);
    assert_vec!(u16, vec![1, 2, 3]);
    assert_vec!(u32, vec![1, 2, 3]);
    assert_vec!(u64, vec![1, 2, 3]);
    assert_vec!(i128, vec![-1, 2, 3]);
    assert_vec!(u128, vec![1, 2, 3]);
    assert_vec!(isize, vec![-1, 2, 3]);
    assert_vec!(usize, vec![1, 2, 3]);
    assert_vec!(f32, vec![1.0, 2.0, 3.0]);
    assert_vec!(f64, vec![1.0, 2.0, 3.0]);
    assert_vec!(
        Float16,
        vec![Float16::from_f32(1.0), Float16::from_f32(2.0)]
    );
    assert_vec!(
        BFloat16,
        vec![BFloat16::from_f32(1.0), BFloat16::from_f32(2.0)]
    );

    macro_rules! assert_array {
        ($ty:ty, $value:expr) => {{
            let value: [$ty; 3] = $value;
            let ordinary = fory.serialize(&value).unwrap();
            let selected = fory
                .serialize_with::<ArraySerializer<$ty, 3>>(&value)
                .unwrap();
            assert_eq!(selected, ordinary);
            assert_eq!(
                fory.deserialize_with::<ArraySerializer<$ty, 3>>(&selected)
                    .unwrap(),
                value
            );
        }};
    }

    assert_array!(bool, [true, false, true]);
    assert_array!(i8, [-1, 2, 3]);
    assert_array!(u8, [1, 2, 3]);
    assert_array!(i16, [-1, 2, 3]);
    assert_array!(i32, [-1, 2, 3]);
    assert_array!(i64, [-1, 2, 3]);
    assert_array!(u16, [1, 2, 3]);
    assert_array!(u32, [1, 2, 3]);
    assert_array!(u64, [1, 2, 3]);
    assert_array!(i128, [-1, 2, 3]);
    assert_array!(u128, [1, 2, 3]);
    assert_array!(isize, [-1, 2, 3]);
    assert_array!(usize, [1, 2, 3]);
    assert_array!(f32, [1.0, 2.0, 3.0]);
    assert_array!(f64, [1.0, 2.0, 3.0]);
    assert_array!(
        Float16,
        [
            Float16::from_f32(1.0),
            Float16::from_f32(2.0),
            Float16::from_f32(3.0)
        ]
    );
    assert_array!(
        BFloat16,
        [
            BFloat16::from_f32(1.0),
            BFloat16::from_f32(2.0),
            BFloat16::from_f32(3.0)
        ]
    );

    let nested = vec![vec![1i32, 2, 3], vec![4, 5]];
    let ordinary = fory.serialize(&nested).unwrap();
    let selected = fory
        .serialize_with::<VecSerializer<VecSerializer<i32>>>(&nested)
        .unwrap();
    assert_eq!(selected, ordinary);
    roundtrip::<VecSerializer<VecSerializer<i32>>>(&fory, &nested);

    assert_eq!(
        <VecSerializer<I32Serializer> as Serializer>::static_type_id(),
        TypeId::LIST
    );
    let mut object = Fory::builder().xlang(false).compatible(false).build();
    assert!(object.register_serializer::<I32Serializer>(200).is_err());
}

#[test]
fn carrier_bytes_match_local() {
    let external_value = user("Ada", 37);
    let local_value = LocalUser {
        name: external_value.name.clone(),
        age: external_value.age,
    };

    for compatible in [false, true] {
        let mut external = Fory::builder().xlang(false).compatible(compatible).build();
        external.register::<UserSerializer>(300).unwrap();
        let mut local = Fory::builder().xlang(false).compatible(compatible).build();
        local.register::<LocalUser>(300).unwrap();

        assert_eq!(
            external
                .serialize_with::<UserSerializer>(&external_value)
                .unwrap(),
            local.serialize(&local_value).unwrap()
        );
        assert_eq!(
            external
                .serialize_with::<VecSerializer<UserSerializer>>(&vec![external_value.clone()])
                .unwrap(),
            local.serialize(&vec![local_value.clone()]).unwrap()
        );
        assert_eq!(
            external
                .serialize_with::<Tuple2Serializer<String, UserSerializer>>(&(
                    "lead".to_string(),
                    external_value.clone(),
                ))
                .unwrap(),
            local
                .serialize(&("lead".to_string(), local_value.clone()))
                .unwrap()
        );
    }
}

#[test]
fn tuple_reads_homogeneous_list() {
    for compatible in [false, true] {
        let mut fory = Fory::builder()
            .xlang(true)
            .compatible(compatible)
            .track_ref(false)
            .build();
        fory.register::<UserSerializer>(300).unwrap();

        let users = vec![user("Ada", 37), user("Grace", 28), user("Linus", 33)];
        let mut bytes = Vec::new();
        fory.serialize_to_with::<VecSerializer<UserSerializer>>(&mut bytes, &users)
            .unwrap();
        fory.serialize_to(&mut bytes, &42i32).unwrap();

        let mut reader = Reader::new(&bytes);
        let pair = fory
            .deserialize_from_with::<Tuple2Serializer<UserSerializer, UserSerializer>>(&mut reader)
            .unwrap();
        assert_eq!(pair, (users[0].clone(), users[1].clone()));
        let tail: i32 = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(tail, 42);
        assert_eq!(reader.get_cursor(), bytes.len());

        let empty = Vec::<User>::new();
        let mut bytes = Vec::new();
        fory.serialize_to_with::<VecSerializer<UserSerializer>>(&mut bytes, &empty)
            .unwrap();
        fory.serialize_to(&mut bytes, &43i32).unwrap();
        let mut reader = Reader::new(&bytes);
        let pair = fory
            .deserialize_from_with::<Tuple2Serializer<UserSerializer, UserSerializer>>(&mut reader)
            .unwrap();
        assert_eq!(pair, (user("", 0), user("", 0)));
        let tail: i32 = fory.deserialize_from(&mut reader).unwrap();
        assert_eq!(tail, 43);
        assert_eq!(reader.get_cursor(), bytes.len());

        let bytes = fory
            .serialize_with::<VecSerializer<UserSerializer>>(&users)
            .unwrap();
        assert!(fory
            .deserialize_with::<Tuple2Serializer<UserSerializer, String>>(&bytes)
            .is_err());
    }
}

#[test]
fn wrapped_maps_use_remote_schema() {
    let mut writer = Fory::builder()
        .xlang(false)
        .compatible(true)
        .track_ref(true)
        .build();
    writer.register::<WrappedMapsFixed>(760).unwrap();
    let mut reader = Fory::builder()
        .xlang(false)
        .compatible(true)
        .track_ref(true)
        .build();
    reader.register::<WrappedMapsVarint>(760).unwrap();
    let values = HashMap::from([(7, 11), (13, 17)]);
    let rc = Rc::new(values.clone());
    let arc = Arc::new(values.clone());
    let bytes = writer
        .serialize(&WrappedMapsFixed(
            Box::new(values.clone()),
            Some(values.clone()),
            RcWeak::from(&rc),
            rc,
            ArcWeak::from(&arc),
            arc,
            RefCell::new(values.clone()),
            Mutex::new(values.clone()),
        ))
        .unwrap();
    let decoded: WrappedMapsVarint = reader.deserialize(&bytes).unwrap();
    assert_eq!(*decoded.0, values);
    assert_eq!(decoded.1.as_ref(), Some(&values));
    assert_eq!(*decoded.2.upgrade().unwrap(), values);
    assert_eq!(*decoded.3, values);
    assert!(Rc::ptr_eq(&decoded.3, &decoded.2.upgrade().unwrap()));
    assert_eq!(*decoded.4.upgrade().unwrap(), values);
    assert_eq!(*decoded.5, values);
    assert!(Arc::ptr_eq(&decoded.5, &decoded.4.upgrade().unwrap()));
    assert_eq!(*decoded.6.borrow(), values);
    assert_eq!(*decoded.7.lock().unwrap(), values);
}

#[test]
fn map_rejects_invalid_chunks() {
    let fory = Fory::builder().xlang(false).compatible(false).build();
    let values = HashMap::from([(7i32, 11i32)]);
    let mut bytes = fory
        .serialize_with::<HashMapSerializer<i32, i32>>(&values)
        .unwrap();
    let chunk_offset = bytes
        .windows(4)
        .position(|window| window == [0, 1, TypeId::VARINT32 as u8, TypeId::VARINT32 as u8])
        .map(|header_offset| header_offset + 1)
        .unwrap();

    bytes[chunk_offset] = 0;
    assert!(fory
        .deserialize_with::<HashMapSerializer<i32, i32>>(&bytes)
        .is_err());

    bytes[chunk_offset] = 2;
    assert!(fory
        .deserialize_with::<HashMapSerializer<i32, i32>>(&bytes)
        .is_err());
}

#[test]
fn compatible_external_schema() {
    let mut writer = Fory::builder().xlang(false).compatible(true).build();
    writer.register::<UserV1Serializer>(400).unwrap();
    let mut reader = Fory::builder().xlang(false).compatible(true).build();
    reader.register::<UserV2Serializer>(400).unwrap();

    let bytes = writer
        .serialize_with::<UserV1Serializer>(&UserV1 {
            name: "Ada".to_string(),
        })
        .unwrap();
    let decoded = reader.deserialize_with::<UserV2Serializer>(&bytes).unwrap();
    assert_eq!(
        decoded,
        UserV2 {
            name: "Ada".to_string(),
            age: 0,
        }
    );
}

#[test]
fn compatible_carrier_schema() {
    let mut writer = Fory::builder().xlang(false).compatible(true).build();
    writer.register::<UserV1Serializer>(420).unwrap();
    writer.register::<CompatibleCarriersV1>(421).unwrap();
    let mut reader = Fory::builder().xlang(false).compatible(true).build();
    reader.register::<UserV2Serializer>(420).unwrap();
    reader.register::<CompatibleCarriersV2>(421).unwrap();

    let ada = UserV1 {
        name: "Ada".to_string(),
    };
    let grace = UserV1 {
        name: "Grace".to_string(),
    };
    let bytes = writer
        .serialize(&CompatibleCarriersV1 {
            direct_list: vec![ada.clone()],
            recursive_list: vec![grace.clone()],
            direct_map: HashMap::from([("direct".to_string(), ada.clone())]),
            recursive_map: HashMap::from([("recursive".to_string(), grace.clone())]),
            direct_tuple: ("direct".to_string(), ada),
            recursive_tuple: ("recursive".to_string(), grace),
        })
        .unwrap();
    let decoded: CompatibleCarriersV2 = reader.deserialize(&bytes).unwrap();

    let assert_user = |value: &UserV2, name: &str| {
        assert_eq!(
            value,
            &UserV2 {
                name: name.to_string(),
                age: 0,
            }
        );
    };
    assert_user(&decoded.direct_list[0], "Ada");
    assert_user(&decoded.recursive_list[0], "Grace");
    assert_user(&decoded.direct_map["direct"], "Ada");
    assert_user(&decoded.recursive_map["recursive"], "Grace");
    assert_eq!(decoded.direct_tuple.0, "direct");
    assert_user(&decoded.direct_tuple.1, "Ada");
    assert_eq!(decoded.recursive_tuple.0, "recursive");
    assert_user(&decoded.recursive_tuple.1, "Grace");
}

#[test]
fn compatible_shared_external_schema() {
    let mut writer = Fory::builder()
        .xlang(false)
        .compatible(true)
        .track_ref(true)
        .build();
    writer.register::<UserV1Serializer>(410).unwrap();
    writer.register::<CompatibleSharedV1>(411).unwrap();
    let mut reader = Fory::builder()
        .xlang(false)
        .compatible(true)
        .track_ref(true)
        .build();
    reader.register::<UserV2Serializer>(410).unwrap();
    reader.register::<CompatibleSharedV2>(411).unwrap();

    let rc = Rc::new(UserV1 {
        name: "Ada".to_string(),
    });
    let arc = Arc::new(UserV1 {
        name: "Grace".to_string(),
    });
    let bytes = writer
        .serialize(&CompatibleSharedV1(
            RcWeak::from(&rc),
            rc,
            ArcWeak::from(&arc),
            arc,
        ))
        .unwrap();
    let decoded: CompatibleSharedV2 = reader.deserialize(&bytes).unwrap();

    let weak_rc = decoded.0.upgrade().unwrap();
    assert_eq!(
        *weak_rc,
        UserV2 {
            name: "Ada".to_string(),
            age: 0,
        }
    );
    assert!(Rc::ptr_eq(&weak_rc, &decoded.1));
    let weak_arc = decoded.2.upgrade().unwrap();
    assert_eq!(
        *weak_arc,
        UserV2 {
            name: "Grace".to_string(),
            age: 0,
        }
    );
    assert!(Arc::ptr_eq(&weak_arc, &decoded.3));
}

#[test]
fn dynamic_external_targets() {
    let fory = configured_fory();

    let value: Box<dyn Any> = Box::new(user("Ada", 37));
    let bytes = fory.serialize(&value).unwrap();
    let decoded: Box<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(
        decoded.downcast::<User>().unwrap(),
        Box::new(user("Ada", 37))
    );

    let value: Rc<dyn Any> = Rc::new(user("Grace", 28));
    let bytes = fory.serialize(&value).unwrap();
    let decoded: Rc<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.downcast_ref::<User>(), Some(&user("Grace", 28)));

    let value: Arc<dyn Any + Send + Sync> = Arc::new(user("Linus", 33));
    let bytes = fory.serialize(&value).unwrap();
    let decoded: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.downcast_ref::<User>(), Some(&user("Linus", 33)));

    let value: Arc<dyn Any + Send + Sync> = Arc::new(ExternalId(9));
    let bytes = fory.serialize(&value).unwrap();
    let decoded: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.downcast_ref::<ExternalId>(), Some(&ExternalId(9)));

    let value: Box<dyn Any> = Box::new(ExternalId(10));
    let bytes = fory.serialize(&value).unwrap();
    let decoded: Box<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.downcast_ref::<ExternalId>(), Some(&ExternalId(10)));

    let value: Rc<dyn Any> = Rc::new(ExternalId(11));
    let bytes = fory.serialize(&value).unwrap();
    let decoded: Rc<dyn Any> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.downcast_ref::<ExternalId>(), Some(&ExternalId(11)));
}

#[test]
fn xlang_dynamic_external_targets() {
    for compatible in [false, true] {
        let mut fory = Fory::builder().xlang(true).compatible(compatible).build();
        fory.register::<UserSerializer>(750).unwrap();

        let dynamic: Box<dyn Any> = Box::new(user("Ada", 37));
        let bytes = fory.serialize(&dynamic).unwrap();
        let decoded: Box<dyn Any> = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded.downcast_ref::<User>(), Some(&user("Ada", 37)));

        let dynamic: Rc<dyn Any> = Rc::new(user("Grace", 28));
        let bytes = fory.serialize(&dynamic).unwrap();
        let decoded: Rc<dyn Any> = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded.downcast_ref::<User>(), Some(&user("Grace", 28)));

        let dynamic: Arc<dyn Any + Send + Sync> = Arc::new(user("Linus", 33));
        let bytes = fory.serialize(&dynamic).unwrap();
        let decoded: Arc<dyn Any + Send + Sync> = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded.downcast_ref::<User>(), Some(&user("Linus", 33)));

        let animal: Box<dyn Animal> = Box::new(user("Rex", 4));
        let bytes = fory.serialize(&animal).unwrap();
        let decoded: Box<dyn Animal> = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded.name(), "Rex");

        let animal: Rc<dyn Animal> = Rc::new(user("Milo", 5));
        let bytes = fory.serialize_with::<AnimalRcSerializer>(&animal).unwrap();
        let decoded = fory.deserialize_with::<AnimalRcSerializer>(&bytes).unwrap();
        assert_eq!(decoded.name(), "Milo");

        let animal: Arc<dyn SharedAnimal> = Arc::new(user("Luna", 6));
        let bytes = fory
            .serialize_with::<SharedAnimalArcSerializer>(&animal)
            .unwrap();
        let decoded = fory
            .deserialize_with::<SharedAnimalArcSerializer>(&bytes)
            .unwrap();
        assert_eq!(decoded.name(), "Luna");

        roundtrip::<VecSerializer<UserSerializer>>(
            &fory,
            &vec![user("Ada", 37), user("Grace", 28)],
        );
    }
}

#[test]
fn application_trait_roots() {
    let fory = configured_fory();

    let animal: Box<dyn Animal> = Box::new(user("Rex", 4));
    let bytes = fory.serialize(&animal).unwrap();
    let decoded: Box<dyn Animal> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.name(), "Rex");

    let animal: Rc<dyn Animal> = Rc::new(user("Milo", 5));
    let bytes = fory.serialize_with::<AnimalRcSerializer>(&animal).unwrap();
    let decoded = fory.deserialize_with::<AnimalRcSerializer>(&bytes).unwrap();
    assert_eq!(decoded.name(), "Milo");

    let animal: Arc<dyn SharedAnimal> = Arc::new(user("Luna", 6));
    let bytes = fory
        .serialize_with::<SharedAnimalArcSerializer>(&animal)
        .unwrap();
    let decoded = fory
        .deserialize_with::<SharedAnimalArcSerializer>(&bytes)
        .unwrap();
    assert_eq!(decoded.name(), "Luna");

    let identifier: Box<dyn Identifier> = Box::new(ExternalId(21));
    let bytes = fory.serialize(&identifier).unwrap();
    let decoded: Box<dyn Identifier> = fory.deserialize(&bytes).unwrap();
    assert_eq!(decoded.value(), 21);

    let identifier: Rc<dyn Identifier> = Rc::new(ExternalId(22));
    let bytes = fory
        .serialize_with::<IdentifierRcSerializer>(&identifier)
        .unwrap();
    let decoded = fory
        .deserialize_with::<IdentifierRcSerializer>(&bytes)
        .unwrap();
    assert_eq!(decoded.value(), 22);

    let identifier: Arc<dyn Identifier> = Arc::new(ExternalId(23));
    let bytes = fory
        .serialize_with::<IdentifierArcSerializer>(&identifier)
        .unwrap();
    let decoded = fory
        .deserialize_with::<IdentifierArcSerializer>(&bytes)
        .unwrap();
    assert_eq!(decoded.value(), 23);
}

#[test]
fn native_enum_xlang_rejection() {
    let mut fory = Fory::builder().xlang(true).compatible(false).build();
    assert!(fory.register::<CommandSerializer>(500).is_err());
    fory.register::<UserSerializer>(500).unwrap();
}

#[test]
fn registration_conflicts_are_atomic() {
    let mut fory = Fory::builder().xlang(false).compatible(false).build();
    fory.register::<UserSerializer>(600).unwrap();
    assert!(fory.register::<UserSerializer>(601).is_err());

    #[derive(ForyStruct)]
    #[fory(target = User)]
    struct OtherUserSerializer {
        name: String,
        age: u32,
    }

    assert!(fory.register::<OtherUserSerializer>(601).is_err());
    fory.register::<PointSerializer>(601).unwrap();

    assert!(fory.register_union::<UserSerializer>(602).is_err());
    fory.register::<MarkerSerializer>(602).unwrap();

    assert!(fory.register::<ValueSerializer>(603).is_err());
    fory.register_union::<ValueSerializer>(603).unwrap();

    assert!(fory
        .register_serializer::<VecSerializer<UserSerializer>>(604)
        .is_err());
    fory.register_serializer::<PackedUsersSerializer>(604)
        .unwrap();

    let error = fory
        .register_serializer::<BoxSerializer<ExternalIdSerializer>>(605)
        .unwrap_err();
    assert!(error.to_string().contains("transparent wrapper"), "{error}");
    fory.register_serializer::<BoxExternalIdSerializer>(605)
        .unwrap();

    let error = fory
        .register_serializer::<Tuple2Serializer<String, UserSerializer>>(606)
        .unwrap_err();
    assert!(error.to_string().contains("declares LIST"), "{error}");
    assert!(!error.to_string().contains("wrapper"), "{error}");
    fory.register_serializer::<PackedEntrySerializer>(606)
        .unwrap();

    let error = fory
        .register_serializer::<OptionSerializer<ExternalIdSerializer>>(607)
        .unwrap_err();
    assert!(error.to_string().contains("transparent wrapper"), "{error}");
    fory.register_serializer::<ExternalIdSerializer>(607)
        .unwrap();
    roundtrip::<BoxExternalIdSerializer>(&fory, &Box::new(ExternalId(9)));
    roundtrip::<PackedEntrySerializer>(&fory, &("entry".to_string(), user("Ada", 37)));

    let mut names = Fory::builder().xlang(false).compatible(false).build();
    names
        .register_by_name::<UserSerializer>("example.Value")
        .unwrap();
    assert!(names
        .register_by_name::<PointSerializer>("example.Value")
        .is_err());
    names
        .register_by_name::<PointSerializer>("example.Point")
        .unwrap();

    let mut mismatch = Fory::builder().xlang(false).compatible(false).build();
    mismatch.register::<OtherUserSerializer>(605).unwrap();
    assert!(mismatch
        .serialize_with::<UserSerializer>(&user("Ada", 37))
        .is_err());
}

#[test]
fn carrier_errors_preserve_safety() {
    let mut fory = Fory::builder()
        .xlang(false)
        .compatible(false)
        .max_graph_memory_bytes(64)
        .build();
    fory.register::<UserSerializer>(700).unwrap();
    let users = vec![user("a", 1), user("b", 2), user("c", 3)];
    let bytes = fory
        .serialize_with::<VecSerializer<UserSerializer>>(&users)
        .unwrap();
    assert!(fory
        .deserialize_with::<VecSerializer<UserSerializer>>(&bytes)
        .is_err());

    let normal = configured_fory();
    let mut bytes = normal
        .serialize_with::<VecSerializer<UserSerializer>>(&users)
        .unwrap();
    bytes.truncate(bytes.len() - 1);
    assert!(normal
        .deserialize_with::<VecSerializer<UserSerializer>>(&bytes)
        .is_err());

    let cell = RefCell::new(user("borrowed", 1));
    let _borrow = cell.borrow_mut();
    assert!(normal
        .serialize_with::<RefCellSerializer<UserSerializer>>(&cell)
        .is_err());

    let mutex = Mutex::new(user("poisoned", 1));
    let _ = std::panic::catch_unwind(|| {
        let _guard = mutex.lock().unwrap();
        panic!("poison");
    });
    assert!(normal
        .serialize_with::<MutexSerializer<UserSerializer>>(&mutex)
        .is_err());
}

#[test]
fn tuple_arity_twenty_two() {
    type Serializer22 = Tuple22Serializer<
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
    let value = (
        0,
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
        user("Ada", 37),
    );
    roundtrip_tuple22::<Serializer22>(&configured_fory(), &value);
}
