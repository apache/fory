/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.benchmark.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.fory.benchmark.data.Image;
import org.apache.fory.benchmark.data.Media;
import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.benchmark.data.Sample;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

/**
 * The msgpack's official provides {@link <a
 * href="https://github.com/msgpack/msgpack-java/tree/main/msgpack-jackson">...</a>} lib, but the
 * performance is relatively poor, so we write a fast one here.
 */
public class MsgpackUtil {

  public static byte[] serialize(MediaContent mediaContent, ByteArrayOutputStream bos)
      throws IOException {
    MessagePacker messagePacker = MessagePack.newDefaultPacker(bos);

    packMediaContent(messagePacker, mediaContent);
    messagePacker.close();

    return bos.toByteArray();
  }

  public static MediaContent deserialize(ByteArrayInputStream bis) throws IOException {
    MessageUnpacker messageUnpacker = MessagePack.newDefaultUnpacker(bis);

    MediaContent mediaContent = unpackMediaContent(messageUnpacker);
    messageUnpacker.close();

    return mediaContent;
  }

  private static void packMediaContent(MessagePacker messagePacker, MediaContent mediaContent)
      throws IOException {
    messagePacker.packMapHeader(2);

    messagePacker.packString("media");
    if (mediaContent.media != null) {
      packMedia(messagePacker, mediaContent.media);
    } else {
      messagePacker.packNil();
    }

    messagePacker.packString("images");
    if (mediaContent.images != null) {
      messagePacker.packArrayHeader(mediaContent.images.size());
      for (Image image : mediaContent.images) {
        packImage(messagePacker, image);
      }
    } else {
      messagePacker.packNil();
    }
  }

  private static MediaContent unpackMediaContent(MessageUnpacker messageUnpacker)
      throws IOException {
    int mapSize = messageUnpacker.unpackMapHeader();
    MediaContent mediaContent = new MediaContent();

    for (int i = 0; i < mapSize; i++) {
      String key = messageUnpacker.unpackString();

      switch (key) {
        case "media":
          if (!messageUnpacker.tryUnpackNil()) {
            mediaContent.media = unpackMedia(messageUnpacker);
          } else {
            mediaContent.media = null;
          }
          break;
        case "images":
          if (!messageUnpacker.tryUnpackNil()) {
            int arraySize = messageUnpacker.unpackArrayHeader();
            List<Image> images = new ArrayList<>();
            for (int j = 0; j < arraySize; j++) {
              images.add(unpackImage(messageUnpacker));
            }
            mediaContent.images = images;
          } else {
            mediaContent.images = null;
          }
          break;
        default:
          messageUnpacker.skipValue();
          break;
      }
    }

    return mediaContent;
  }

  private static void packMedia(MessagePacker messagePacker, Media media) throws IOException {
    messagePacker.packMapHeader(9); // Media object's field count

    messagePacker.packString("uri");
    messagePacker.packString(media.uri);
    messagePacker.packString("width");
    messagePacker.packInt(media.width);
    messagePacker.packString("height");
    messagePacker.packInt(media.height);
    messagePacker.packString("format");
    messagePacker.packString(media.format);
    messagePacker.packString("duration");
    messagePacker.packLong(media.duration);
    messagePacker.packString("size");
    messagePacker.packLong(media.size);
    messagePacker.packString("player");
    messagePacker.packString(media.player.name());

    if (media.persons != null) {
      messagePacker.packString("persons");
      messagePacker.packArrayHeader(media.persons.size());
      for (String person : media.persons) {
        messagePacker.packString(person);
      }
    } else {
      messagePacker.packString("persons");
      messagePacker.packNil();
    }

    if (media.copyright != null) {
      messagePacker.packString("copyright");
      messagePacker.packString(media.copyright);
    } else {
      messagePacker.packString("copyright");
      messagePacker.packNil();
    }
  }

  private static Media unpackMedia(MessageUnpacker messageUnpacker) throws IOException {
    int mapSize = messageUnpacker.unpackMapHeader();
    Media media = new Media();

    for (int i = 0; i < mapSize; i++) {
      String key = messageUnpacker.unpackString();

      switch (key) {
        case "uri":
          media.uri = messageUnpacker.unpackString();
          break;
        case "width":
          media.width = messageUnpacker.unpackInt();
          break;
        case "height":
          media.height = messageUnpacker.unpackInt();
          break;
        case "format":
          media.format = messageUnpacker.unpackString();
          break;
        case "duration":
          media.duration = messageUnpacker.unpackLong();
          break;
        case "size":
          media.size = messageUnpacker.unpackInt();
          break;
        case "player":
          media.player = Media.Player.valueOf(messageUnpacker.unpackString());
          break;
        case "persons":
          if (!messageUnpacker.tryUnpackNil()) {
            int arraySize = messageUnpacker.unpackArrayHeader();
            media.persons = new ArrayList<>();
            for (int j = 0; j < arraySize; j++) {
              media.persons.add(messageUnpacker.unpackString());
            }
          } else {
            messageUnpacker.unpackNil();
            media.persons = null;
          }
          break;
        case "copyright":
          if (!messageUnpacker.tryUnpackNil()) {
            media.copyright = messageUnpacker.unpackString();
          } else {
            messageUnpacker.unpackNil();
            media.copyright = null;
          }
          break;
        default:
          messageUnpacker.skipValue();
          break;
      }
    }

    return media;
  }

  private static void packImage(MessagePacker messagePacker, Image image) throws IOException {
    messagePacker.packMapHeader(6);

    messagePacker.packString("uri");
    messagePacker.packString(image.uri);
    messagePacker.packString("title");
    if (image.title == null) {
      messagePacker.packNil();
    } else {
      messagePacker.packString(image.title);
    }
    messagePacker.packString("width");
    messagePacker.packInt(image.width);
    messagePacker.packString("height");
    messagePacker.packInt(image.height);
    messagePacker.packString("size");
    messagePacker.packString(image.size.name());
    messagePacker.packString("media");
    if (image.media != null) {
      packMedia(messagePacker, image.media);
    } else {
      messagePacker.packNil();
    }
  }

  private static Image unpackImage(MessageUnpacker messageUnpacker) throws IOException {
    int mapSize = messageUnpacker.unpackMapHeader();
    Image image = new Image();

    for (int i = 0; i < mapSize; i++) {
      String key = messageUnpacker.unpackString();

      switch (key) {
        case "uri":
          image.uri = messageUnpacker.unpackString();
          break;
        case "title":
          if (!messageUnpacker.tryUnpackNil()) {
            image.title = messageUnpacker.unpackString();
          } else {
            image.title = null;
          }
          break;
        case "width":
          image.width = messageUnpacker.unpackInt();
          break;
        case "height":
          image.height = messageUnpacker.unpackInt();
          break;
        case "size":
          image.size = Image.Size.valueOf(messageUnpacker.unpackString());
          break;
        case "media":
          if (!messageUnpacker.tryUnpackNil()) {
            image.media = unpackMedia(messageUnpacker);
          } else {
            image.media = null;
          }
          break;
        default:
          messageUnpacker.skipValue();
          break;
      }
    }

    return image;
  }

  public static byte[] serializeSample(Sample sample, ByteArrayOutputStream bos)
      throws IOException {
    MessagePacker messagePacker = MessagePack.newDefaultPacker(bos);
    packSample(messagePacker, sample);
    messagePacker.close();
    return bos.toByteArray();
  }

  public static Sample deserializeSample(ByteArrayInputStream bis) throws IOException {
    MessageUnpacker messageUnpacker = MessagePack.newDefaultUnpacker(bis);

    Sample sample = unpackSample(messageUnpacker);
    messageUnpacker.close();

    return sample;
  }

  private static void packSample(MessagePacker messagePacker, Sample sample) throws IOException {
    messagePacker.packMapHeader(23);

    // Basic type
    messagePacker.packString("intValue");
    messagePacker.packInt(sample.intValue);
    messagePacker.packString("longValue");
    messagePacker.packLong(sample.longValue);
    messagePacker.packString("floatValue");
    messagePacker.packFloat(sample.floatValue);
    messagePacker.packString("doubleValue");
    messagePacker.packDouble(sample.doubleValue);
    messagePacker.packString("shortValue");
    messagePacker.packShort(sample.shortValue);
    messagePacker.packString("charValue");
    messagePacker.packString(String.valueOf(sample.charValue));
    messagePacker.packString("booleanValue");
    messagePacker.packBoolean(sample.booleanValue);

    // Boxed type
    messagePacker.packString("intValueBoxed");
    if (sample.intValueBoxed != null) messagePacker.packInt(sample.intValueBoxed);
    else messagePacker.packNil();

    messagePacker.packString("longValueBoxed");
    if (sample.longValueBoxed != null) messagePacker.packLong(sample.longValueBoxed);
    else messagePacker.packNil();

    messagePacker.packString("floatValueBoxed");
    if (sample.floatValueBoxed != null) messagePacker.packFloat(sample.floatValueBoxed);
    else messagePacker.packNil();

    messagePacker.packString("doubleValueBoxed");
    if (sample.doubleValueBoxed != null) messagePacker.packDouble(sample.doubleValueBoxed);
    else messagePacker.packNil();

    messagePacker.packString("shortValueBoxed");
    if (sample.shortValueBoxed != null) messagePacker.packShort(sample.shortValueBoxed);
    else messagePacker.packNil();

    messagePacker.packString("charValueBoxed");
    if (sample.charValueBoxed != null)
      messagePacker.packString(String.valueOf(sample.charValueBoxed));
    else messagePacker.packNil();

    messagePacker.packString("booleanValueBoxed");
    if (sample.booleanValueBoxed != null) messagePacker.packBoolean(sample.booleanValueBoxed);
    else messagePacker.packNil();

    // Array type
    messagePacker.packString("intArray");
    if (sample.intArray != null) {
      messagePacker.packArrayHeader(sample.intArray.length);
      for (int value : sample.intArray) messagePacker.packInt(value);
    } else {
      messagePacker.packNil();
    }

    messagePacker.packString("longArray");
    if (sample.longArray != null) {
      messagePacker.packArrayHeader(sample.longArray.length);
      for (long value : sample.longArray) messagePacker.packLong(value);
    } else {
      messagePacker.packNil();
    }

    messagePacker.packString("floatArray");
    if (sample.floatArray != null) {
      messagePacker.packArrayHeader(sample.floatArray.length);
      for (float value : sample.floatArray) messagePacker.packFloat(value);
    } else {
      messagePacker.packNil();
    }

    messagePacker.packString("doubleArray");
    if (sample.doubleArray != null) {
      messagePacker.packArrayHeader(sample.doubleArray.length);
      for (double value : sample.doubleArray) messagePacker.packDouble(value);
    } else {
      messagePacker.packNil();
    }

    messagePacker.packString("shortArray");
    if (sample.shortArray != null) {
      messagePacker.packArrayHeader(sample.shortArray.length);
      for (short value : sample.shortArray) messagePacker.packShort(value);
    } else {
      messagePacker.packNil();
    }

    messagePacker.packString("charArray");
    if (sample.charArray != null) {
      messagePacker.packArrayHeader(sample.charArray.length);
      for (char value : sample.charArray) messagePacker.packString(String.valueOf(value));
    } else {
      messagePacker.packNil();
    }

    messagePacker.packString("booleanArray");
    if (sample.booleanArray != null) {
      messagePacker.packArrayHeader(sample.booleanArray.length);
      for (boolean value : sample.booleanArray) messagePacker.packBoolean(value);
    } else {
      messagePacker.packNil();
    }

    messagePacker.packString("string");
    if (sample.string != null) messagePacker.packString(sample.string);
    else messagePacker.packNil();

    messagePacker.packString("sample");
    if (sample.sample != null) packSample(messagePacker, sample.sample);
    else messagePacker.packNil();
  }

  private static Sample unpackSample(MessageUnpacker messageUnpacker) throws IOException {
    int mapSize = messageUnpacker.unpackMapHeader();
    Sample sample = new Sample();

    for (int i = 0; i < mapSize; i++) {
      String key = messageUnpacker.unpackString();

      switch (key) {
        case "intValue":
          sample.intValue = messageUnpacker.unpackInt();
          break;
        case "longValue":
          sample.longValue = messageUnpacker.unpackLong();
          break;
        case "floatValue":
          sample.floatValue = messageUnpacker.unpackFloat();
          break;
        case "doubleValue":
          sample.doubleValue = messageUnpacker.unpackDouble();
          break;
        case "shortValue":
          sample.shortValue = messageUnpacker.unpackShort();
          break;
        case "charValue":
          sample.charValue = messageUnpacker.unpackString().charAt(0);
          break;
        case "booleanValue":
          sample.booleanValue = messageUnpacker.unpackBoolean();
          break;

        case "intValueBoxed":
          if (!messageUnpacker.tryUnpackNil()) sample.intValueBoxed = messageUnpacker.unpackInt();
          else sample.intValueBoxed = null;
          break;

        case "longValueBoxed":
          if (!messageUnpacker.tryUnpackNil()) sample.longValueBoxed = messageUnpacker.unpackLong();
          else sample.longValueBoxed = null;
          break;

        case "floatValueBoxed":
          if (!messageUnpacker.tryUnpackNil())
            sample.floatValueBoxed = messageUnpacker.unpackFloat();
          else sample.floatValueBoxed = null;
          break;

        case "doubleValueBoxed":
          if (!messageUnpacker.tryUnpackNil())
            sample.doubleValueBoxed = messageUnpacker.unpackDouble();
          else sample.doubleValueBoxed = null;
          break;

        case "shortValueBoxed":
          if (!messageUnpacker.tryUnpackNil())
            sample.shortValueBoxed = messageUnpacker.unpackShort();
          else sample.shortValueBoxed = null;
          break;

        case "charValueBoxed":
          if (!messageUnpacker.tryUnpackNil())
            sample.charValueBoxed = messageUnpacker.unpackString().charAt(0);
          else sample.charValueBoxed = null;
          break;

        case "booleanValueBoxed":
          if (!messageUnpacker.tryUnpackNil())
            sample.booleanValueBoxed = messageUnpacker.unpackBoolean();
          else sample.booleanValueBoxed = null;
          break;

        case "intArray":
          if (!messageUnpacker.tryUnpackNil()) {
            int length = messageUnpacker.unpackArrayHeader();
            sample.intArray = new int[length];
            for (int j = 0; j < length; j++) sample.intArray[j] = messageUnpacker.unpackInt();
          } else {
            messageUnpacker.unpackNil();
            sample.intArray = null;
          }
          break;

        case "longArray":
          if (!messageUnpacker.tryUnpackNil()) {
            int length = messageUnpacker.unpackArrayHeader();
            sample.longArray = new long[length];
            for (int j = 0; j < length; j++) sample.longArray[j] = messageUnpacker.unpackLong();
          } else {
            messageUnpacker.unpackNil();
            sample.longArray = null;
          }
          break;

        case "floatArray":
          if (!messageUnpacker.tryUnpackNil()) {
            int length = messageUnpacker.unpackArrayHeader();
            sample.floatArray = new float[length];
            for (int j = 0; j < length; j++) sample.floatArray[j] = messageUnpacker.unpackFloat();
          } else {
            messageUnpacker.unpackNil();
            sample.floatArray = null;
          }
          break;

        case "doubleArray":
          if (!messageUnpacker.tryUnpackNil()) {
            int length = messageUnpacker.unpackArrayHeader();
            sample.doubleArray = new double[length];
            for (int j = 0; j < length; j++) sample.doubleArray[j] = messageUnpacker.unpackDouble();
          } else {
            messageUnpacker.unpackNil();
            sample.doubleArray = null;
          }
          break;

        case "shortArray":
          if (!messageUnpacker.tryUnpackNil()) {
            int length = messageUnpacker.unpackArrayHeader();
            sample.shortArray = new short[length];
            for (int j = 0; j < length; j++) sample.shortArray[j] = messageUnpacker.unpackShort();
          } else {
            messageUnpacker.unpackNil();
            sample.shortArray = null;
          }
          break;

        case "charArray":
          if (!messageUnpacker.tryUnpackNil()) {
            int length = messageUnpacker.unpackArrayHeader();
            sample.charArray = new char[length];
            for (int j = 0; j < length; j++)
              sample.charArray[j] = messageUnpacker.unpackString().charAt(0);
          } else {
            messageUnpacker.unpackNil();
            sample.charArray = null;
          }
          break;

        case "booleanArray":
          if (!messageUnpacker.tryUnpackNil()) {
            int length = messageUnpacker.unpackArrayHeader();
            sample.booleanArray = new boolean[length];
            for (int j = 0; j < length; j++)
              sample.booleanArray[j] = messageUnpacker.unpackBoolean();
          } else {
            messageUnpacker.unpackNil();
            sample.booleanArray = null;
          }
          break;

        case "string":
          if (!messageUnpacker.tryUnpackNil()) sample.string = messageUnpacker.unpackString();
          else sample.string = null;
          break;

        case "sample":
          if (!messageUnpacker.tryUnpackNil()) sample.sample = unpackSample(messageUnpacker);
          else sample.sample = null;
          break;

        default:
          messageUnpacker.skipValue();
          break;
      }
    }

    return sample;
  }

  public static void main(String[] args) throws IOException {
    MediaContent mc = new MediaContent().populate(true);

    byte[] mcSerializedData = serialize(mc, new ByteArrayOutputStream());

    MediaContent mcDeserialized = deserialize(new ByteArrayInputStream(mcSerializedData));

    System.out.println("Original: " + mc.toString());
    System.out.println("Deserialized: " + mcDeserialized.toString());

    Sample sample = new Sample().populate(true);

    byte[] sampleSerializedData = serializeSample(sample, new ByteArrayOutputStream());

    Sample sampleDeserialized = deserializeSample(new ByteArrayInputStream(sampleSerializedData));

    System.out.println("Original: " + sample.toString());
    System.out.println("Deserialized: " + sampleDeserialized.toString());
  }
}
