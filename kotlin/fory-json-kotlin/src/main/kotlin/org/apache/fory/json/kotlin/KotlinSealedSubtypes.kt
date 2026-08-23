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

package org.apache.fory.json.kotlin

import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.metadata.Modality
import kotlin.metadata.modality
import org.apache.fory.json.ForyJsonException

/** Cold sealed-closure producer backed by the same Kotlin metadata as object models. */
internal object KotlinSealedSubtypes {
  internal data class Table(val classes: Array<Class<*>>, val names: Array<String>)

  fun discover(root: Class<*>): Table {
    val metadata = KotlinMetadataTypes.classMetadata(root).kmClass
    if (metadata.modality != Modality.SEALED) {
      throw ForyJsonException("Empty @JsonSubTypes requires a sealed Kotlin type ${root.name}")
    }
    // Kotlin metadata is trusted static schema input retained by the application build. JSON
    // input cannot contribute a class name and later selects only a validated logical name.
    val classes = ArrayList<Class<*>>()
    val names = ArrayList<String>()
    val visited = Collections.newSetFromMap(IdentityHashMap<Class<*>, Boolean>())
    collect(root, metadata.sealedSubclasses, classes, names, visited)
    return Table(classes.toTypedArray(), names.toTypedArray())
  }

  private fun collect(
    root: Class<*>,
    directNames: List<String>,
    classes: MutableList<Class<*>>,
    names: MutableList<String>,
    visited: MutableSet<Class<*>>,
  ) {
    for (metadataName in directNames) {
      val subtype = load(root, metadataName)
      if (!visited.add(subtype)) continue
      val model = KotlinMetadataTypes.classMetadata(subtype).kmClass
      val concrete = !subtype.isInterface && !Modifier.isAbstract(subtype.modifiers)
      if (concrete) {
        classes += subtype
        names += sourceSimpleName(metadataName)
      }
      if (model.modality == Modality.SEALED) {
        collect(root, model.sealedSubclasses, classes, names, visited)
      } else if (!concrete) {
        throw ForyJsonException(
          "Sealed Kotlin JSON hierarchy has an open abstract branch ${subtype.name}"
        )
      }
    }
  }

  private fun load(root: Class<*>, metadataName: String): Class<*> {
    val binaryName = binaryName(metadataName)
    return try {
      Class.forName(binaryName, false, root.classLoader)
    } catch (cause: ClassNotFoundException) {
      throw ForyJsonException(
        "Cannot resolve Kotlin sealed subtype $binaryName from ${root.name}",
        cause,
      )
    }
  }

  private fun binaryName(metadataName: String): String {
    val packageEnd = metadataName.lastIndexOf('/')
    val packageName =
      if (packageEnd < 0) "" else metadataName.substring(0, packageEnd).replace('/', '.') + "."
    return packageName + metadataName.substring(packageEnd + 1).replace('.', '$')
  }

  private fun sourceSimpleName(metadataName: String): String =
    metadataName.substringAfterLast('/').substringAfterLast('.')
}
