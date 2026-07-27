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

public protocol NamedValue {
    var name: String { get }
}

public struct User: Equatable, Hashable, NamedValue {
    public var name: String
    public var age: UInt32

    public init(name: String, age: UInt32) {
        self.name = name
        self.age = age
    }
}

public struct ManualUser: Equatable, Hashable, NamedValue {
    public var name: String
    public var age: UInt32

    public init(name: String, age: UInt32) {
        self.name = name
        self.age = age
    }
}

public struct AccountID: Equatable, Hashable {
    public var rawValue: UInt64

    public init(rawValue: UInt64) {
        self.rawValue = rawValue
    }
}

public struct Profile: Equatable {
    public var name: String
    public var age: UInt32
    public var email: String

    public init(name: String, age: UInt32, email: String = "") {
        self.name = name
        self.age = age
        self.email = email
    }
}

public struct Key: Equatable, Hashable {
    public var value: String

    public init(value: String) {
        self.value = value
    }
}

public struct Group: Equatable {
    public var owner: User
    public var backup: User?
    public var users: [User]
    public var keys: Set<Key>
    public var usersByKey: [Key: User]
    public var groupedUsers: [String: [User?]]

    public init(
        owner: User,
        backup: User?,
        users: [User],
        keys: Set<Key>,
        usersByKey: [Key: User],
        groupedUsers: [String: [User?]]
    ) {
        self.owner = owner
        self.backup = backup
        self.users = users
        self.keys = keys
        self.usersByKey = usersByKey
        self.groupedUsers = groupedUsers
    }
}

public final class Node {
    public var value: Int32 = 0
    public var next: Node?

    public init() {}
}

@frozen
public enum Status: Equatable {
    case active
    case disabled
}

@frozen
public enum Command<UnknownPayload: Equatable>: Equatable {
    case unknown(UnknownPayload)
    case rename(String)
    case replace(User)
}
