/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.internal.controller.service;

import org.gradle.caching.BuildCacheEntryFileReference;

import java.io.File;
import java.nio.file.Path;

abstract class BaseCacheEntryAccessor implements AutoCloseable {

    final File file;

    private boolean closed;
    private boolean opened;
    private BuildCacheEntryFileReference fileReference;

    public BaseCacheEntryAccessor(File file) {
        this.file = file;
    }

    protected synchronized boolean isOpened() {
        return opened;
    }

    public synchronized BuildCacheEntryFileReference openFileReference() {
        if (fileReference != null) {
            throw new IllegalStateException("file reference already opened");

        }
        opened = true;
        fileReference = new BuildCacheEntryFileReference() {
            @Override
            public Path getFile() {
                return file.toPath();
            }

            @Override
            public void close() {

            }
        };
        return fileReference;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
