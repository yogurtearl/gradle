/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.tooling.internal.provider.runner;

import org.gradle.api.BuildCancelledException;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.Try;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.concurrent.GradleThread;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.MultipleBuildOperationFailures;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.resources.ProjectLeaseRegistry;
import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter;
import org.gradle.tooling.internal.adapter.ViewBuilder;
import org.gradle.tooling.internal.gradle.GradleBuildIdentity;
import org.gradle.tooling.internal.gradle.GradleProjectIdentity;
import org.gradle.tooling.internal.protocol.BuildExceptionVersion1;
import org.gradle.tooling.internal.protocol.BuildResult;
import org.gradle.tooling.internal.protocol.InternalActionAwareBuildController;
import org.gradle.tooling.internal.protocol.InternalBuildControllerVersion2;
import org.gradle.tooling.internal.protocol.InternalUnsupportedModelException;
import org.gradle.tooling.internal.protocol.ModelIdentifier;
import org.gradle.tooling.internal.provider.connection.ProviderBuildResult;
import org.gradle.tooling.provider.model.UnknownModelException;
import org.gradle.tooling.provider.model.internal.ToolingModelBuilderLookup;
import org.gradle.util.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@SuppressWarnings("deprecation")
class DefaultBuildController implements org.gradle.tooling.internal.protocol.InternalBuildController, InternalBuildControllerVersion2, InternalActionAwareBuildController {
    private final GradleInternal gradle;
    private final BuildCancellationToken cancellationToken;
    private final BuildOperationExecutor buildOperationExecutor;
    private final ProjectLeaseRegistry projectLeaseRegistry;
    private final BuildStateRegistry buildStateRegistry;
    private final boolean parallelActions = !"false".equalsIgnoreCase(System.getProperty("org.gradle.internal.tooling.parallel"));

    public DefaultBuildController(GradleInternal gradle,
                                  BuildCancellationToken cancellationToken,
                                  BuildOperationExecutor buildOperationExecutor,
                                  ProjectLeaseRegistry projectLeaseRegistry,
                                  BuildStateRegistry buildStateRegistry) {
        this.gradle = gradle;
        this.cancellationToken = cancellationToken;
        this.buildOperationExecutor = buildOperationExecutor;
        this.projectLeaseRegistry = projectLeaseRegistry;
        this.buildStateRegistry = buildStateRegistry;
    }

    /**
     * This is used by consumers 1.8-rc-1 to 4.3
     */
    @Override
    @Deprecated
    public BuildResult<?> getBuildModel() throws BuildExceptionVersion1 {
        assertCanQuery();
        return new ProviderBuildResult<Object>(gradle);
    }

    /**
     * This is used by consumers 1.8-rc-1 to 4.3
     */
    @Override
    @Deprecated
    public BuildResult<?> getModel(Object target, ModelIdentifier modelIdentifier) throws BuildExceptionVersion1, InternalUnsupportedModelException {
        return getModel(target, modelIdentifier, null);
    }

    /**
     * This is used by consumers 4.4 and later
     */
    @Override
    public BuildResult<?> getModel(Object target, ModelIdentifier modelIdentifier, Object parameter)
        throws BuildExceptionVersion1, InternalUnsupportedModelException {
        assertCanQuery();
        if (cancellationToken.isCancellationRequested()) {
            throw new BuildCancelledException(String.format("Could not build '%s' model. Build cancelled.", modelIdentifier.getName()));
        }
        ModelTarget modelTarget = getTarget(target);
        ToolingModelBuilderLookup.Builder builder = getToolingModelBuilder(modelTarget, parameter != null, modelIdentifier);

        Object model;
        if (parameter == null) {
            model = builder.build(null);
        } else {
            model = getParameterizedModel(builder, parameter);
        }

        return new ProviderBuildResult<>(model);
    }

    @Override
    public boolean getCanQueryProjectModelInParallel(Class<?> modelType) {
        return projectLeaseRegistry.getAllowsParallelExecution() && parallelActions;
    }

    @Override
    public <T> List<T> run(List<Supplier<T>> actions) {
        assertCanQuery();
        List<NestedAction<T>> wrappers = new ArrayList<>(actions.size());
        for (Supplier<T> action : actions) {
            wrappers.add(new NestedAction<>(action));
        }
        if (parallelActions) {
            buildOperationExecutor.runAllWithAccessToProjectState(buildOperationQueue -> {
                for (NestedAction<T> wrapper : wrappers) {
                    buildOperationQueue.add(wrapper);
                }
            });
        } else {
            for (NestedAction<T> wrapper : wrappers) {
                wrapper.run(null);
            }
        }

        List<T> results = new ArrayList<>(actions.size());
        List<Throwable> failures = new ArrayList<>();
        for (NestedAction<T> wrapper : wrappers) {
            Try<T> value = wrapper.value();
            if (value.isSuccessful()) {
                results.add(value.get());
            } else {
                failures.add(value.getFailure().get());
            }
        }
        if (!failures.isEmpty()) {
            throw new MultipleBuildOperationFailures(failures, null);
        }
        return results;
    }

    private Object getParameterizedModel(ToolingModelBuilderLookup.Builder builder, Object parameter)
        throws InternalUnsupportedModelException {
        Class<?> expectedParameterType = builder.getParameterType();

        ViewBuilder<?> viewBuilder = new ProtocolToModelAdapter().builder(expectedParameterType);
        Object internalParameter = viewBuilder.build(parameter);
        return builder.build(internalParameter);
    }

    private ModelTarget getTarget(Object target) {
        if (target == null) {
            return new BuildScopedModel(gradle);
        } else if (target instanceof GradleProjectIdentity) {
            GradleProjectIdentity projectIdentity = (GradleProjectIdentity) target;
            BuildState build = findBuild(projectIdentity);
            return new ProjectScopedModel(findProject(build, projectIdentity));
        } else if (target instanceof GradleBuildIdentity) {
            GradleBuildIdentity buildIdentity = (GradleBuildIdentity) target;
            return new BuildScopedModel(findBuild(buildIdentity).getMutableModel());
        } else {
            throw new IllegalArgumentException("Don't know how to build models for " + target);
        }
    }

    private BuildState findBuild(GradleBuildIdentity buildIdentity) {
        AtomicReference<BuildState> match = new AtomicReference<>();
        buildStateRegistry.visitBuilds(buildState -> {
            if (buildState.isImportableBuild() && buildState.getBuildRootDir().equals(buildIdentity.getRootDir())) {
                match.set(buildState);
            }
        });
        if (match.get() != null) {
            return match.get();
        } else {
            throw new IllegalArgumentException(buildIdentity.getRootDir() + " is not included in this build");
        }
    }

    private ProjectInternal findProject(BuildState build, GradleProjectIdentity projectIdentity) {
        return build.getProjects().getProject(Path.path(projectIdentity.getProjectPath())).getMutableModel();
    }

    private ToolingModelBuilderLookup.Builder getToolingModelBuilder(ModelTarget modelTarget, boolean parameter, ModelIdentifier modelIdentifier) {
        ToolingModelBuilderLookup modelBuilderRegistry = modelTarget.targetProject.getServices().get(ToolingModelBuilderLookup.class);
        try {
            return modelTarget.locate(modelBuilderRegistry, parameter, modelIdentifier);
        } catch (UnknownModelException e) {
            throw (InternalUnsupportedModelException) (new InternalUnsupportedModelException()).initCause(e);
        }
    }

    private void assertCanQuery() {
        if (!GradleThread.isManaged()) {
            throw new IllegalStateException("A build controller cannot be used from a thread that is not managed by Gradle.");
        }
    }

    private static abstract class ModelTarget {
        final ProjectInternal targetProject;

        protected ModelTarget(ProjectInternal targetProject) {
            this.targetProject = targetProject;
        }

        abstract ToolingModelBuilderLookup.Builder locate(ToolingModelBuilderLookup lookup, boolean parameter, ModelIdentifier modelIdentifier);
    }

    private static class ProjectScopedModel extends ModelTarget {
        public ProjectScopedModel(ProjectInternal targetProject) {
            super(targetProject);
        }

        @Override
        ToolingModelBuilderLookup.Builder locate(ToolingModelBuilderLookup lookup, boolean parameter, ModelIdentifier modelIdentifier) {
            return lookup.locateForClientOperation(modelIdentifier.getName(), parameter, targetProject);
        }
    }

    private static class BuildScopedModel extends ModelTarget {
        private final GradleInternal targetBuild;

        public BuildScopedModel(GradleInternal gradle) {
            super(gradle.getDefaultProject());
            this.targetBuild = gradle;
        }

        @Override
        ToolingModelBuilderLookup.Builder locate(ToolingModelBuilderLookup lookup, boolean parameter, ModelIdentifier modelIdentifier) {
            return lookup.locateForClientOperation(modelIdentifier.getName(), parameter, targetBuild);
        }
    }

    private static class NestedAction<T> implements RunnableBuildOperation {
        private final Supplier<T> action;
        private Try<T> result;

        public NestedAction(Supplier<T> action) {
            this.action = action;
        }

        @Override
        public void run(BuildOperationContext context) {
            try {
                T value = action.get();
                result = Try.successful(value);
            } catch (Throwable t) {
                result = Try.failure(t);
            }
        }

        public Try<T> value() {
            return result;
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor.displayName("Tooling API client action");
        }
    }
}
