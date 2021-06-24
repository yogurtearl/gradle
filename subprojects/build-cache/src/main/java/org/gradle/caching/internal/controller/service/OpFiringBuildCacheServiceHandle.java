/*
 * Copyright 2017 the original author or authors.
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
import org.gradle.caching.BuildCacheEntryReader;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.BuildCacheService;
import org.gradle.caching.BuildCacheService.StoreOutcome;
import org.gradle.caching.internal.controller.operations.LoadOperationDetails;
import org.gradle.caching.internal.controller.operations.LoadOperationHitResult;
import org.gradle.caching.internal.controller.operations.LoadOperationMissResult;
import org.gradle.caching.internal.controller.operations.StoreOperationDetails;
import org.gradle.caching.internal.controller.operations.StoreOperationResult;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;

import java.nio.file.Path;

public class OpFiringBuildCacheServiceHandle extends BaseBuildCacheServiceHandle {

    private final BuildOperationExecutor buildOperationExecutor;

    public OpFiringBuildCacheServiceHandle(BuildCacheService service, boolean push, BuildCacheServiceRole role, BuildOperationExecutor buildOperationExecutor, boolean logStackTraces, boolean disableOnError) {
        super(service, push, role, logStackTraces, disableOnError);
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    protected boolean loadInner(final String description, final BuildCacheKey key, final LoadTarget loadTarget) {
        return buildOperationExecutor.call(new CallableBuildOperation<Boolean>() {
            @Override
            public Boolean call(BuildOperationContext context) {
                boolean loaded = OpFiringBuildCacheServiceHandle.super.loadInner(key, new OpFiringEntryReader(loadTarget));
                context.setResult(
                    loaded
                        ? new LoadOperationHitResult(loadTarget.file.length())
                        : LoadOperationMissResult.INSTANCE
                );
                return loaded;
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName(description)
                    .details(new LoadOperationDetails(key))
                    .progressDisplayName("Requesting from remote build cache");
            }
        });
    }

    @Override
    protected StoreOutcome storeInner(final String description, final BuildCacheKey key, final StoreTarget storeTarget) {
        return buildOperationExecutor.call(new CallableBuildOperation<StoreOutcome>() {
            @Override
            public StoreOutcome call(BuildOperationContext context) {
                StoreOutcome storeOutcome = OpFiringBuildCacheServiceHandle.super.storeInner(description, key, storeTarget);
                boolean stored = storeOutcome == StoreOutcome.STORED || (isUnknown(storeOutcome) && storeTarget.isStored());
                context.setResult(stored ? StoreOperationResult.STORED : StoreOperationResult.NOT_STORED);
                return storeOutcome;
            }

            @SuppressWarnings("deprecation")
            private boolean isUnknown(StoreOutcome storeOutcome) {
                return storeOutcome == StoreOutcome.UNKNOWN;
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName(description)
                    .details(new StoreOperationDetails(key, storeTarget.getSize()))
                    .progressDisplayName("Uploading to remote build cache");
            }
        });
    }

    private class OpFiringEntryReader implements BuildCacheEntryReader {

        private final BuildCacheEntryReader delegate;

        OpFiringEntryReader(BuildCacheEntryReader delegate) {
            this.delegate = delegate;
        }

        @Override
        public BuildCacheEntryFileReference openFileReference() {
            BuildCacheEntryFileReference fileReference = delegate.openFileReference();
            BuildOperationContext operationContext = buildOperationExecutor.start(BuildOperationDescriptor.displayName("Download from remote build cache")
                .progressDisplayName("Downloading"));
            return new BuildCacheEntryFileReference() {
                @Override
                public Path getFile() {
                    return fileReference.getFile();
                }

                @Override
                public void close() {
                    operationContext.setResult(null);
                }
            };
        }

    }

}
