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

using Apache.Fory;
using MessagePack;
using ProtoBuf;

namespace Apache.Fory.Benchmarks.CSharp;

[ForyObject]
[ProtoContract]
[MessagePackObject(keyAsPropertyName: true)]
public sealed class NumericStruct
{
    [ProtoMember(1)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int F1 { get; set; }

    [ProtoMember(2)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int F2 { get; set; }

    [ProtoMember(3)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int F3 { get; set; }

    [ProtoMember(4)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int F4 { get; set; }

    [ProtoMember(5)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int F5 { get; set; }

    [ProtoMember(6)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int F6 { get; set; }

    [ProtoMember(7)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int F7 { get; set; }

    [ProtoMember(8)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int F8 { get; set; }
}

[ForyObject]
[ProtoContract]
[MessagePackObject(keyAsPropertyName: true)]
public sealed class StructList
{
    [ProtoMember(1)]
    public List<NumericStruct> Values { get; set; } = [];
}

[ForyObject]
[ProtoContract]
[MessagePackObject(keyAsPropertyName: true)]
public sealed class Sample
{
    [ProtoMember(1)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int IntValue { get; set; }

    [ProtoMember(2)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public long LongValue { get; set; }

    [ProtoMember(3)]
    public float FloatValue { get; set; }

    [ProtoMember(4)]
    public double DoubleValue { get; set; }

    [ProtoMember(5)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int ShortValue { get; set; }

    [ProtoMember(6)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int CharValue { get; set; }

    [ProtoMember(7)]
    public bool BooleanValue { get; set; }

    [ProtoMember(8)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int IntValueBoxed { get; set; }

    [ProtoMember(9)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public long LongValueBoxed { get; set; }

    [ProtoMember(10)]
    public float FloatValueBoxed { get; set; }

    [ProtoMember(11)]
    public double DoubleValueBoxed { get; set; }

    [ProtoMember(12)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int ShortValueBoxed { get; set; }

    [ProtoMember(13)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int CharValueBoxed { get; set; }

    [ProtoMember(14)]
    public bool BooleanValueBoxed { get; set; }

    [ProtoMember(15)]
    public int[] IntArray { get; set; } = [];

    [ProtoMember(16)]
    public long[] LongArray { get; set; } = [];

    [ProtoMember(17)]
    public float[] FloatArray { get; set; } = [];

    [ProtoMember(18)]
    public double[] DoubleArray { get; set; } = [];

    [ProtoMember(19)]
    public int[] ShortArray { get; set; } = [];

    [ProtoMember(20)]
    public int[] CharArray { get; set; } = [];

    [ProtoMember(21)]
    public bool[] BooleanArray { get; set; } = [];

    [ProtoMember(22)]
    public string String { get; set; } = string.Empty;
}

[ForyObject]
[ProtoContract]
[MessagePackObject(keyAsPropertyName: true)]
public sealed class SampleList
{
    [ProtoMember(1)]
    public List<Sample> Values { get; set; } = [];
}

[ForyObject]
public enum Player
{
    Java,
    Flash,
}

[ForyObject]
public enum MediaSize
{
    Small,
    Large,
}

[ForyObject]
[ProtoContract]
[MessagePackObject(keyAsPropertyName: true)]
public sealed class Media
{
    [ProtoMember(1)]
    public string Uri { get; set; } = string.Empty;

    [ProtoMember(2)]
    public string Title { get; set; } = string.Empty;

    [ProtoMember(3)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int Width { get; set; }

    [ProtoMember(4)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int Height { get; set; }

    [ProtoMember(5)]
    public string Format { get; set; } = string.Empty;

    [ProtoMember(6)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public long Duration { get; set; }

    [ProtoMember(7)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public long Size { get; set; }

    [ProtoMember(8)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int Bitrate { get; set; }

    [ProtoMember(9)]
    public bool HasBitrate { get; set; }

    [ProtoMember(10)]
    public List<string> Persons { get; set; } = [];

    [ProtoMember(11)]
    public Player Player { get; set; }

    [ProtoMember(12)]
    public string Copyright { get; set; } = string.Empty;
}

[ForyObject]
[ProtoContract]
[MessagePackObject(keyAsPropertyName: true)]
public sealed class Image
{
    [ProtoMember(1)]
    public string Uri { get; set; } = string.Empty;

    [ProtoMember(2)]
    public string Title { get; set; } = string.Empty;

    [ProtoMember(3)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int Width { get; set; }

    [ProtoMember(4)]
    [Field(Encoding = FieldEncoding.Fixed)]
    public int Height { get; set; }

    [ProtoMember(5)]
    public MediaSize Size { get; set; }
}

[ForyObject]
[ProtoContract]
[MessagePackObject(keyAsPropertyName: true)]
public sealed class MediaContent
{
    [ProtoMember(1)]
    public Media Media { get; set; } = new();

    [ProtoMember(2)]
    public List<Image> Images { get; set; } = [];
}

[ForyObject]
[ProtoContract]
[MessagePackObject(keyAsPropertyName: true)]
public sealed class MediaContentList
{
    [ProtoMember(1)]
    public List<MediaContent> Values { get; set; } = [];
}

public static class BenchmarkDataFactory
{
    private const int ListSize = 5;

    public static NumericStruct CreateNumericStruct()
    {
        return new NumericStruct
        {
            F1 = -12345,
            F2 = 987654321,
            F3 = -31415,
            F4 = 27182818,
            F5 = -32000,
            F6 = 1000000,
            F7 = -999999999,
            F8 = 42,
        };
    }

    public static Sample CreateSample()
    {
        return new Sample
        {
            IntValue = 123,
            LongValue = 1230000,
            FloatValue = 12.345f,
            DoubleValue = 1.234567,
            ShortValue = 12345,
            CharValue = '!',
            BooleanValue = true,
            IntValueBoxed = 321,
            LongValueBoxed = 3210000,
            FloatValueBoxed = 54.321f,
            DoubleValueBoxed = 7.654321,
            ShortValueBoxed = 32100,
            CharValueBoxed = '$',
            BooleanValueBoxed = false,
            IntArray = [-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
            LongArray = [-123400, -12300, -1200, -100, 0, 100, 1200, 12300, 123400],
            FloatArray = [-12.34f, -12.3f, -12.0f, -1.0f, 0.0f, 1.0f, 12.0f, 12.3f, 12.34f],
            DoubleArray = [-1.234, -1.23, -12.0, -1.0, 0.0, 1.0, 12.0, 1.23, 1.234],
            ShortArray = [-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
            CharArray = ['a', 's', 'd', 'f', 'A', 'S', 'D', 'F'],
            BooleanArray = [true, false, false, true],
            String = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
        };
    }

    public static MediaContent CreateMediaContent()
    {
        return new MediaContent
        {
            Media = new Media
            {
                Uri = "https://apache.org/fory/video/123",
                Title = "Apache Fory: Cross-language serialization benchmark",
                Width = 1920,
                Height = 1080,
                Format = "video/mp4",
                Duration = 145_000,
                Size = 58_000_000,
                Bitrate = 3_200,
                HasBitrate = true,
                Persons = ["alice", "bob", "charlie", "david"],
                Player = Player.Java,
                Copyright = "Apache Software Foundation",
            },
            Images =
            [
                new Image
                {
                    Uri = "https://apache.org/fory/image/1",
                    Title = "cover",
                    Width = 1920,
                    Height = 1080,
                    Size = MediaSize.Large,
                },
                new Image
                {
                    Uri = "https://apache.org/fory/image/2",
                    Title = "thumbnail",
                    Width = 320,
                    Height = 180,
                    Size = MediaSize.Small,
                },
            ],
        };
    }

    public static StructList CreateStructList()
    {
        NumericStruct value = CreateNumericStruct();
        StructList list = new();
        for (int i = 0; i < ListSize; i++)
        {
            list.Values.Add(
                new NumericStruct
                {
                    F1 = value.F1 + i,
                    F2 = value.F2 - i,
                    F3 = value.F3 + i,
                    F4 = value.F4 - i,
                    F5 = value.F5 + i,
                    F6 = value.F6 - i,
                    F7 = value.F7 + i,
                    F8 = value.F8 + i,
                });
        }

        return list;
    }

    public static SampleList CreateSampleList()
    {
        Sample sample = CreateSample();
        SampleList list = new();
        for (int i = 0; i < ListSize; i++)
        {
            list.Values.Add(new Sample
            {
                IntValue = sample.IntValue + i,
                LongValue = sample.LongValue + i,
                FloatValue = sample.FloatValue,
                DoubleValue = sample.DoubleValue,
                ShortValue = sample.ShortValue,
                CharValue = sample.CharValue,
                BooleanValue = sample.BooleanValue,
                IntValueBoxed = sample.IntValueBoxed,
                LongValueBoxed = sample.LongValueBoxed,
                FloatValueBoxed = sample.FloatValueBoxed,
                DoubleValueBoxed = sample.DoubleValueBoxed,
                ShortValueBoxed = sample.ShortValueBoxed,
                CharValueBoxed = sample.CharValueBoxed,
                BooleanValueBoxed = sample.BooleanValueBoxed,
                IntArray = sample.IntArray,
                LongArray = sample.LongArray,
                FloatArray = sample.FloatArray,
                DoubleArray = sample.DoubleArray,
                ShortArray = sample.ShortArray,
                CharArray = sample.CharArray,
                BooleanArray = sample.BooleanArray,
                String = sample.String,
            });
        }

        return list;
    }

    public static MediaContentList CreateMediaContentList()
    {
        MediaContent content = CreateMediaContent();
        MediaContentList list = new();
        for (int i = 0; i < ListSize; i++)
        {
            list.Values.Add(new MediaContent
            {
                Media = new Media
                {
                    Uri = content.Media.Uri,
                    Title = content.Media.Title,
                    Width = content.Media.Width,
                    Height = content.Media.Height,
                    Format = content.Media.Format,
                    Duration = content.Media.Duration,
                    Size = content.Media.Size,
                    Bitrate = content.Media.Bitrate,
                    HasBitrate = content.Media.HasBitrate,
                    Persons = [.. content.Media.Persons],
                    Player = content.Media.Player,
                    Copyright = content.Media.Copyright,
                },
                Images =
                [
                    .. content.Images.Select(image => new Image
                    {
                        Uri = image.Uri,
                        Title = image.Title,
                        Width = image.Width,
                        Height = image.Height,
                        Size = image.Size,
                    }),
                ],
            });
        }

        return list;
    }
}
