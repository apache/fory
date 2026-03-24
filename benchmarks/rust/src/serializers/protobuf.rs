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

use crate::data::{
    Image, Media, MediaContent, MediaContentList, NumericStruct, Player, Sample, SampleList, Size,
    StructList,
};
use crate::generated as pb;
use crate::serializers::{BenchmarkSerializer, BoxError};
use prost::Message;

pub trait ProtobufModel: Sized {
    type Proto: Message + Default;

    fn to_proto(&self) -> Self::Proto;
    fn from_proto(proto: Self::Proto) -> Self;
}

#[derive(Default)]
pub struct ProtobufSerializer;

impl ProtobufSerializer {
    pub fn new() -> Self {
        Self
    }
}

impl<T> BenchmarkSerializer<T> for ProtobufSerializer
where
    T: ProtobufModel,
{
    fn serialize(&self, data: &T) -> Result<Vec<u8>, BoxError> {
        let proto = data.to_proto();
        let mut buf = Vec::with_capacity(proto.encoded_len());
        proto.encode(&mut buf)?;
        Ok(buf)
    }

    fn deserialize(&self, data: &[u8]) -> Result<T, BoxError> {
        Ok(T::from_proto(T::Proto::decode(data)?))
    }
}

impl ProtobufModel for NumericStruct {
    type Proto = pb::Struct;

    fn to_proto(&self) -> Self::Proto {
        Self::Proto {
            f1: self.f1,
            f2: self.f2,
            f3: self.f3,
            f4: self.f4,
            f5: self.f5,
            f6: self.f6,
            f7: self.f7,
            f8: self.f8,
        }
    }

    fn from_proto(proto: Self::Proto) -> Self {
        Self {
            f1: proto.f1,
            f2: proto.f2,
            f3: proto.f3,
            f4: proto.f4,
            f5: proto.f5,
            f6: proto.f6,
            f7: proto.f7,
            f8: proto.f8,
        }
    }
}

impl ProtobufModel for Sample {
    type Proto = pb::Sample;

    fn to_proto(&self) -> Self::Proto {
        Self::Proto {
            int_value: self.int_value,
            long_value: self.long_value,
            float_value: self.float_value,
            double_value: self.double_value,
            short_value: self.short_value,
            char_value: self.char_value,
            boolean_value: self.boolean_value,
            int_value_boxed: self.int_value_boxed,
            long_value_boxed: self.long_value_boxed,
            float_value_boxed: self.float_value_boxed,
            double_value_boxed: self.double_value_boxed,
            short_value_boxed: self.short_value_boxed,
            char_value_boxed: self.char_value_boxed,
            boolean_value_boxed: self.boolean_value_boxed,
            int_array: self.int_array.clone(),
            long_array: self.long_array.clone(),
            float_array: self.float_array.clone(),
            double_array: self.double_array.clone(),
            short_array: self.short_array.clone(),
            char_array: self.char_array.clone(),
            boolean_array: self.boolean_array.clone(),
            string: self.string.clone(),
        }
    }

    fn from_proto(proto: Self::Proto) -> Self {
        Self {
            int_value: proto.int_value,
            long_value: proto.long_value,
            float_value: proto.float_value,
            double_value: proto.double_value,
            short_value: proto.short_value,
            char_value: proto.char_value,
            boolean_value: proto.boolean_value,
            int_value_boxed: proto.int_value_boxed,
            long_value_boxed: proto.long_value_boxed,
            float_value_boxed: proto.float_value_boxed,
            double_value_boxed: proto.double_value_boxed,
            short_value_boxed: proto.short_value_boxed,
            char_value_boxed: proto.char_value_boxed,
            boolean_value_boxed: proto.boolean_value_boxed,
            int_array: proto.int_array,
            long_array: proto.long_array,
            float_array: proto.float_array,
            double_array: proto.double_array,
            short_array: proto.short_array,
            char_array: proto.char_array,
            boolean_array: proto.boolean_array,
            string: proto.string,
        }
    }
}

impl ProtobufModel for Media {
    type Proto = pb::Media;

    fn to_proto(&self) -> Self::Proto {
        Self::Proto {
            uri: self.uri.clone(),
            title: (!self.title.is_empty()).then(|| self.title.clone()),
            width: self.width,
            height: self.height,
            format: self.format.clone(),
            duration: self.duration,
            size: self.size,
            bitrate: self.bitrate,
            has_bitrate: self.has_bitrate,
            persons: self.persons.clone(),
            player: to_proto_player(self.player) as i32,
            copyright: self.copyright.clone(),
        }
    }

    fn from_proto(proto: Self::Proto) -> Self {
        Self {
            uri: proto.uri,
            title: proto.title.unwrap_or_default(),
            width: proto.width,
            height: proto.height,
            format: proto.format,
            duration: proto.duration,
            size: proto.size,
            bitrate: proto.bitrate,
            has_bitrate: proto.has_bitrate,
            persons: proto.persons,
            player: from_proto_player(proto.player),
            copyright: proto.copyright,
        }
    }
}

impl ProtobufModel for Image {
    type Proto = pb::Image;

    fn to_proto(&self) -> Self::Proto {
        Self::Proto {
            uri: self.uri.clone(),
            title: (!self.title.is_empty()).then(|| self.title.clone()),
            width: self.width,
            height: self.height,
            size: to_proto_size(self.size) as i32,
            media: None,
        }
    }

    fn from_proto(proto: Self::Proto) -> Self {
        Self {
            uri: proto.uri,
            title: proto.title.unwrap_or_default(),
            width: proto.width,
            height: proto.height,
            size: from_proto_size(proto.size),
        }
    }
}

impl ProtobufModel for MediaContent {
    type Proto = pb::MediaContent;

    fn to_proto(&self) -> Self::Proto {
        Self::Proto {
            media: Some(self.media.to_proto()),
            images: self.images.iter().map(ProtobufModel::to_proto).collect(),
        }
    }

    fn from_proto(proto: Self::Proto) -> Self {
        Self {
            media: proto
                .media
                .map(Media::from_proto)
                .unwrap_or_else(|| Media::from_proto(pb::Media::default())),
            images: proto.images.into_iter().map(Image::from_proto).collect(),
        }
    }
}

impl ProtobufModel for StructList {
    type Proto = pb::StructList;

    fn to_proto(&self) -> Self::Proto {
        Self::Proto {
            struct_list: self
                .struct_list
                .iter()
                .map(ProtobufModel::to_proto)
                .collect(),
        }
    }

    fn from_proto(proto: Self::Proto) -> Self {
        Self {
            struct_list: proto
                .struct_list
                .into_iter()
                .map(NumericStruct::from_proto)
                .collect(),
        }
    }
}

impl ProtobufModel for SampleList {
    type Proto = pb::SampleList;

    fn to_proto(&self) -> Self::Proto {
        Self::Proto {
            sample_list: self
                .sample_list
                .iter()
                .map(ProtobufModel::to_proto)
                .collect(),
        }
    }

    fn from_proto(proto: Self::Proto) -> Self {
        Self {
            sample_list: proto
                .sample_list
                .into_iter()
                .map(Sample::from_proto)
                .collect(),
        }
    }
}

impl ProtobufModel for MediaContentList {
    type Proto = pb::MediaContentList;

    fn to_proto(&self) -> Self::Proto {
        Self::Proto {
            media_content_list: self
                .media_content_list
                .iter()
                .map(ProtobufModel::to_proto)
                .collect(),
        }
    }

    fn from_proto(proto: Self::Proto) -> Self {
        Self {
            media_content_list: proto
                .media_content_list
                .into_iter()
                .map(MediaContent::from_proto)
                .collect(),
        }
    }
}

fn to_proto_player(player: Player) -> pb::Player {
    match player {
        Player::Java => pb::Player::Java,
        Player::Flash => pb::Player::Flash,
    }
}

fn from_proto_player(player: i32) -> Player {
    match pb::Player::try_from(player).unwrap_or(pb::Player::Java) {
        pb::Player::Java => Player::Java,
        pb::Player::Flash => Player::Flash,
    }
}

fn to_proto_size(size: Size) -> pb::Size {
    match size {
        Size::Small => pb::Size::Small,
        Size::Large => pb::Size::Large,
    }
}

fn from_proto_size(size: i32) -> Size {
    match pb::Size::try_from(size).unwrap_or(pb::Size::Small) {
        pb::Size::Small => Size::Small,
        pb::Size::Large => Size::Large,
    }
}
