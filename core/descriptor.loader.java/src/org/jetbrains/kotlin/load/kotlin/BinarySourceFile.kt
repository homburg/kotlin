/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.load.kotlin

import org.jetbrains.kotlin.descriptors.FileSystemKind
import org.jetbrains.kotlin.descriptors.SourceFile

internal class BinarySourceFile(kotlinBinaryClasses: List<KotlinJvmBinaryClass>) : SourceFile {
    private val fsKind by lazy {
        if (kotlinBinaryClasses.isEmpty()) return@lazy FileSystemKind.UNDEFINED

        kotlinBinaryClasses.fold(kotlinBinaryClasses[0].fileSystemKind) { fsKind, b ->
            if (fsKind == b.fileSystemKind) fsKind else return@lazy FileSystemKind.UNDEFINED
        }
    }

    override fun getFileSystemKind(): FileSystemKind = fsKind
}
