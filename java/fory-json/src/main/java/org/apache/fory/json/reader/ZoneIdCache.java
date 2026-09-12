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

package org.apache.fory.json.reader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.collection.LongMap;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.serializer.StringSerializer;

/** Reader-local references to bounded, immutable, process-wide zone entries. */
final class ZoneIdCache {
  static final long HASH_SEED = 0xcbf29ce484222325L;
  static final long HASH_MULTIPLIER = 0x100000001b3L;
  private static final int MAX_SHARED_ENTRIES = 2048;
  private static final int MAX_LOCAL_ENTRIES = 1024;
  private static final int MAX_ID_LENGTH = 128;
  private static final ConcurrentHashMap<Long, Entry> SHARED = new ConcurrentHashMap<>();
  private static final Set<String> CACHEABLE_IDS = cacheableIds();
  private static final boolean STRING_BYTES_BACKED = StringSerializer.isBytesBackedString();
  private static final MethodHandle REGION_CONSTRUCTOR = regionConstructor();
  private static final MethodHandle REGION_RULES = regionRules();
  private static final MethodHandle STRING_HASH_SETTER = stringHashSetter();
  private static final boolean[] REGION_CHARACTERS = regionCharacters();
  private static final short[] REGION_PAIRS = regionPairs();
  private static final MethodHandle CACHED_RULES = cachedRules();
  private LongMap<Entry> entries;

  ZoneId get(JsonReader reader, int start, int end, long hash) {
    Entry entry = find(hash);
    if (entry != null && reader.matchesZoneId(start, end, entry.text)) {
      return entry.zone;
    }
    return parse(reader.slice(start, end), hash);
  }

  ZoneId get(CharSequence text, int start, int end) {
    long hash = HASH_SEED;
    for (int i = start; i < end; i++) {
      hash = hash * HASH_MULTIPLIER ^ text.charAt(i);
    }
    Entry entry = find(hash);
    if (entry != null && matches(entry.id, text, start, end)) {
      return entry.zone;
    }
    // Only a miss materializes text. A quoted-text view's subSequence would copy the full token.
    StringBuilder id = new StringBuilder(end - start);
    for (int i = start; i < end; i++) {
      id.append(text.charAt(i));
    }
    return parse(id.toString(), hash);
  }

  private Entry find(long hash) {
    Entry entry = entries == null ? null : entries.get(hash);
    if (entry != null) {
      return entry;
    }
    entry = SHARED.get(hash);
    if (entry != null) {
      remember(hash, entry);
    }
    return entry;
  }

  private void remember(long hash, Entry entry) {
    if (entries == null) {
      entries = new LongMap<>(32);
    }
    if (entries.size < MAX_LOCAL_ENTRIES) {
      entries.put(hash, entry);
    }
  }

  private ZoneId parse(String id, long hash) {
    ZoneId zone = parseZoneId(id);
    if (id.length() > MAX_ID_LENGTH || !CACHEABLE_IDS.contains(id) || !canCache(zone)) {
      return zone;
    }
    // Parse outside the publication lock: custom providers can execute application code.
    // All readers retain the winning entry, so independent ForyJson instances share its objects.
    Entry entry;
    synchronized (SHARED) {
      entry = SHARED.get(hash);
      if (entry == null) {
        if (SHARED.size() >= MAX_SHARED_ENTRIES) {
          return zone;
        }
        entry = new Entry(id, zone);
        SHARED.put(hash, entry);
      } else if (!entry.id.equals(id)) {
        // A hash is only a candidate. Do not replace an entry or grow collision chains.
        return zone;
      }
    }
    remember(hash, entry);
    return entry.zone;
  }

  private static boolean canCache(ZoneId zone) {
    if (zone instanceof ZoneOffset) {
      return true;
    }
    if (CACHED_RULES == null) {
      return false;
    }
    try {
      // A provider returning null for getRules(id, true) refuses caching. Inspect the rules
      // already resolved by this parse without querying it twice or calling getRules() lazily.
      return (ZoneRules) CACHED_RULES.invokeExact(zone) != null;
    } catch (ThreadDeath | VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot inspect zone ID rules", e);
    }
  }

  private static Set<String> cacheableIds() {
    // TimeZone enumerates the platform's built-in IDs, independently of custom ZoneRulesProvider
    // registrations. Input must not grow this admission set: cache pollution would let arbitrary
    // custom names or second-precision offsets consume shared memory and displace common zones.
    // Only add the finite set of canonical quarter-hour offsets and their standard prefixes.
    Set<String> ids = new HashSet<>(Arrays.asList(TimeZone.getAvailableIDs()));
    String[] prefixes = {"UT", "UTC", "GMT"};
    for (int quarter = -72; quarter <= 72; quarter++) {
      String offset = ZoneOffset.ofTotalSeconds(quarter * 900).getId();
      ids.add(offset);
      for (String prefix : prefixes) {
        ids.add(quarter == 0 ? prefix : prefix + offset);
      }
    }
    return ids;
  }

  private static MethodHandle cachedRules() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      Class<?> region = Class.forName("java.time.ZoneRegion", false, ZoneId.class.getClassLoader());
      return _JDKAccess._trustedLookup(region)
          .findGetter(region, "rules", ZoneRules.class)
          .asType(MethodType.methodType(ZoneRules.class, ZoneId.class));
    } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }

  private static boolean matches(String id, CharSequence text, int start, int end) {
    if (id.length() != end - start) {
      return false;
    }
    for (int i = 0; i < id.length(); i++) {
      if (id.charAt(i) != text.charAt(start + i)) {
        return false;
      }
    }
    return true;
  }

  private static final class Entry {
    final String id;
    final ZoneId zone;
    final byte[] text;

    Entry(String id, ZoneId zone) {
      this.id = id;
      this.zone = zone;
      // Valid zone IDs contain only ASCII; share the String's immutable storage when available.
      text =
          STRING_BYTES_BACKED && StringSerializer.isLatin1Coder(StringSerializer.getStringCoder(id))
              ? StringSerializer.getStringBytes(id)
              : id.getBytes(StandardCharsets.US_ASCII);
    }
  }

  private static ZoneId parseZoneId(String value) {
    if (REGION_CONSTRUCTOR == null
        || REGION_RULES == null
        || STRING_HASH_SETTER == null
        || !STRING_BYTES_BACKED
        || !StringSerializer.isLatin1Coder(StringSerializer.getStringCoder(value))
        || value.length() < 2) {
      return ZoneId.of(value);
    }
    if (value.startsWith("UT") || value.startsWith("GMT")) {
      return parsePrefixedZoneId(value);
    }
    byte[] bytes = StringSerializer.getStringBytes(value);
    int first = bytes[0] | 0x20;
    if (first < 'a' || first > 'z') {
      return ZoneId.of(value);
    }
    int hash = bytes[0];
    int i = 1;
    for (; i <= bytes.length - Integer.BYTES; i += Integer.BYTES) {
      int text = LittleEndian.getInt32(bytes, i);
      if ((text & 0x80808080) != 0) {
        return ZoneId.of(value);
      }
      int firstPair = REGION_PAIRS[((text & 0x7f) << 7) | ((text >>> 8) & 0x7f)];
      int secondPair = REGION_PAIRS[(((text >>> 16) & 0x7f) << 7) | (text >>> 24)];
      if ((firstPair | secondPair) < 0) {
        return ZoneId.of(value);
      }
      hash = 31 * 31 * 31 * 31 * hash + 31 * 31 * firstPair + secondPair;
    }
    for (; i < bytes.length - 1; i += 2) {
      int firstChar = bytes[i];
      int secondChar = bytes[i + 1];
      if ((firstChar | secondChar) < 0) {
        return ZoneId.of(value);
      }
      int contribution = REGION_PAIRS[(firstChar << 7) | secondChar];
      if (contribution < 0) {
        return ZoneId.of(value);
      }
      hash = 961 * hash + contribution;
    }
    if (i < bytes.length) {
      int ch = bytes[i] & 0xff;
      if (!REGION_CHARACTERS[ch]) {
        return ZoneId.of(value);
      }
      hash = 31 * hash + ch;
    }
    // The decoded String owns its storage. Compute its standard hash during validation so the
    // provider lookup does not scan the same characters again on a cache miss.
    try {
      STRING_HASH_SETTER.invokeExact(value, hash);
      // Preserve the provider's caching decision, including a null result for dynamic rules.
      ZoneRules rules = (ZoneRules) REGION_RULES.invokeExact(value, true);
      return zoneRegion(value, rules);
    } catch (RuntimeException e) {
      throw e;
    } catch (ThreadDeath | VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot resolve JSON zone ID", e);
    }
  }

  private static ZoneId parsePrefixedZoneId(String value) {
    int start = value.startsWith("UTC") || value.startsWith("GMT") ? 3 : 2;
    if (value.length() != start + 6 || value.charAt(start + 3) != ':') {
      return ZoneId.of(value);
    }
    int sign = value.charAt(start);
    int h0 = value.charAt(start + 1) - '0';
    int h1 = value.charAt(start + 2) - '0';
    int m0 = value.charAt(start + 4) - '0';
    int m1 = value.charAt(start + 5) - '0';
    if ((sign != '+' && sign != '-')
        || (h0 | h1 | m0 | m1 | (9 - h0) | (9 - h1) | (9 - m0) | (9 - m1)) < 0) {
      return ZoneId.of(value);
    }
    int hours = h0 * 10 + h1;
    int minutes = m0 * 10 + m1;
    if (hours > 18 || minutes > 59 || (hours == 18 && minutes != 0)) {
      return ZoneId.of(value);
    }
    int seconds = hours * 3600 + minutes * 60;
    if (seconds == 0) {
      return ZoneId.of(value);
    }
    // Nonzero signed HH:MM is already canonical. Retain this read's ID instead of splitting
    // and concatenating it; zero offsets must keep the JDK's prefix-only normalization.
    ZoneOffset offset = ZoneOffset.ofTotalSeconds(sign == '-' ? -seconds : seconds);
    return zoneRegion(value, offset.getRules());
  }

  private static ZoneId zoneRegion(String value, ZoneRules rules) {
    try {
      return (ZoneId) REGION_CONSTRUCTOR.invokeExact(value, rules);
    } catch (ThreadDeath | VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot construct JSON zone ID", e);
    }
  }

  private static boolean[] regionCharacters() {
    boolean[] characters = new boolean[256];
    String allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789/~._+-";
    for (int i = 0; i < allowed.length(); i++) {
      characters[allowed.charAt(i)] = true;
    }
    return characters;
  }

  private static short[] regionPairs() {
    // ASCII pairs fit fourteen index bits. A valid pair contributes 31 * first + second to
    // the String hash; -1 marks every pair containing a disallowed region character.
    short[] pairs = new short[1 << 14];
    Arrays.fill(pairs, (short) -1);
    for (int first = 0; first < 128; first++) {
      if (REGION_CHARACTERS[first]) {
        for (int second = 0; second < 128; second++) {
          if (REGION_CHARACTERS[second]) {
            pairs[(first << 7) | second] = (short) (31 * first + second);
          }
        }
      }
    }
    return pairs;
  }

  private static MethodHandle stringHashSetter() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      return _JDKAccess._trustedLookup(String.class).findSetter(String.class, "hash", int.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }

  private static MethodHandle regionRules() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      // Android does not expose ZoneRulesProvider. A direct class reference fails R8 even
      // though Android uses ZoneId.of; resolve this JVM-only dependency during initialization.
      Class<?> provider =
          Class.forName("java.time.zone.ZoneRulesProvider", false, ZoneId.class.getClassLoader());
      return MethodHandles.publicLookup()
          .findStatic(
              provider,
              "getRules",
              MethodType.methodType(ZoneRules.class, String.class, boolean.class));
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  private static MethodHandle regionConstructor() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      Class<?> region = Class.forName("java.time.ZoneRegion", false, ZoneId.class.getClassLoader());
      return _JDKAccess._trustedLookup(region)
          .findConstructor(region, MethodType.methodType(void.class, String.class, ZoneRules.class))
          .asType(MethodType.methodType(ZoneId.class, String.class, ZoneRules.class));
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }
}
