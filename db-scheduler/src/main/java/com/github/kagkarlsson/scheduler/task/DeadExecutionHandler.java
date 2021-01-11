/**
 * Copyright (C) Gustav Karlsson
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
package com.github.kagkarlsson.scheduler.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

public interface DeadExecutionHandler<T> {
    void deadExecution(Execution execution, ExecutionOperations<T> executionOperations);

    class ReviveDeadExecution<T> implements DeadExecutionHandler<T> {
        private static final Logger LOG = LoggerFactory.getLogger(ReviveDeadExecution.class);

        @Override
        public void deadExecution(Execution execution, ExecutionOperations<T> executionOperations) {
            final Instant now = Instant.now();
            LOG.info("Reviving dead execution: " + execution + " to " + now);
            executionOperations.reschedule(new ExecutionComplete(execution, now, now, ExecutionComplete.Result.FAILED, null), now);
        }
    }

    class CancelDeadExecution<T> implements DeadExecutionHandler<T> {
        private static final Logger LOG = LoggerFactory.getLogger(ReviveDeadExecution.class);

        @Override
        public void deadExecution(Execution execution, ExecutionOperations<T> executionOperations) {
            LOG.warn("Cancelling dead execution: " + execution);
            executionOperations.stop();
        }
    }

    class MarkDeadExecution<T> implements DeadExecutionHandler<T> {
        private static final Logger LOG = LoggerFactory.getLogger(ReviveDeadExecution.class);

        @Override
        public void deadExecution(Execution execution, ExecutionOperations<T> executionOperations) {
            LOG.warn("Marking dead execution: " + execution);
            executionOperations.dead();
        }
    }
}
