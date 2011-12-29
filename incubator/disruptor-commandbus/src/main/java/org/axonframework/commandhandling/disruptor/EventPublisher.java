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

import com.lmax.disruptor.EventHandler;
import org.axonframework.domain.EventMessage;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventsourcing.EventSourcedAggregateRoot;
import org.axonframework.eventstore.EventStore;

import java.util.Iterator;

/**
 * Component of the DisruptorCommandBus that stores and publishes events generated by the command's execution.
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class EventPublisher<T extends EventSourcedAggregateRoot> implements EventHandler<CommandHandlingEntry<T>> {

    private final EventStore eventStore;
    private final String aggregateType;
    private final EventBus eventBus;

    /**
     * Initializes the EventPublisher to publish Events to the given <code>eventStore</code> and <code>eventBus</code>
     * for aggregate of given <code>aggregateType</code>.
     *
     * @param aggregateType The type of aggregate to store the events for
     * @param eventStore    The EventStore persisting the generated events
     * @param eventBus      The EventBus to publish events on
     */
    public EventPublisher(String aggregateType, EventStore eventStore, EventBus eventBus) {
        this.eventStore = eventStore;
        this.aggregateType = aggregateType;
        this.eventBus = eventBus;
    }

    @Override
    public void onEvent(CommandHandlingEntry<T> entry, long sequence, boolean endOfBatch) throws Exception {
        DisruptorUnitOfWork unitOfWork = entry.getUnitOfWork();
        eventStore.appendEvents(aggregateType, unitOfWork.getEventsToStore());
        Iterator<EventMessage> eventsToPublish = unitOfWork.getEventsToPublish().iterator();
        while (eventBus != null && eventsToPublish.hasNext()) {
            eventBus.publish(eventsToPublish.next());
        }
    }
}
