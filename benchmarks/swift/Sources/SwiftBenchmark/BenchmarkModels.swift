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

import Fory
import Foundation

enum DataKind: String, CaseIterable {
    case numericStruct = "struct"
    case sample = "sample"
    case mediaContent = "mediacontent"
    case structList = "structlist"
    case sampleList = "samplelist"
    case mediaContentList = "mediacontentlist"

    var title: String {
        switch self {
        case .numericStruct:
            return "Struct"
        case .sample:
            return "Sample"
        case .mediaContent:
            return "MediaContent"
        case .structList:
            return "StructList"
        case .sampleList:
            return "SampleList"
        case .mediaContentList:
            return "MediaContentList"
        }
    }
}

enum SerializerKind: String, CaseIterable {
    case fory
    case protobuf
    case msgpack

    var title: String {
        rawValue.capitalized
    }
}

enum OperationKind: String, CaseIterable {
    case serialize
    case deserialize

    var title: String {
        rawValue.capitalized
    }
}

@ForyObject
struct NumericStruct: Codable, Equatable {
    var f1: Int32 = 0
    var f2: Int32 = 0
    var f3: Int32 = 0
    var f4: Int32 = 0
    var f5: Int32 = 0
    var f6: Int32 = 0
    var f7: Int32 = 0
    var f8: Int32 = 0
}

@ForyObject
struct Sample: Codable, Equatable {
    var intValue: Int32 = 0
    var longValue: Int64 = 0
    var floatValue: Float = 0
    var doubleValue: Double = 0
    var shortValue: Int32 = 0
    var charValue: Int32 = 0
    var booleanValue: Bool = false
    var intValueBoxed: Int32 = 0
    var longValueBoxed: Int64 = 0
    var floatValueBoxed: Float = 0
    var doubleValueBoxed: Double = 0
    var shortValueBoxed: Int32 = 0
    var charValueBoxed: Int32 = 0
    var booleanValueBoxed: Bool = false
    var intArray: [Int32] = []
    var longArray: [Int64] = []
    var floatArray: [Float] = []
    var doubleArray: [Double] = []
    var shortArray: [Int32] = []
    var charArray: [Int32] = []
    var booleanArray: [Bool] = []
    var string: String = ""
}

@ForyObject
enum Player: Int32, Codable, Equatable {
    case java = 0
    case flash = 1
}

@ForyObject
enum Size: Int32, Codable, Equatable {
    case small = 0
    case large = 1
}

@ForyObject
struct Media: Codable, Equatable {
    var uri: String = ""
    var title: String = ""
    var width: Int32 = 0
    var height: Int32 = 0
    var format: String = ""
    var duration: Int64 = 0
    var size: Int64 = 0
    var bitrate: Int32 = 0
    var hasBitrate: Bool = false
    var persons: [String] = []
    var player: Player = .java
    var copyright: String = ""
}

@ForyObject
struct Image: Codable, Equatable {
    var uri: String = ""
    var title: String = ""
    var width: Int32 = 0
    var height: Int32 = 0
    var size: Size = .small
}

@ForyObject
struct MediaContent: Codable, Equatable {
    var media: Media = .init()
    var images: [Image] = []
}

@ForyObject
struct StructList: Codable, Equatable {
    var structList: [NumericStruct] = []
}

@ForyObject
struct SampleList: Codable, Equatable {
    var sampleList: [Sample] = []
}

@ForyObject
struct MediaContentList: Codable, Equatable {
    var mediaContentList: [MediaContent] = []
}

enum BenchmarkDataFactory {
    static let listSize = 5

    static func makeNumericStruct() -> NumericStruct {
        NumericStruct(
            f1: -12345,
            f2: 987_654_321,
            f3: -31_415,
            f4: 27_182_818,
            f5: -32_000,
            f6: 1_000_000,
            f7: -999_999_999,
            f8: 42
        )
    }

    static func makeSample() -> Sample {
        Sample(
            intValue: 123,
            longValue: 1_230_000,
            floatValue: 12.345,
            doubleValue: 1.234_567,
            shortValue: 12_345,
            charValue: 33,
            booleanValue: true,
            intValueBoxed: 321,
            longValueBoxed: 3_210_000,
            floatValueBoxed: 54.321,
            doubleValueBoxed: 7.654_321,
            shortValueBoxed: 32_100,
            charValueBoxed: 36,
            booleanValueBoxed: false,
            intArray: [-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
            longArray: [-123_400, -12_300, -1200, -100, 0, 100, 1200, 12_300, 123_400],
            floatArray: [-12.34, -12.3, -12.0, -1.0, 0.0, 1.0, 12.0, 12.3, 12.34],
            doubleArray: [-1.234, -1.23, -12.0, -1.0, 0.0, 1.0, 12.0, 1.23, 1.234],
            shortArray: [-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
            charArray: [97, 115, 100, 102, 65, 83, 68, 70],
            booleanArray: [true, false, false, true],
            string: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        )
    }

    static func makeMediaContent() -> MediaContent {
        MediaContent(
            media: Media(
                uri: "http://javaone.com/keynote.ogg",
                title: "",
                width: 641,
                height: 481,
                format: "video/theora\u{1234}",
                duration: 18_000_001,
                size: 58_982_401,
                bitrate: 0,
                hasBitrate: false,
                persons: ["Bill Gates, Jr.", "Steven Jobs"],
                player: .flash,
                copyright: "Copyright (c) 2009, Scooby Dooby Doo"
            ),
            images: [
                Image(
                    uri: "http://javaone.com/keynote_huge.jpg",
                    title: "Javaone Keynote\u{1234}",
                    width: 32_000,
                    height: 24_000,
                    size: .large
                ),
                Image(
                    uri: "http://javaone.com/keynote_large.jpg",
                    title: "",
                    width: 1024,
                    height: 768,
                    size: .large
                ),
                Image(
                    uri: "http://javaone.com/keynote_small.jpg",
                    title: "",
                    width: 320,
                    height: 240,
                    size: .small
                ),
            ]
        )
    }

    static func makeStructList() -> StructList {
        StructList(structList: Array(repeating: makeNumericStruct(), count: listSize))
    }

    static func makeSampleList() -> SampleList {
        SampleList(sampleList: Array(repeating: makeSample(), count: listSize))
    }

    static func makeMediaContentList() -> MediaContentList {
        MediaContentList(mediaContentList: Array(repeating: makeMediaContent(), count: listSize))
    }
}
