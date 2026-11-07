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

using System.Buffers;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.Tests;

public sealed class RegistrationTests
{
    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    [InlineData(5)]
    [InlineData(6)]
    public void RootFreezesRegistration(int operation)
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<Node>(952);
        byte[] bytes = ForyRuntime.Builder().Build().Serialize(37);
        switch (operation)
        {
            case 0:
                fory.Serialize(37);
                break;
            case 1:
                fory.Serialize(new ArrayBufferWriter<byte>(), 37);
                break;
            case 2:
                Assert.Equal(37, fory.Deserialize<int>(bytes));
                break;
            case 3:
                Assert.Equal(37, fory.Deserialize<int>(bytes.AsSpan()));
                break;
            case 4:
                Assert.Throws<TypeNotRegisteredException>(() => fory.Serialize(new CustomPayload()));
                break;
            case 5:
                Assert.ThrowsAny<Exception>(() => fory.Deserialize<int>(Array.Empty<byte>()));
                break;
            case 6:
                Assert.ThrowsAny<Exception>(() => fory.Deserialize<int>(ReadOnlySpan<byte>.Empty));
                break;
        }

        Assert.Throws<InvalidOperationException>(() => fory.Register<Node>(954));
        Assert.Throws<InvalidOperationException>(() => fory.Register<Node>("test.Node"));
        Assert.Throws<InvalidOperationException>(() => fory.Register<Node>("test", "Node"));
        Assert.Throws<InvalidOperationException>(() => fory.Register<int, ForbiddenSerializer>(953));
        Assert.Throws<InvalidOperationException>(() => fory.Register<int, ForbiddenSerializer>("test.Int"));
        Assert.Throws<InvalidOperationException>(() => fory.Register<int, ForbiddenSerializer>("test", "Int"));
        Assert.Equal(37, fory.Deserialize<int>(fory.Serialize(37)));
        ForyRuntime peer = ForyRuntime.Builder().Build();
        peer.Register<Node>(952);
        Assert.Equal(37, peer.Deserialize<Node>(fory.Serialize(new Node { Value = 37 })).Value);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    public async Task FirstThreadFreezesRegistration(int operation)
    {
        using ThreadSafeFory fory = ForyRuntime.Builder().BuildThreadSafe();
        fory.Register<Node>(952);
        byte[] bytes = ForyRuntime.Builder().Build().Serialize(37);
        await Task.Factory.StartNew(() =>
        {
            switch (operation)
            {
                case 0:
                    fory.Serialize(37);
                    break;
                case 1:
                    fory.Serialize(new ArrayBufferWriter<byte>(), 37);
                    break;
                case 2:
                    Assert.Equal(37, fory.Deserialize<int>(bytes));
                    break;
                case 3:
                    Assert.Throws<TypeNotRegisteredException>(() => fory.Serialize(new CustomPayload()));
                    break;
                case 4:
                    Assert.ThrowsAny<Exception>(() => fory.Deserialize<int>(ReadOnlySpan<byte>.Empty));
                    break;
            }
        }, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);

        Assert.Throws<InvalidOperationException>(() => fory.Register<Node>(954));
        Assert.Throws<InvalidOperationException>(() => fory.Register<Node>("test.Node"));
        Assert.Throws<InvalidOperationException>(() => fory.Register<Node>("test", "Node"));
        Assert.Throws<InvalidOperationException>(() => fory.Register<int, ForbiddenSerializer>(953));
        Assert.Throws<InvalidOperationException>(() => fory.Register<int, ForbiddenSerializer>("test.Int"));
        Assert.Throws<InvalidOperationException>(() => fory.Register<int, ForbiddenSerializer>("test", "Int"));

        // A new thread must receive only the registrations accepted before first use.
        await Task.Factory.StartNew(() =>
        {
            ForyRuntime peer = ForyRuntime.Builder().Build();
            peer.Register<Node>(952);
            byte[] nodeBytes = fory.Serialize(new Node { Value = 37 });
            Assert.Equal(37, peer.Deserialize<Node>(nodeBytes).Value);
            Assert.Equal(37, fory.Deserialize<int>(fory.Serialize(37)));
        }, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
    }

    public sealed class ForbiddenSerializer : Serializer<int>
    {
        public ForbiddenSerializer() => throw new NotSupportedException("late serializer construction");

        public override int DefaultValue => 0;

        public override void WriteData(WriteContext context, in int value, bool hasGenerics) =>
            throw new NotSupportedException();

        public override int ReadData(ReadContext context) => throw new NotSupportedException();
    }
}
