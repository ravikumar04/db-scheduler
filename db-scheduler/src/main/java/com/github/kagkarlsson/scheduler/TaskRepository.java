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
package com.github.kagkarlsson.scheduler;

import com.github.kagkarlsson.scheduler.task.Execution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface TaskRepository {

    boolean createIfNotExists(Execution execution);

    List<Execution> getDue(Instant now, int limit);

    void getScheduledExecutions(ScheduledExecutionsFilter filter, Consumer<Execution> consumer);

    void getScheduledExecutions(ScheduledExecutionsFilter filter, String taskName, Consumer<Execution> consumer);

    void remove(Execution execution);

    boolean reschedule(Execution execution, Instant nextExecutionTime, Instant lastSuccess, Instant lastFailure, int consecutiveFailures);

    boolean reschedule(Execution execution, Instant nextExecutionTime, Object newData, Instant lastSuccess, Instant lastFailure, int consecutiveFailures);

    Optional<Execution> pick(Execution e, Instant timePicked);

    List<Execution> getDeadExecutions(Instant olderThan);

    void updateHeartbeat(Execution execution, Instant heartbeatTime);

    List<Execution> getExecutionsFailingLongerThan(Duration interval);

    Optional<Execution> getExecution(String taskName, String taskInstanceId);

    int removeExecutions(String taskName);

    void markInitiated(Execution execution);

    void markCompleted(Execution execution);

    void markFailed(Execution execution);

    void markCancelled(Execution execution);

    void markDead(Execution execution);

    int markDead(String taskName);
}
