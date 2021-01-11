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

import com.github.kagkarlsson.jdbc.JdbcRunner;
import com.github.kagkarlsson.jdbc.ResultSetMapper;
import com.github.kagkarlsson.jdbc.SQLRuntimeException;
import com.github.kagkarlsson.scheduler.TaskResolver.UnresolvedTask;
import com.github.kagkarlsson.scheduler.enums.TaskStatus;
import com.github.kagkarlsson.scheduler.jdbc.AndCondition;
import com.github.kagkarlsson.scheduler.jdbc.AutodetectJdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.QueryBuilder;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.github.kagkarlsson.scheduler.StringUtils.truncate;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@SuppressWarnings("rawtypes")
public class JdbcTaskRepository implements TaskRepository {

    public static final String DEFAULT_TABLE_NAME = "scheduled_tasks";

    private static final Logger LOG = LoggerFactory.getLogger(JdbcTaskRepository.class);
    private final TaskResolver taskResolver;
    private final SchedulerName schedulerSchedulerName;
    private final JdbcRunner jdbcRunner;
    private final Serializer serializer;
    private final String tableName;
    private final JdbcCustomization jdbcCustomization;

    public JdbcTaskRepository(DataSource dataSource, boolean commitWhenAutocommitDisabled, String tableName, TaskResolver taskResolver, SchedulerName schedulerSchedulerName) {
        this(dataSource, commitWhenAutocommitDisabled, new AutodetectJdbcCustomization(dataSource), tableName, taskResolver, schedulerSchedulerName, Serializer.DEFAULT_JAVA_SERIALIZER);
    }

    public JdbcTaskRepository(DataSource dataSource, boolean commitWhenAutocommitDisabled, JdbcCustomization jdbcCustomization, String tableName, TaskResolver taskResolver, SchedulerName schedulerSchedulerName) {
        this(dataSource, commitWhenAutocommitDisabled, jdbcCustomization, tableName, taskResolver, schedulerSchedulerName, Serializer.DEFAULT_JAVA_SERIALIZER);
    }

    public JdbcTaskRepository(DataSource dataSource, boolean commitWhenAutocommitDisabled, JdbcCustomization jdbcCustomization, String tableName, TaskResolver taskResolver, SchedulerName schedulerSchedulerName, Serializer serializer) {
        this.tableName = tableName;
        this.taskResolver = taskResolver;
        this.schedulerSchedulerName = schedulerSchedulerName;
        this.jdbcRunner = new JdbcRunner(dataSource, commitWhenAutocommitDisabled);
        this.serializer = serializer;
        this.jdbcCustomization = jdbcCustomization;
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public boolean createIfNotExists(Execution execution) {
        try {
            Optional<Execution> existingExecution = getExecution(execution.taskInstance);
            if (existingExecution.isPresent()) {
                LOG.debug("Execution not created, it already exists. Due: {}", existingExecution.get().executionTime);
                return false;
            }

            jdbcRunner.execute(
                "insert into " + tableName + "(task_name, task_instance, task_data, execution_time, picked, version, status) values(?, ?, ?, ?, ?, ?, ?)",
                (PreparedStatement p) -> {
                    p.setString(1, execution.taskInstance.getTaskName());
                    p.setString(2, execution.taskInstance.getId());
                    p.setObject(3, serializer.serialize(execution.taskInstance.getData()));
                    jdbcCustomization.setInstant(p, 4, execution.executionTime);
                    p.setBoolean(5, false);
                    p.setLong(6, 1L);
                    p.setString(7,TaskStatus.SUBMITTED.name());
                });
            return true;

        } catch (SQLRuntimeException e) {
            LOG.debug("Exception when inserting execution. Assuming it to be a constraint violation.", e);
            Optional<Execution> existingExecution = getExecution(execution.taskInstance);
            if (!existingExecution.isPresent()) {
                throw new RuntimeException("Failed to add new execution.", e);
            }
            LOG.debug("Execution not created, another thread created it.");
            return false;
        }
    }

    @Override
    public void getScheduledExecutions(ScheduledExecutionsFilter filter, Consumer<Execution> consumer) {
        UnresolvedFilter unresolvedFilter = new UnresolvedFilter(taskResolver.getUnresolved());

        QueryBuilder q = queryForFilter(filter);
        if (unresolvedFilter.isActive()) {
            q.andCondition(unresolvedFilter);
        }

        jdbcRunner.query(q.getQuery(), q.getPreparedStatementSetter(), new ExecutionResultSetConsumer(consumer));
    }

    @Override
    public void getScheduledExecutions(ScheduledExecutionsFilter filter, String taskName, Consumer<Execution> consumer) {
        QueryBuilder q = queryForFilter(filter);
        q.andCondition(new TaskCondition(taskName));

        jdbcRunner.query(q.getQuery(),
            q.getPreparedStatementSetter(),
            new ExecutionResultSetConsumer(consumer)
        );
    }

    @Override
    public List<Execution> getDue(Instant now, int limit) {
        final UnresolvedFilter unresolvedFilter = new UnresolvedFilter(taskResolver.getUnresolved());

        return jdbcRunner.query(
            "select * from " + tableName + " where picked = ? and execution_time <= ? " + unresolvedFilter.andCondition() + " order by execution_time asc",
            (PreparedStatement p) -> {
                int index = 1;
                p.setBoolean(index++, false);
                jdbcCustomization.setInstant(p, index++, now);
                unresolvedFilter.setParameters(p, index);
                p.setMaxRows(limit);
            },
            new ExecutionResultSetMapper()
        );
    }

    @Override
    public void remove(Execution execution) {

        final int removed = jdbcRunner.execute("delete from " + tableName + " where task_name = ? and task_instance = ? and version = ?",
            ps -> {
                ps.setString(1, execution.taskInstance.getTaskName());
                ps.setString(2, execution.taskInstance.getId());
                ps.setLong(3, execution.version);
            }
        );

        if (removed != 1) {
            throw new RuntimeException("Expected one execution to be removed, but removed " + removed + ". Indicates a bug.");
        }
    }

    @Override
    public boolean reschedule(Execution execution, Instant nextExecutionTime, Instant lastSuccess, Instant lastFailure, int consecutiveFailures) {
        return rescheduleInternal(execution, nextExecutionTime, null, lastSuccess, lastFailure, consecutiveFailures);
    }

    @Override
    public boolean reschedule(Execution execution, Instant nextExecutionTime, Object newData, Instant lastSuccess, Instant lastFailure, int consecutiveFailures) {
        return rescheduleInternal(execution, nextExecutionTime, new NewData(newData), lastSuccess, lastFailure, consecutiveFailures);
    }

    private boolean rescheduleInternal(Execution execution, Instant nextExecutionTime, NewData newData, Instant lastSuccess, Instant lastFailure, int consecutiveFailures) {
        final int updated = jdbcRunner.execute(
            "update " + tableName + " set " +
                "picked = ?, " +
                "picked_by = ?, " +
                "last_heartbeat = ?, " +
                "last_success = ?, " +
                "last_failure = ?, " +
                "consecutive_failures = ?, " +
                "execution_time = ?, " +
                (newData != null ? "task_data = ?, " : "") +
                "version = version + 1, " +
                "status = ? " +
                "where task_name = ? " +
                "and task_instance = ? " +
                "and version = ?",
            ps -> {
                int index = 1;
                ps.setBoolean(index++, false);
                ps.setString(index++, null);
                jdbcCustomization.setInstant(ps, index++, null);
                jdbcCustomization.setInstant(ps, index++, ofNullable(lastSuccess).orElse(null));
                jdbcCustomization.setInstant(ps, index++, ofNullable(lastFailure).orElse(null));
                ps.setInt(index++, consecutiveFailures);
                jdbcCustomization.setInstant(ps, index++, nextExecutionTime);
                if (newData != null) {
                    // may cause datbase-specific problems, might have to use setNull instead
                    ps.setObject(index++, serializer.serialize(newData.data));
                }
                ps.setString(index++, TaskStatus.RESCHEDULED.name());
                ps.setString(index++, execution.taskInstance.getTaskName());
                ps.setString(index++, execution.taskInstance.getId());
                ps.setLong(index++, execution.version);
            });

        if (updated != 1) {
            throw new RuntimeException("Expected one execution to be updated, but updated " + updated + ". Indicates a bug.");
        }
        return updated > 0;
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public Optional<Execution> pick(Execution e, Instant timePicked) {
        final int updated = jdbcRunner.execute(
            "update " + tableName + " set picked = ?, picked_by = ?, last_heartbeat = ?, version = version + 1," +
                "status = ? " +
                "where picked = ? " +
                "and task_name = ? " +
                "and task_instance = ? " +
                "and version = ?",
            ps -> {
                ps.setBoolean(1, true);
                ps.setString(2, truncate(schedulerSchedulerName.getName(), 50));
                jdbcCustomization.setInstant(ps, 3, timePicked);
                ps.setString(4,TaskStatus.INITIATED.name());
                ps.setBoolean(5, false);
                ps.setString(6, e.taskInstance.getTaskName());
                ps.setString(7, e.taskInstance.getId());
                ps.setLong(8, e.version);
            });

        if (updated == 0) {
            LOG.trace("Failed to pick execution. It must have been picked by another scheduler.", e);
            return Optional.empty();
        } else if (updated == 1) {
            final Optional<Execution> pickedExecution = getExecution(e.taskInstance);
            if (!pickedExecution.isPresent()) {
                throw new IllegalStateException("Unable to find picked execution. Must have been deleted by another thread. Indicates a bug.");
            } else if (!pickedExecution.get().isPicked()) {
                throw new IllegalStateException("Picked execution does not have expected state in database: " + pickedExecution.get());
            }
            return pickedExecution;
        } else {
            throw new IllegalStateException("Updated multiple rows when picking single execution. Should never happen since name and id is primary key. Execution: " + e);
        }
    }

    @Override
    public List<Execution> getDeadExecutions(Instant olderThan) {
        final UnresolvedFilter unresolvedFilter = new UnresolvedFilter(taskResolver.getUnresolved());
        return jdbcRunner.query(
            "select * from " + tableName + " where picked = ? and last_heartbeat <= ? " + unresolvedFilter.andCondition() + " order by last_heartbeat asc",
            (PreparedStatement p) -> {
                int index = 1;
                p.setBoolean(index++, true);
                jdbcCustomization.setInstant(p, index++, olderThan);
                unresolvedFilter.setParameters(p, index);
            },
            new ExecutionResultSetMapper()
        );
    }

    @Override
    public void updateHeartbeat(Execution e, Instant newHeartbeat) {

        final int updated = jdbcRunner.execute(
            "update " + tableName + " set last_heartbeat = ? " +
                "where task_name = ? " +
                "and task_instance = ? " +
                "and version = ?",
            ps -> {
                jdbcCustomization.setInstant(ps, 1, newHeartbeat);
                ps.setString(2, e.taskInstance.getTaskName());
                ps.setString(3, e.taskInstance.getId());
                ps.setLong(4, e.version);
            });

        if (updated == 0) {
            LOG.trace("Did not update heartbeat. Execution must have been removed or rescheduled. {}", e);
        } else {
            if (updated > 1) {
                throw new IllegalStateException("Updated multiple rows updating heartbeat for execution. Should never happen since name and id is primary key. Execution: " + e);
            }
            LOG.debug("Updated heartbeat for execution: " + e);
        }
    }

    @Override
    public List<Execution> getExecutionsFailingLongerThan(Duration interval) {
        UnresolvedFilter unresolvedFilter = new UnresolvedFilter(taskResolver.getUnresolved());
        return jdbcRunner.query(
            "select * from " + tableName + " where " +
                "    ((last_success is null and last_failure is not null)" +
                "    or (last_failure is not null and last_success < ?)) " +
                unresolvedFilter.andCondition(),
            (PreparedStatement p) -> {
                int index = 1;
                jdbcCustomization.setInstant(p, index++, Instant.now().minus(interval));
                unresolvedFilter.setParameters(p, index);
            },
            new ExecutionResultSetMapper()
        );
    }

    public Optional<Execution> getExecution(TaskInstance taskInstance) {
        return getExecution(taskInstance.getTaskName(), taskInstance.getId());
    }

    public Optional<Execution> getExecution(String taskName, String taskInstanceId) {
        final List<Execution> executions = jdbcRunner.query(
            "select * from " + tableName + " where task_name = ? and task_instance = ?",
            (PreparedStatement p) -> {
                p.setString(1, taskName);
                p.setString(2, taskInstanceId);
            },
            new ExecutionResultSetMapper()
        );
        if (executions.size() > 1) {
            throw new RuntimeException(String.format("Found more than one matching execution for task name/id combination: '%s'/'%s'", taskName, taskInstanceId));
        }

        return executions.size() == 1 ? ofNullable(executions.get(0)) : Optional.empty();
    }

    @Override
    public int removeExecutions(String taskName) {
        return jdbcRunner.execute("delete from " + tableName + " where task_name = ?",
            (PreparedStatement p) -> {
                p.setString(1, taskName);
            });
    }


    @Override
    public void markInitiated(Execution execution) {
        updateStatus(execution,TaskStatus.INITIATED);
    }

    @Override
    public void markCompleted(Execution execution) {
        final int updated = jdbcRunner.execute(
            "update " + tableName + " set status = ?, last_success = ? " +
                "where task_name = ? " +
                "and task_instance = ? " +
                "and version = ?",
            ps -> {
                ps.setString(1, TaskStatus.COMPETED.name());
                jdbcCustomization.setInstant(ps, 2, Instant.now());
                ps.setString(3, execution.taskInstance.getTaskName());
                ps.setString(4, execution.taskInstance.getId());
                ps.setLong(5, execution.version);
            });

        if (updated == 0) {
            throw new IllegalStateException("Update failed. Should never happen since name and id is primary key. Execution: " + execution);
        }
        LOG.debug("{} Marked execution: {}",TaskStatus.COMPETED, execution);
    }

    @Override
    public void markFailed(Execution execution) {
        final int updated = jdbcRunner.execute(
            "update " + tableName + " set status = ?, last_failure = ? " +
                "where task_name = ? " +
                "and task_instance = ? " +
                "and version = ?",
            ps -> {
                ps.setString(1, TaskStatus.FAILED.name());
                jdbcCustomization.setInstant(ps, 2, Instant.now());
                ps.setString(3, execution.taskInstance.getTaskName());
                ps.setString(4, execution.taskInstance.getId());
                ps.setLong(5, execution.version);
            });

        if (updated == 0) {
            throw new IllegalStateException("Update failed. Should never happen since name and id is primary key. Execution: " + execution);
        }
        LOG.debug("{} Marked execution: {}",TaskStatus.FAILED, execution);
    }

    @Override
    public void markCancelled(Execution execution) {
        updateStatus(execution,TaskStatus.CANCELLED);
    }

    @Override
    public void markDead(Execution execution) {
        updateStatus(execution,TaskStatus.DEAD);
    }

    @Override
    public int markDead(String taskName) {
        return jdbcRunner.execute("update " + tableName + " set status = ? where task_name = ?",
            (PreparedStatement p) -> {
                p.setString(1, TaskStatus.DEAD.name());
                p.setString(2, taskName);
            });
    }

    private void updateStatus(Execution execution, TaskStatus status) {
        final int updated = jdbcRunner.execute(
            "update " + tableName + " set status = ? " +
                "where task_name = ? " +
                "and task_instance = ? " +
                "and version = ?",
            ps -> {
                ps.setString(1, status.name());
                ps.setString(2, execution.taskInstance.getTaskName());
                ps.setString(3, execution.taskInstance.getId());
                ps.setLong(4, execution.version);
            });

        if (updated == 0) {
            throw new IllegalStateException("Update failed. Should never happen since name and id is primary key. Execution: " + execution);
        }
        LOG.debug("{} Marked execution: {}",status, execution);
    }

    private QueryBuilder queryForFilter(ScheduledExecutionsFilter filter) {
        final QueryBuilder q = QueryBuilder.selectFromTable(tableName);

        filter.getPickedValue().ifPresent(value -> {
            q.andCondition(new PickedCondition(value));
        });

        q.orderBy("execution_time asc");
        return q;
    }


    private class ExecutionResultSetMapper implements ResultSetMapper<List<Execution>> {

        private final ArrayList<Execution> executions;

        private final ExecutionResultSetConsumer delegate;

        private ExecutionResultSetMapper() {
            this.executions = new ArrayList<>();
            this.delegate = new ExecutionResultSetConsumer(executions::add);
        }

        @Override
        public List<Execution> map(ResultSet resultSet) throws SQLException {
            this.delegate.map(resultSet);
            return this.executions;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private class ExecutionResultSetConsumer implements ResultSetMapper<Void> {

        private final Consumer<Execution> consumer;

        private ExecutionResultSetConsumer(Consumer<Execution> consumer) {
            this.consumer = consumer;
        }

        @Override
        public Void map(ResultSet rs) throws SQLException {

            while (rs.next()) {
                String taskName = rs.getString("task_name");
                Optional<Task> task = taskResolver.resolve(taskName);

                if (!task.isPresent()) {
                    LOG.warn("Failed to find implementation for task with name '{}'. Execution will be excluded from due. Either delete the execution from the database, or add an implementation for it. The scheduler may be configured to automatically delete unresolved tasks after a certain period of time.", taskName);
                    continue;
                }

                String instanceId = rs.getString("task_instance");
                byte[] data = rs.getBytes("task_data");

                Instant executionTime = jdbcCustomization.getInstant(rs, "execution_time");

                boolean picked = rs.getBoolean("picked");
                final String pickedBy = rs.getString("picked_by");
                Instant lastSuccess = jdbcCustomization.getInstant(rs, "last_success");
                Instant lastFailure = jdbcCustomization.getInstant(rs, "last_failure");
                int consecutiveFailures = rs.getInt("consecutive_failures"); // null-value is returned as 0 which is the preferred default
                Instant lastHeartbeat = jdbcCustomization.getInstant(rs, "last_heartbeat");
                long version = rs.getLong("version");
                String status = rs.getString("status");

                Supplier dataSupplier = memoize(() -> serializer.deserialize(task.get().getDataClass(), data));
                this.consumer.accept(new Execution(executionTime, new TaskInstance(taskName, instanceId, dataSupplier), picked, pickedBy, lastSuccess, lastFailure, consecutiveFailures, lastHeartbeat, version, status));
            }

            return null;
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> original) {
        return new Supplier<T>() {
            Supplier<T> delegate = this::firstTime;
            boolean initialized;

            public T get() {
                return delegate.get();
            }

            private synchronized T firstTime() {
                if (!initialized) {
                    T value = original.get();
                    delegate = () -> value;
                    initialized = true;
                }
                return delegate.get();
            }
        };
    }

    private static class NewData {
        private final Object data;

        NewData(Object data) {
            this.data = data;
        }
    }

    private static class UnresolvedFilter implements AndCondition {
        private final List<UnresolvedTask> unresolved;

        public UnresolvedFilter(List<UnresolvedTask> unresolved) {
            this.unresolved = unresolved;
        }

        public boolean isActive() {
            return !unresolved.isEmpty();
        }

        public String andCondition() {
            return unresolved.isEmpty() ? "" : "and " + getQueryPart();
        }

        public String getQueryPart() {
            return "task_name not in (" + unresolved.stream().map(ignored -> "?").collect(joining(",")) + ")";
        }

        public int setParameters(PreparedStatement p, int index) throws SQLException {
            final List<String> unresolvedTasknames = unresolved.stream().map(UnresolvedTask::getTaskName).collect(toList());
            for (String taskName : unresolvedTasknames) {
                p.setString(index++, taskName);
            }
            return index;
        }
    }

    private static class PickedCondition implements AndCondition {
        private final boolean value;

        public PickedCondition(boolean value) {
            this.value = value;
        }

        @Override
        public String getQueryPart() {
            return "picked = ?";
        }

        @Override
        public int setParameters(PreparedStatement p, int index) throws SQLException {
            p.setBoolean(index++, value);
            return index;
        }
    }

    private static class TaskCondition implements AndCondition {
        private final String value;

        public TaskCondition(String value) {
            this.value = value;
        }

        @Override
        public String getQueryPart() {
            return "task_name = ?";
        }

        @Override
        public int setParameters(PreparedStatement p, int index) throws SQLException {
            p.setString(index++, value);
            return index;
        }
    }
}
