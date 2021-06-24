/*
 * Copyright 2014 the original author or authors.
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

import org.gradle.internal.buildtree.BuildActionRunner;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.tooling.internal.protocol.PhasedActionResult;
import org.gradle.tooling.internal.provider.action.ClientProvidedBuildAction;
import org.gradle.tooling.internal.provider.serialization.PayloadSerializer;

public class ClientProvidedBuildActionRunner extends AbstractClientProvidedBuildActionRunner implements BuildActionRunner {
    private final PayloadSerializer payloadSerializer;

    public ClientProvidedBuildActionRunner(BuildControllerFactory buildControllerFactory,
                                           PayloadSerializer payloadSerializer) {
        super(buildControllerFactory);
        this.payloadSerializer = payloadSerializer;
    }

    @Override
    public Result run(BuildAction action, BuildTreeLifecycleController buildController) {
        if (!(action instanceof ClientProvidedBuildAction)) {
            return Result.nothing();
        }

        ClientProvidedBuildAction clientProvidedBuildAction = (ClientProvidedBuildAction) action;

        Object clientAction = payloadSerializer.deserialize(clientProvidedBuildAction.getAction());

        return runClientAction(new ClientActionImpl(clientAction, action, payloadSerializer), buildController);
    }

    private static class ClientActionImpl implements ClientAction {
        private final Object clientAction;
        private final BuildAction action;
        private final PayloadSerializer payloadSerializer;

        public ClientActionImpl(Object clientAction, BuildAction action, PayloadSerializer payloadSerializer) {
            this.clientAction = clientAction;
            this.action = action;
            this.payloadSerializer = payloadSerializer;
        }

        @Override
        public Object getProjectsEvaluatedAction() {
            return null;
        }

        @Override
        public Object getBuildFinishedAction() {
            return clientAction;
        }

        @Override
        public Object collectActionResult(Object result, PhasedActionResult.Phase phase) {
            return payloadSerializer.serialize(result);
        }

        @Override
        public boolean isRunTasks() {
            return action.isRunTasks();
        }
    }
}
