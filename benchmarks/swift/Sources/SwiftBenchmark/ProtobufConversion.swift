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

import Foundation
import SwiftBenchmarkProto
import SwiftProtobuf

protocol ProtobufConvertible {
    associatedtype PBType: SwiftProtobuf.Message

    func toProtobuf() -> PBType
    static func fromProtobuf(_ pb: PBType) -> Self
}

extension Player {
    func toProtobuf() -> Protobuf_Player {
        switch self {
        case .java:
            return .java
        case .flash:
            return .flash
        }
    }

    static func fromProtobuf(_ pb: Protobuf_Player) -> Player {
        switch pb {
        case .java:
            return .java
        case .flash:
            return .flash
        default:
            return .java
        }
    }
}

extension Size {
    func toProtobuf() -> Protobuf_Size {
        switch self {
        case .small:
            return .small
        case .large:
            return .large
        }
    }

    static func fromProtobuf(_ pb: Protobuf_Size) -> Size {
        switch pb {
        case .small:
            return .small
        case .large:
            return .large
        default:
            return .small
        }
    }
}

extension NumericStruct: ProtobufConvertible {
    func toProtobuf() -> Protobuf_Struct {
        var pb = Protobuf_Struct()
        pb.f1 = f1
        pb.f2 = f2
        pb.f3 = f3
        pb.f4 = f4
        pb.f5 = f5
        pb.f6 = f6
        pb.f7 = f7
        pb.f8 = f8
        return pb
    }

    static func fromProtobuf(_ pb: Protobuf_Struct) -> NumericStruct {
        NumericStruct(
            f1: pb.f1,
            f2: pb.f2,
            f3: pb.f3,
            f4: pb.f4,
            f5: pb.f5,
            f6: pb.f6,
            f7: pb.f7,
            f8: pb.f8
        )
    }
}

extension Sample: ProtobufConvertible {
    func toProtobuf() -> Protobuf_Sample {
        var pb = Protobuf_Sample()
        pb.intValue = intValue
        pb.longValue = longValue
        pb.floatValue = floatValue
        pb.doubleValue = doubleValue
        pb.shortValue = shortValue
        pb.charValue = charValue
        pb.booleanValue = booleanValue
        pb.intValueBoxed = intValueBoxed
        pb.longValueBoxed = longValueBoxed
        pb.floatValueBoxed = floatValueBoxed
        pb.doubleValueBoxed = doubleValueBoxed
        pb.shortValueBoxed = shortValueBoxed
        pb.charValueBoxed = charValueBoxed
        pb.booleanValueBoxed = booleanValueBoxed
        pb.intArray = intArray
        pb.longArray = longArray
        pb.floatArray = floatArray
        pb.doubleArray = doubleArray
        pb.shortArray = shortArray
        pb.charArray = charArray
        pb.booleanArray = booleanArray
        pb.string = string
        return pb
    }

    static func fromProtobuf(_ pb: Protobuf_Sample) -> Sample {
        Sample(
            intValue: pb.intValue,
            longValue: pb.longValue,
            floatValue: pb.floatValue,
            doubleValue: pb.doubleValue,
            shortValue: pb.shortValue,
            charValue: pb.charValue,
            booleanValue: pb.booleanValue,
            intValueBoxed: pb.intValueBoxed,
            longValueBoxed: pb.longValueBoxed,
            floatValueBoxed: pb.floatValueBoxed,
            doubleValueBoxed: pb.doubleValueBoxed,
            shortValueBoxed: pb.shortValueBoxed,
            charValueBoxed: pb.charValueBoxed,
            booleanValueBoxed: pb.booleanValueBoxed,
            intArray: pb.intArray,
            longArray: pb.longArray,
            floatArray: pb.floatArray,
            doubleArray: pb.doubleArray,
            shortArray: pb.shortArray,
            charArray: pb.charArray,
            booleanArray: pb.booleanArray,
            string: pb.string
        )
    }
}

extension Image: ProtobufConvertible {
    func toProtobuf() -> Protobuf_Image {
        var pb = Protobuf_Image()
        pb.uri = uri
        if !title.isEmpty {
            pb.title = title
        }
        pb.width = width
        pb.height = height
        pb.size = size.toProtobuf()
        return pb
    }

    static func fromProtobuf(_ pb: Protobuf_Image) -> Image {
        Image(
            uri: pb.uri,
            title: pb.title,
            width: pb.width,
            height: pb.height,
            size: Size.fromProtobuf(pb.size)
        )
    }
}

extension Media: ProtobufConvertible {
    func toProtobuf() -> Protobuf_Media {
        var pb = Protobuf_Media()
        pb.uri = uri
        if !title.isEmpty {
            pb.title = title
        }
        pb.width = width
        pb.height = height
        pb.format = format
        pb.duration = duration
        pb.size = size
        pb.bitrate = bitrate
        pb.hasBitrate_p = hasBitrate
        pb.persons = persons
        pb.player = player.toProtobuf()
        pb.copyright = copyright
        return pb
    }

    static func fromProtobuf(_ pb: Protobuf_Media) -> Media {
        Media(
            uri: pb.uri,
            title: pb.title,
            width: pb.width,
            height: pb.height,
            format: pb.format,
            duration: pb.duration,
            size: pb.size,
            bitrate: pb.bitrate,
            hasBitrate: pb.hasBitrate_p,
            persons: pb.persons,
            player: Player.fromProtobuf(pb.player),
            copyright: pb.copyright
        )
    }
}

extension MediaContent: ProtobufConvertible {
    func toProtobuf() -> Protobuf_MediaContent {
        var pb = Protobuf_MediaContent()
        pb.media = media.toProtobuf()
        pb.images = images.map { $0.toProtobuf() }
        return pb
    }

    static func fromProtobuf(_ pb: Protobuf_MediaContent) -> MediaContent {
        MediaContent(
            media: Media.fromProtobuf(pb.media),
            images: pb.images.map { Image.fromProtobuf($0) }
        )
    }
}

extension StructList: ProtobufConvertible {
    func toProtobuf() -> Protobuf_StructList {
        var pb = Protobuf_StructList()
        pb.structList = structList.map { $0.toProtobuf() }
        return pb
    }

    static func fromProtobuf(_ pb: Protobuf_StructList) -> StructList {
        StructList(structList: pb.structList.map { NumericStruct.fromProtobuf($0) })
    }
}

extension SampleList: ProtobufConvertible {
    func toProtobuf() -> Protobuf_SampleList {
        var pb = Protobuf_SampleList()
        pb.sampleList = sampleList.map { $0.toProtobuf() }
        return pb
    }

    static func fromProtobuf(_ pb: Protobuf_SampleList) -> SampleList {
        SampleList(sampleList: pb.sampleList.map { Sample.fromProtobuf($0) })
    }
}

extension MediaContentList: ProtobufConvertible {
    func toProtobuf() -> Protobuf_MediaContentList {
        var pb = Protobuf_MediaContentList()
        pb.mediaContentList = mediaContentList.map { $0.toProtobuf() }
        return pb
    }

    static func fromProtobuf(_ pb: Protobuf_MediaContentList) -> MediaContentList {
        MediaContentList(mediaContentList: pb.mediaContentList.map { MediaContent.fromProtobuf($0) })
    }
}
