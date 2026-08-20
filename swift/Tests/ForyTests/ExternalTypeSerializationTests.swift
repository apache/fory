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

import ForyExternalModels
import Foundation
import Testing
@testable import Fory

// This test target belongs to the package that owns `Serializer`, so Swift
// rejects `@retroactive` here. Downstream applications use the marker when
// both the target type and protocol are imported.
extension AccountID: Serializer {
    public typealias Target = AccountID

    public static var staticTypeId: TypeId { .ext }

    public static func defaultValue(_: ReadContext) throws -> AccountID {
        AccountID(rawValue: 0)
    }

    public static func writeData(
        _ value: AccountID,
        _ context: WriteContext
    ) throws {
        try UInt64.writeData(value.rawValue, context)
    }

    public static func readData(_ context: ReadContext) throws -> AccountID {
        AccountID(rawValue: try UInt64.readData(context))
    }
}

@ForyStruct
private struct AccountHolder: Equatable {
    var primary: AccountID
    var backup: AccountID?
    var accounts: [AccountID]
    var uniqueAccounts: Set<AccountID>
    var aliases: [AccountID: AccountID]
}

@ForyStruct
private struct SelectedAccountHolder: Equatable {
    @ForyField(with: AccountID.self)
    var primary: AccountID

    @ForyField(with: OptionalSerializer<AccountID>.self)
    var backup: AccountID?

    @ForyField(with: ArraySerializer<AccountID>.self)
    var accounts: [AccountID]

    @ForyField(with: SetSerializer<AccountID>.self)
    var uniqueAccounts: Set<AccountID>

    @ForyField(with: DictionarySerializer<AccountID, AccountID>.self)
    var aliases: [AccountID: AccountID]
}

@ForyStruct(target: User.self)
private struct UserSerializer {
    var name: String
    var age: UInt32
}

@ForyStruct(target: Key.self)
private struct KeySerializer {
    var value: String
}

@ForyStruct(target: Group.self)
private struct GroupSerializer {
    @ForyField(with: UserSerializer.self)
    var owner: User

    @ForyField(with: OptionalSerializer<UserSerializer>.self)
    var backup: User?

    @ListField(element: .with(UserSerializer.self))
    var users: [User]

    @SetField(element: .with(KeySerializer.self))
    var keys: Set<Key>

    @MapField(key: .with(KeySerializer.self), value: .with(UserSerializer.self))
    var usersByKey: [Key: User]

    @MapField(value: .list(element: .with(OptionalSerializer<UserSerializer>.self)))
    var groupedUsers: [String: [User?]]
}

@ForyStruct
private struct CarrierSelections: Equatable {
    @MapField(value: .with(UserSerializer.self))
    var usersByName: [String: User]

    @MapField(key: .with(KeySerializer.self))
    var labelsByKey: [Key: String]

    @ForyField(with: ArraySerializer<UserSerializer>.self)
    var wholeUsers: [User]
}

@ForyStruct(target: Node.self)
private final class NodeSerializer {
    @ForyField(ignore: false)
    var value: Int32 = 0

    @ForyField(with: OptionalSerializer<NodeSerializer>.self)
    var next: Node?

    @ForyField(ignore: true)
    var omittedState: (UInt64, UInt64) = (0, 0)
}

@ForyStruct(target: Node.self)
private final class NodeValueSerializer {
    var value: Int32 = 0
}

@ForyEnum(target: Status.self)
private enum StatusSerializer {
    case active
    case disabled
}

@ForyUnion(target: Command<UnknownCase>.self)
private enum CommandSerializer {
    @ForyUnknownCase
    case unknown(UnknownCase)

    @ForyCase(id: 0)
    case rename(String)

    @ForyCase(id: 1, payload: .with(UserSerializer.self))
    case replace(User)
}

@ForyStruct
private struct LocalUser: Equatable {
    var name: String
    var age: UInt32
}

@ForyStruct
private final class LocalNode {
    var value: Int32 = 0
    var next: LocalNode?

    required init() {}
}

@ForyStruct
private struct LocalNamedValue: NamedValue, Equatable {
    var name: String
    var age: UInt32
}

@ForyStruct
private struct NamedValueHolder {
    var featured: any NamedValue
    var backup: (any NamedValue)?
    var values: [any NamedValue]
}

private protocol LinkedValue: AnyObject {
    var value: Int32 { get }
}

extension Node: LinkedValue {}

@ForyStruct(target: User.self)
private struct AlternateUserSerializer {
    var name: String
    var age: UInt32
}

@ForyStruct(target: Profile.self)
private struct ProfileV1Serializer {
    var name: String
    var age: UInt32
}

@ForyStruct(target: Profile.self)
private struct ProfileV2Serializer {
    var name: String
    var age: UInt32
    var email: String
}

@ForyStruct
private struct ProfileOptionalsV1 {
    @ListField(element: .with(OptionalSerializer<ProfileV1Serializer>.self))
    var values: [Profile?]
}

@ForyStruct
private struct ProfileOptionalsV2 {
    @ListField(element: .with(OptionalSerializer<ProfileV2Serializer>.self))
    var values: [Profile?]
}

@ForyStruct(target: Node.self)
private struct ValueNodeSerializer {
    var value: Int32

    @ForyField(with: OptionalSerializer<NodeSerializer>.self)
    var next: Node?
}

private enum CustomUserSerializer: Serializer {
    typealias Target = CustomUser

    static var staticTypeId: TypeId { .ext }

    static func defaultValue(_: ReadContext) throws -> Target {
        Target(name: "", age: 0)
    }

    static func writeData(_ value: Target, _ context: WriteContext) throws {
        try String.writeData(value.name, context)
        try UInt32.writeData(value.age, context)
    }

    static func readData(_ context: ReadContext) throws -> Target {
        Target(
            name: try String.readData(context),
            age: try UInt32.readData(context)
        )
    }
}

private enum UserArrayCustomSerializer: Serializer {
    typealias Target = [User]

    static var staticTypeId: TypeId { .ext }

    static func defaultValue(_: ReadContext) throws -> Target { [] }

    static func writeData(_ value: Target, _ context: WriteContext) throws {
        try ArraySerializer<UserSerializer>.writeData(value, context)
    }

    static func readData(_ context: ReadContext) throws -> Target {
        try ArraySerializer<UserSerializer>.readData(context)
    }
}

private enum StringCustomSerializer: Serializer {
    typealias Target = String

    static var staticTypeId: TypeId { .ext }

    static func defaultValue(_: ReadContext) throws -> String { "" }

    static func writeData(_ value: String, _ context: WriteContext) throws {
        try String.writeData(value, context)
    }

    static func readData(_ context: ReadContext) throws -> String {
        try String.readData(context)
    }
}

@ForyStruct
private struct CustomUserHolder: Equatable {
    @ForyField(with: CustomUserSerializer.self)
    var user: CustomUser
}

private typealias AliasedUserGroups = [String: [User?]]

@ForyStruct
private struct AliasedSelectionHolder: Equatable {
    @ForyField(
        type: .map(
            key: .string,
            value: .list(
                element: .with(OptionalSerializer<UserSerializer>.self)
            )
        )
    )
    var groups: AliasedUserGroups
}

private typealias HiddenUserArraySerializer = ArraySerializer<UserSerializer>

@ForyStruct
private struct HiddenCarrierHolder {
    @ForyField(with: HiddenUserArraySerializer.self)
    var users: [User]
}

private func makeGroup() -> Group {
    let first = User(name: "Alice", age: 31)
    let second = User(name: "Bob", age: 29)
    let key = Key(value: "primary")
    return Group(
        owner: first,
        backup: second,
        users: [first, second],
        keys: [key],
        usersByKey: [key: first],
        groupedUsers: ["team": [first, nil, second]]
    )
}

@Test
func retroactiveSerializerRoots() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(AccountID.self, id: 140)

    let primary = AccountID(rawValue: 7)
    let secondary = AccountID(rawValue: 9)

    let directBytes = try fory.serialize(primary)
    let selectedDirectBytes = try fory.serialize(
        primary,
        with: AccountID.self
    )
    #expect(directBytes == selectedDirectBytes)
    let decodedDirect: AccountID = try fory.deserialize(directBytes)
    #expect(decodedDirect == primary)

    let optional: AccountID? = secondary
    let optionalBytes = try fory.serialize(optional)
    let selectedOptionalBytes = try fory.serialize(
        optional,
        with: OptionalSerializer<AccountID>.self
    )
    #expect(optionalBytes == selectedOptionalBytes)
    let decodedOptional: AccountID? = try fory.deserialize(optionalBytes)
    #expect(decodedOptional == optional)

    let accounts = [primary, secondary]
    let arrayBytes = try fory.serialize(accounts)
    let selectedArrayBytes = try fory.serialize(
        accounts,
        with: ArraySerializer<AccountID>.self
    )
    #expect(arrayBytes == selectedArrayBytes)
    let decodedAccounts: [AccountID] = try fory.deserialize(arrayBytes)
    #expect(decodedAccounts == accounts)

    let uniqueAccounts: Set<AccountID> = [primary, secondary]
    let setBytes = try fory.serialize(uniqueAccounts)
    let selectedSetBytes = try fory.serialize(
        uniqueAccounts,
        with: SetSerializer<AccountID>.self
    )
    #expect(setBytes == selectedSetBytes)
    let decodedSet: Set<AccountID> = try fory.deserialize(setBytes)
    #expect(decodedSet == uniqueAccounts)

    let aliases = [primary: secondary]
    let mapBytes = try fory.serialize(aliases)
    let selectedMapBytes = try fory.serialize(
        aliases,
        with: DictionarySerializer<AccountID, AccountID>.self
    )
    #expect(mapBytes == selectedMapBytes)
    let decodedAliases: [AccountID: AccountID] = try fory.deserialize(mapBytes)
    #expect(decodedAliases == aliases)
}

@Test
func retroactiveSerializerFields() throws {
    let primary = AccountID(rawValue: 7)
    let secondary = AccountID(rawValue: 9)
    let implicitValue = AccountHolder(
        primary: primary,
        backup: secondary,
        accounts: [primary, secondary],
        uniqueAccounts: [primary, secondary],
        aliases: [primary: secondary]
    )
    let selectedValue = SelectedAccountHolder(
        primary: implicitValue.primary,
        backup: implicitValue.backup,
        accounts: implicitValue.accounts,
        uniqueAccounts: implicitValue.uniqueAccounts,
        aliases: implicitValue.aliases
    )

    let implicit = Fory(config: .init(trackRef: false, compatible: true))
    try implicit.register(AccountID.self, id: 140)
    try implicit.register(AccountHolder.self, id: 141)

    let selected = Fory(config: .init(trackRef: false, compatible: true))
    try selected.register(AccountID.self, id: 140)
    try selected.register(SelectedAccountHolder.self, id: 141)

    #expect(
        AccountHolder.foryFieldsInfo(trackRef: false)
            == SelectedAccountHolder.foryFieldsInfo(trackRef: false)
    )

    let implicitBytes = try implicit.serialize(implicitValue)
    let selectedBytes = try selected.serialize(selectedValue)
    #expect(implicitBytes == selectedBytes)

    let decoded: AccountHolder = try implicit.deserialize(implicitBytes)
    #expect(decoded == implicitValue)
}

@Test
func externalStructRootRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 100)

    let value = User(name: "Alice", age: 31)
    let data = try fory.serialize(value, with: UserSerializer.self)
    let decoded = try fory.deserialize(data, with: UserSerializer.self)
    #expect(decoded == value)
}

@Test
func externalNameRegistration() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: true))
    try fory.register(UserSerializer.self, name: "example.User")

    let value = User(name: "Alice", age: 31)
    let decoded = try fory.deserialize(
        fory.serialize(value, with: UserSerializer.self),
        with: UserSerializer.self
    )
    #expect(decoded == value)
}

@Test
func externalFieldsAndCarriers() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: true))
    try fory.register(UserSerializer.self, id: 101)
    try fory.register(KeySerializer.self, id: 102)
    try fory.register(GroupSerializer.self, id: 103)

    let value = makeGroup()
    let decoded = try fory.deserialize(
        fory.serialize(value, with: GroupSerializer.self),
        with: GroupSerializer.self
    )
    #expect(decoded == value)
}

@Test
func wholeCarrierAndMapSelection() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 104)
    try fory.register(KeySerializer.self, id: 105)
    try fory.register(CarrierSelections.self, id: 106)

    let user = User(name: "Alice", age: 31)
    let key = Key(value: "primary")
    let value = CarrierSelections(
        usersByName: ["alice": user],
        labelsByKey: [key: "owner"],
        wholeUsers: [user]
    )
    let decoded: CarrierSelections = try fory.deserialize(try fory.serialize(value))
    #expect(decoded == value)
}

@Test
func externalClassCycleRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(NodeSerializer.self, id: 107)

    let value = Node()
    value.value = 42
    value.next = value

    let decoded = try fory.deserialize(
        fory.serialize(value, with: NodeSerializer.self),
        with: NodeSerializer.self
    )
    #expect(decoded.value == 42)
    #expect(decoded.next === decoded)
}

@Test
func externalIgnoredFieldBudget() throws {
    let value = Node()
    value.value = 42
    let required =
        2 * MemoryLayout<Int>.stride
        + MemoryLayout<Int32>.stride
        + 4
        + MemoryLayout<(UInt64, UInt64)>.stride

    for compatible in [false, true] {
        let writer = Fory(
            config: .init(
                trackRef: false,
                compatible: compatible
            )
        )
        let bytes: Data
        if compatible {
            try writer.register(NodeValueSerializer.self, id: 142)
            bytes = try writer.serialize(value, with: NodeValueSerializer.self)
        } else {
            try writer.register(NodeSerializer.self, id: 142)
            bytes = try writer.serialize(value, with: NodeSerializer.self)
        }

        let limited = Fory(
            config: .init(
                trackRef: false,
                compatible: compatible,
                maxGraphMemoryBytes: Int64(required - 1)
            )
        )
        try limited.register(NodeSerializer.self, id: 142)
        #expect(throws: ForyError.self) {
            let _: Node = try limited.deserialize(
                bytes,
                with: NodeSerializer.self
            )
        }

        let exact = Fory(
            config: .init(
                trackRef: false,
                compatible: compatible,
                maxGraphMemoryBytes: Int64(required)
            )
        )
        try exact.register(NodeSerializer.self, id: 142)
        let decoded: Node = try exact.deserialize(
            bytes,
            with: NodeSerializer.self
        )
        #expect(decoded.value == value.value)
        #expect(decoded.next == nil)
    }
}

@Test
func externalEnumAndUnionRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 108)
    try fory.register(StatusSerializer.self, id: 109)
    try fory.register(CommandSerializer.self, id: 110)

    let status = try fory.deserialize(
        fory.serialize(Status.disabled, with: StatusSerializer.self),
        with: StatusSerializer.self
    )
    #expect(status == .disabled)

    let command: Command<UnknownCase> = .replace(User(name: "Alice", age: 31))
    let decoded = try fory.deserialize(
        fory.serialize(command, with: CommandSerializer.self),
        with: CommandSerializer.self
    )
    #expect(decoded == command)
}

@Test
func rootCarrierCompositionRoundTrip() throws {
    typealias Serializer = DictionarySerializer<
        String,
        ArraySerializer<OptionalSerializer<UserSerializer>>
    >

    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 111)
    let value: [String: [User?]] = [
        "team": [User(name: "Alice", age: 31), nil]
    ]

    let decoded = try fory.deserialize(
        fory.serialize(value, with: Serializer.self),
        with: Serializer.self
    )
    #expect(decoded == value)
}

@Test
func externalSchemaBytesMatch() throws {
    let external = Fory(config: .init(trackRef: false, compatible: false))
    let ordinary = Fory(config: .init(trackRef: false, compatible: false))
    try external.register(UserSerializer.self, id: 112)
    try ordinary.register(LocalUser.self, id: 112)

    let externalBytes = try external.serialize(
        User(name: "Alice", age: 31),
        with: UserSerializer.self
    )
    let ordinaryBytes = try ordinary.serialize(LocalUser(name: "Alice", age: 31))
    #expect(externalBytes == ordinaryBytes)
}

@Test
func externalMetadataMatchesOrdinary() throws {
    let external = Fory(config: .init(trackRef: false, compatible: true))
    let ordinary = Fory(config: .init(trackRef: false, compatible: true))
    try external.register(UserSerializer.self, id: 70)
    try ordinary.register(LocalUser.self, id: 70)

    let externalBytes = try external.serialize(
        User(name: "Alice", age: 31),
        with: UserSerializer.self
    )
    let ordinaryBytes = try ordinary.serialize(LocalUser(name: "Alice", age: 31))
    let externalInfo = try external.typeResolver.requireTypeInfo(for: UserSerializer.self)
    let ordinaryInfo = try ordinary.typeResolver.requireTypeInfo(for: LocalUser.self)

    #expect(externalBytes == ordinaryBytes)
    #expect(externalInfo.typeDefBytes == ordinaryInfo.typeDefBytes)
    #expect(
        UserSerializer.foryFieldsInfo(trackRef: false)
            == LocalUser.foryFieldsInfo(trackRef: false)
    )
}

@Test
func externalReferenceShapeMatches() throws {
    let external = Fory(config: .init(trackRef: true, compatible: true))
    let ordinary = Fory(config: .init(trackRef: true, compatible: true))
    try external.register(NodeSerializer.self, id: 71)
    try ordinary.register(LocalNode.self, id: 71)

    let externalValue = Node()
    externalValue.value = 42
    externalValue.next = externalValue
    let ordinaryValue = LocalNode()
    ordinaryValue.value = 42
    ordinaryValue.next = ordinaryValue

    let externalBytes = try external.serialize(
        externalValue,
        with: NodeSerializer.self
    )
    let ordinaryBytes = try ordinary.serialize(ordinaryValue)
    let externalInfo = try external.typeResolver.requireTypeInfo(for: NodeSerializer.self)
    let ordinaryInfo = try ordinary.typeResolver.requireTypeInfo(for: LocalNode.self)
    let externalFields = NodeSerializer.foryFieldsInfo(trackRef: true)
    let ordinaryFields = LocalNode.foryFieldsInfo(trackRef: true)

    #expect(externalBytes == ordinaryBytes)
    #expect(externalInfo.typeDefBytes == ordinaryInfo.typeDefBytes)
    #expect(externalFields == ordinaryFields)

    let externalDecoded = try external.deserialize(
        externalBytes,
        with: NodeSerializer.self
    )
    let ordinaryDecoded: LocalNode = try ordinary.deserialize(ordinaryBytes)
    #expect(externalDecoded.next === externalDecoded)
    #expect(ordinaryDecoded.next === ordinaryDecoded)
}

@Test
func customRootAndField() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(CustomUserSerializer.self, id: 113)
    try fory.register(CustomUserHolder.self, id: 114)

    let value = CustomUser(name: "Alice", age: 31)
    let decoded = try fory.deserialize(
        fory.serialize(value, with: CustomUserSerializer.self),
        with: CustomUserSerializer.self
    )
    #expect(decoded == value)

    let holder = CustomUserHolder(user: value)
    let decodedHolder: CustomUserHolder = try fory.deserialize(try fory.serialize(holder))
    #expect(decodedHolder == holder)
}

@Test
func customWholeCarrierRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 115)
    try fory.register(UserArrayCustomSerializer.self, id: 116)

    let value = [
        User(name: "Alice", age: 31),
        User(name: "Bob", age: 29)
    ]
    let decoded = try fory.deserialize(
        fory.serialize(value, with: UserArrayCustomSerializer.self),
        with: UserArrayCustomSerializer.self
    )
    #expect(decoded == value)
}

@Test
func customCarrierEnforcesBudget() throws {
    let writer = Fory(config: .init(trackRef: false, compatible: false))
    try writer.register(UserSerializer.self, id: 138)
    try writer.register(UserArrayCustomSerializer.self, id: 139)
    let bytes = try writer.serialize(
        [User(name: "Alice", age: 31)],
        with: UserArrayCustomSerializer.self
    )

    let reader = Fory(
        config: .init(
            trackRef: false,
            compatible: false,
            maxGraphMemoryBytes: 1
        )
    )
    try reader.register(UserSerializer.self, id: 138)
    try reader.register(UserArrayCustomSerializer.self, id: 139)
    #expect(throws: ForyError.self) {
        let _: [User] = try reader.deserialize(
            bytes,
            with: UserArrayCustomSerializer.self
        )
    }
}

@Test
func selectedBufferRootsRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 117)
    let value = User(name: "Alice", age: 31)

    var output = Data()
    try fory.serialize(value, with: UserSerializer.self, to: &output)
    let decoded = try fory.deserialize(
        from: ByteBuffer(data: output),
        with: UserSerializer.self
    )
    #expect(decoded == value)
}

@Test
func allCarrierRootsRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 118)
    try fory.register(KeySerializer.self, id: 119)

    let user = User(name: "Alice", age: 31)
    let key = Key(value: "primary")

    let optional = Optional(user)
    #expect(
        try fory.deserialize(
            fory.serialize(optional, with: OptionalSerializer<UserSerializer>.self),
            with: OptionalSerializer<UserSerializer>.self
        ) == optional
    )

    let users = [user]
    #expect(
        try fory.deserialize(
            fory.serialize(users, with: ArraySerializer<UserSerializer>.self),
            with: ArraySerializer<UserSerializer>.self
        ) == users
    )

    let keys = Set([key])
    #expect(
        try fory.deserialize(
            fory.serialize(keys, with: SetSerializer<KeySerializer>.self),
            with: SetSerializer<KeySerializer>.self
        ) == keys
    )

    let usersByKey = [key: user]
    typealias MapSerializer = DictionarySerializer<KeySerializer, UserSerializer>
    #expect(
        try fory.deserialize(
            fory.serialize(usersByKey, with: MapSerializer.self),
            with: MapSerializer.self
        ) == usersByKey
    )
}

@Test
func carrierCompositionRoundTrip() throws {
    typealias NestedSerializer = OptionalSerializer<
        DictionarySerializer<
            String,
            ArraySerializer<SetSerializer<UserSerializer>>
        >
    >

    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 72)
    try fory.register(CustomUserSerializer.self, id: 73)

    let customUsers = [
        CustomUser(name: "Alice", age: 31),
        CustomUser(name: "Bob", age: 29)
    ]
    let decodedCustom = try fory.deserialize(
        fory.serialize(
            customUsers,
            with: ArraySerializer<CustomUserSerializer>.self
        ),
        with: ArraySerializer<CustomUserSerializer>.self
    )
    #expect(decodedCustom == customUsers)

    let nested: [String: [Set<User>]]? = [
        "team": [
            [
                User(name: "Alice", age: 31),
                User(name: "Bob", age: 29)
            ]
        ]
    ]
    let decodedNested = try fory.deserialize(
        fory.serialize(nested, with: NestedSerializer.self),
        with: NestedSerializer.self
    )
    #expect(decodedNested == nested)
}

@Test
func aliasedSelectionRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: true))
    try fory.register(UserSerializer.self, id: 74)
    try fory.register(AliasedSelectionHolder.self, id: 75)

    let value = AliasedSelectionHolder(
        groups: [
            "team": [
                User(name: "Alice", age: 31),
                nil
            ]
        ]
    )
    let decoded: AliasedSelectionHolder = try fory.deserialize(
        fory.serialize(value)
    )
    #expect(decoded == value)
}

@Test
func emptyCarriersAreLazy() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))

    let optional: User? = nil
    #expect(
        try fory.deserialize(
            fory.serialize(optional, with: OptionalSerializer<UserSerializer>.self),
            with: OptionalSerializer<UserSerializer>.self
        ) == nil
    )

    let users: [User] = []
    #expect(
        try fory.deserialize(
            fory.serialize(users, with: ArraySerializer<UserSerializer>.self),
            with: ArraySerializer<UserSerializer>.self
        ).isEmpty
    )

    let usersSet: Set<User> = []
    #expect(
        try fory.deserialize(
            fory.serialize(usersSet, with: SetSerializer<UserSerializer>.self),
            with: SetSerializer<UserSerializer>.self
        ).isEmpty
    )

    let usersByName: [String: User] = [:]
    #expect(
        try fory.deserialize(
            fory.serialize(
                usersByName,
                with: DictionarySerializer<String, UserSerializer>.self
            ),
            with: DictionarySerializer<String, UserSerializer>.self
        ).isEmpty
    )
}

@Test
func protocolRootAndCarrier() throws {
    typealias ValueSerializer = DynamicSerializer<any NamedValue>
    typealias ValuesSerializer = ArraySerializer<ValueSerializer>

    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(LocalNamedValue.self, id: 120)
    try fory.register(UserSerializer.self, id: 121)
    try fory.register(CustomUserSerializer.self, id: 122)

    let values: [any NamedValue] = [
        LocalNamedValue(name: "local", age: 1),
        User(name: "external", age: 2),
        CustomUser(name: "custom", age: 3)
    ]
    let decoded = try fory.deserialize(
        fory.serialize(values, with: ValuesSerializer.self),
        with: ValuesSerializer.self
    )

    #expect(decoded.count == 3)
    #expect(decoded[0] as? LocalNamedValue == LocalNamedValue(name: "local", age: 1))
    #expect(decoded[1] as? User == User(name: "external", age: 2))
    #expect(decoded[2] as? CustomUser == CustomUser(name: "custom", age: 3))
}

@Test
func protocolFieldAndRoot() throws {
    typealias ValueSerializer = DynamicSerializer<any NamedValue>

    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(LocalNamedValue.self, id: 132)
    try fory.register(UserSerializer.self, id: 133)
    try fory.register(NamedValueHolder.self, id: 134)

    let featured: any NamedValue = User(name: "external", age: 2)
    let decodedRoot = try fory.deserialize(
        fory.serialize(featured, with: ValueSerializer.self),
        with: ValueSerializer.self
    )
    #expect(decodedRoot as? User == User(name: "external", age: 2))

    let optional: (any NamedValue)? = featured
    let decodedOptional = try fory.deserialize(
        fory.serialize(
            optional,
            with: OptionalSerializer<ValueSerializer>.self
        ),
        with: OptionalSerializer<ValueSerializer>.self
    )
    #expect(decodedOptional as? User == User(name: "external", age: 2))

    let valuesByName: [String: any NamedValue] = ["external": featured]
    let decodedMap = try fory.deserialize(
        fory.serialize(
            valuesByName,
            with: DictionarySerializer<String, ValueSerializer>.self
        ),
        with: DictionarySerializer<String, ValueSerializer>.self
    )
    #expect(decodedMap["external"] as? User == User(name: "external", age: 2))

    let holder = NamedValueHolder(
        featured: featured,
        backup: LocalNamedValue(name: "backup", age: 3),
        values: [
            LocalNamedValue(name: "local", age: 1),
            featured
        ]
    )
    let decodedHolder: NamedValueHolder = try fory.deserialize(
        fory.serialize(holder)
    )
    #expect(decodedHolder.featured as? User == User(name: "external", age: 2))
    #expect(
        decodedHolder.backup as? LocalNamedValue
            == LocalNamedValue(name: "backup", age: 3)
    )
    #expect(
        decodedHolder.values[0] as? LocalNamedValue
            == LocalNamedValue(name: "local", age: 1)
    )
    #expect(decodedHolder.values[1] as? User == User(name: "external", age: 2))
}

@Test
func protocolClassCycle() throws {
    typealias ValueSerializer = DynamicSerializer<any LinkedValue>

    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(NodeSerializer.self, id: 137)

    let node = Node()
    node.value = 42
    node.next = node
    let value: any LinkedValue = node
    let decoded = try fory.deserialize(
        fory.serialize(value, with: ValueSerializer.self),
        with: ValueSerializer.self
    )

    let decodedNode = try #require(decoded as? Node)
    #expect(decodedNode.value == 42)
    #expect(decodedNode.next === decodedNode)
}

@Test
func externalCompatibleSchemasRoundTrip() throws {
    let v1 = Fory(config: .init(trackRef: false, compatible: true))
    let v2 = Fory(config: .init(trackRef: false, compatible: true))
    try v1.register(ProfileV1Serializer.self, id: 123)
    try v2.register(ProfileV2Serializer.self, id: 123)

    let old = Profile(name: "Alice", age: 31)
    let readByV2 = try v2.deserialize(
        v1.serialize(old, with: ProfileV1Serializer.self),
        with: ProfileV2Serializer.self
    )
    #expect(readByV2 == old)

    let current = Profile(name: "Bob", age: 29, email: "bob@example.com")
    let readByV1 = try v1.deserialize(
        v2.serialize(current, with: ProfileV2Serializer.self),
        with: ProfileV1Serializer.self
    )
    #expect(readByV1 == Profile(name: "Bob", age: 29))
}

@Test
func externalOptionalFieldEvolves() throws {
    let writer = Fory(config: .init(trackRef: false, compatible: true))
    let reader = Fory(config: .init(trackRef: false, compatible: true))
    try writer.register(ProfileV1Serializer.self, id: 135)
    try reader.register(ProfileV2Serializer.self, id: 135)
    try writer.register(ProfileOptionalsV1.self, id: 136)
    try reader.register(ProfileOptionalsV2.self, id: 136)

    let value = ProfileOptionalsV1(
        values: [Profile(name: "Alice", age: 31)]
    )
    let decoded: ProfileOptionalsV2 = try reader.deserialize(
        writer.serialize(value)
    )
    #expect(decoded.values == value.values)
}

@Test
func externalUnionPreservesUnknownCase() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 124)
    try fory.register(CommandSerializer.self, id: 125)

    let value: Command<UnknownCase> = .unknown(
        UnknownCase(caseId: 77, value: Int32(9))
    )
    let decoded = try fory.deserialize(
        fory.serialize(value, with: CommandSerializer.self),
        with: CommandSerializer.self
    )

    guard case .unknown(let unknown) = decoded else {
        Issue.record("expected unknown union case")
        return
    }
    #expect(unknown.caseId == 77)
    #expect(unknown.value as? Int32 == 9)
}

@Test
func directExternalDynamicRoot() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(UserSerializer.self, id: 126)

    let value = User(name: "dynamic", age: 7)
    let directData = try fory.serialize(value)
    let dynamicValue: Any = value
    let explicitData = try fory.serialize(
        dynamicValue,
        with: DynamicSerializer<Any>.self
    )
    #expect(directData == explicitData)

    let decoded: Any = try fory.deserialize(directData)
    #expect(decoded as? User == value)
}

@Test
func dynamicTargetFailures() throws {
    let unregistered = Fory()
    let unregisteredValue: Any = User(name: "unregistered", age: 1)
    #expect(throws: ForyError.self) {
        _ = try unregistered.serialize(
            unregisteredValue,
            with: DynamicSerializer<Any>.self
        )
    }

    let fory = Fory()
    try fory.register(KeySerializer.self, id: 76)
    let value: Any = Key(value: "not-named")
    let bytes = try fory.serialize(value, with: DynamicSerializer<Any>.self)

    #expect(throws: ForyError.self) {
        let _: any NamedValue = try fory.deserialize(
            bytes,
            with: DynamicSerializer<any NamedValue>.self
        )
    }
    #expect(throws: ForyError.self) {
        let _: AnyObject = try fory.deserialize(
            bytes,
            with: DynamicSerializer<AnyObject>.self
        )
    }
}

@Test
func dynamicReferenceEnvelopeBytes() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(NodeSerializer.self, id: 77)

    let value: AnyObject = Node()
    let bytes = Array(
        try fory.serialize(
            value,
            with: DynamicSerializer<AnyObject>.self
        )
    )

    #expect(
        Array(bytes.prefix(5))
            == [
                ForyHeaderFlag.isXlang,
                UInt8(bitPattern: RefFlag.notNullValue.rawValue),
                UInt8(truncatingIfNeeded: TypeId.structType.rawValue),
                77,
                UInt8(bitPattern: RefFlag.refValue.rawValue)
            ]
    )
}

@Test
func registrationRejectsInvalidOwnership() throws {
    let carrier = Fory()
    #expect(throws: ForyError.self) {
        try carrier.register(ArraySerializer<UserSerializer>.self, id: 126)
    }

    let wrapper = Fory()
    #expect(throws: ForyError.self) {
        try wrapper.register(OptionalSerializer<UserSerializer>.self, id: 132)
    }

    let dynamic = Fory()
    #expect(throws: ForyError.self) {
        try dynamic.register(DynamicSerializer<Any>.self, id: 133)
    }

    let duplicateTarget = Fory()
    try duplicateTarget.register(UserSerializer.self, id: 127)
    #expect(throws: ForyError.self) {
        try duplicateTarget.register(AlternateUserSerializer.self, id: 128)
    }

    let builtinTarget = Fory()
    #expect(throws: ForyError.self) {
        try builtinTarget.register(StringCustomSerializer.self, id: 129)
    }

    let valueDeclaration = Fory()
    #expect(throws: ForyError.self) {
        try valueDeclaration.register(ValueNodeSerializer.self, id: 130)
    }
}

@Test
func hiddenCarrierAliasIsRejected() throws {
    let fory = Fory()
    try fory.register(UserSerializer.self, id: 78)
    try fory.register(HiddenCarrierHolder.self, id: 79)

    #expect(throws: ForyError.self) {
        _ = try fory.serialize(HiddenCarrierHolder(users: []))
    }
}

@Test
func numericIDConflictIsAtomic() throws {
    let fory = Fory()
    try fory.register(UserSerializer.self, id: 80)
    #expect(throws: ForyError.self) {
        try fory.register(KeySerializer.self, id: 80)
    }
    try fory.register(KeySerializer.self, id: 81)

    let user = User(name: "Alice", age: 31)
    let decodedUser = try fory.deserialize(
        fory.serialize(user, with: UserSerializer.self),
        with: UserSerializer.self
    )
    let key = Key(value: "primary")
    let decodedKey = try fory.deserialize(
        fory.serialize(key, with: KeySerializer.self),
        with: KeySerializer.self
    )
    #expect(decodedUser == user)
    #expect(decodedKey == key)
}

@Test
func registrationFreezesAtFirstRoot() throws {
    let fory = Fory()
    _ = try fory.serialize(Int32(1))
    #expect(throws: ForyError.self) {
        try fory.register(UserSerializer.self, id: 131)
    }
}
