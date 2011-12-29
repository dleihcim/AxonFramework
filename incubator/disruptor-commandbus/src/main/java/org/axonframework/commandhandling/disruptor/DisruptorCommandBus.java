/*
 * Copyright (c) 2010-2011. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.commandhandling.disruptor;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventsourcing.AggregateFactory;
import org.axonframework.eventsourcing.EventSourcedAggregateRoot;
import org.axonframework.eventstore.EventStore;
import org.axonframework.repository.Repository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * @author Allard Buijze
 * @since 2.0
 */
public class DisruptorCommandBus<T extends EventSourcedAggregateRoot> implements CommandBus, Repository<T> {

    /**
     * The name of the MetaData property containing the aggregate identifier of the command's target.
     */
    public static final String TARGET_AGGREGATE_PROPERTY = "target.aggregate";

    private static final ThreadGroup DISRUPTOR_THREAD_GROUP = new ThreadGroup("Disruptor");

    private final ConcurrentMap<Class<?>, CommandHandler<?>> commandHandlers = new ConcurrentHashMap<Class<?>, CommandHandler<?>>();
    private final Disruptor<CommandHandlingEntry<T>> disruptor;
    private final CommandHandlerInvoker<T> commandHandlerInvoker;
    private final ExecutorService executorService;

    /**
     * Initialize the DisruptorCommandBus with given resources, using default configuration settings. Uses a Blocking
     * WaitStrategy on a RingBuffer of size 4096. The (3) Threads required for command execution are created
     * immediately.
     *
     * @param aggregateFactory The factory providing uninitialized Aggregate instances for event sourcing
     * @param eventStore       The EventStore where generated events must be stored
     * @param eventBus         The EventBus where generated events must be published
     */
    public DisruptorCommandBus(AggregateFactory<T> aggregateFactory, EventStore eventStore, EventBus eventBus) {
        this(aggregateFactory, eventStore, eventBus, new RingBufferConfiguration());
    }

    /**
     * Initialize the DisruptorCommandBus with given resources and settings. The 3 Threads required for command
     * execution are immediately requested from the Configuration's Executor, if any. Otherwise, they are created.
     *
     * @param aggregateFactory The factory providing uninitialized Aggregate instances for event sourcing
     * @param eventStore       The EventStore where generated events must be stored
     * @param eventBus         The EventBus where generated events must be published
     * @param configuration    The configuration for the command bus
     */
    @SuppressWarnings("unchecked")
    public DisruptorCommandBus(AggregateFactory<T> aggregateFactory, EventStore eventStore, EventBus eventBus,
                               RingBufferConfiguration configuration) {
        Executor executor = configuration.getExecutor();
        if (executor == null) {
            executorService = Executors.newCachedThreadPool(new AxonThreadFactory(DISRUPTOR_THREAD_GROUP));
            executor = executorService;
        } else {
            executorService = null;
        }
        disruptor = new Disruptor<CommandHandlingEntry<T>>(new CommandHandlingEntryFactory<T>(),
                                                           executor,
                                                           configuration.getClaimStrategy(),
                                                           configuration.getWaitStrategy());
        commandHandlerInvoker = new CommandHandlerInvoker<T>();
        disruptor.handleEventsWith(new CommandHandlerPreFetcher<T>(eventStore,
                                                                   aggregateFactory,
                                                                   commandHandlers,
                                                                   configuration.getInterceptors()))
                 .then(commandHandlerInvoker)
                 .then(new EventPublisher<T>(aggregateFactory.getTypeIdentifier(), eventStore, eventBus));
        disruptor.start();
    }

    @Override
    public void dispatch(final CommandMessage<?> command) {
        RingBuffer<CommandHandlingEntry<T>> ringBuffer = disruptor.getRingBuffer();
        Object aggregateIdentifier = command.getMetaData().get(TARGET_AGGREGATE_PROPERTY);
        long sequence = ringBuffer.next();
        CommandHandlingEntry event = ringBuffer.get(sequence);
        event.reset(command, aggregateIdentifier);
        ringBuffer.publish(sequence);
    }

    @Override
    public <R> void dispatch(CommandMessage<?> command, CommandCallback<R> callback) {
        throw new UnsupportedOperationException("This CommandBus instance does not support callbacks, yet.");
    }

    @Override
    public <C> void subscribe(Class<C> commandType, CommandHandler<? super C> handler) {
        commandHandlers.put(commandType, handler);
    }

    @Override
    public <C> boolean unsubscribe(Class<C> commandType, CommandHandler<? super C> handler) {
        return commandHandlers.remove(commandType, handler);
    }

    @Override
    public T load(Object aggregateIdentifier, Long expectedVersion) {
        return load(aggregateIdentifier);
    }

    @Override
    public T load(Object aggregateIdentifier) {
        T aggregateRoot = commandHandlerInvoker.getPreLoadedAggregate();
        if (aggregateRoot.getIdentifier().equals(aggregateIdentifier)) {
            return aggregateRoot;
        } else {
            throw new UnsupportedOperationException("Not supported to load another aggregate than the pre-loaded one");
        }
    }

    @Override
    public void add(T aggregate) {
        // not necessary
    }

    /**
     * Shuts down the command bus. It no longer accepts new commands, and finishes processing commands that have
     * already been published. This method will not shut down any executor that has been provided as part of the
     * Configuration.
     */
    public void stop() {
        disruptor.shutdown();
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    private static class CommandHandlingEntryFactory<T extends EventSourcedAggregateRoot>
            implements EventFactory<CommandHandlingEntry<T>> {

        @Override
        public CommandHandlingEntry<T> newInstance() {
            return new CommandHandlingEntry<T>();
        }
    }


    private static class AxonThreadFactory implements ThreadFactory {

        private final ThreadGroup groupName;

        public AxonThreadFactory(ThreadGroup groupName) {
            this.groupName = groupName;
        }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(groupName, r);
        }
    }
}
