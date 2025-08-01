/*
 * Copyright 2016-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.listener;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.FencedInstanceIdException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.jspecify.annotations.Nullable;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.log.LogAccessor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaResourceHolder;
import org.springframework.kafka.event.ConsumerFailedToStartEvent;
import org.springframework.kafka.event.ConsumerPartitionPausedEvent;
import org.springframework.kafka.event.ConsumerPartitionResumedEvent;
import org.springframework.kafka.event.ConsumerPausedEvent;
import org.springframework.kafka.event.ConsumerResumedEvent;
import org.springframework.kafka.event.ConsumerRetryAuthEvent;
import org.springframework.kafka.event.ConsumerRetryAuthSuccessfulEvent;
import org.springframework.kafka.event.ConsumerStartedEvent;
import org.springframework.kafka.event.ConsumerStartingEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent.Reason;
import org.springframework.kafka.event.ConsumerStoppingEvent;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.event.ListenerContainerNoLongerIdleEvent;
import org.springframework.kafka.event.ListenerContainerPartitionIdleEvent;
import org.springframework.kafka.event.ListenerContainerPartitionNoLongerIdleEvent;
import org.springframework.kafka.event.NonResponsiveConsumerEvent;
import org.springframework.kafka.listener.ConsumerSeekAware.ConsumerSeekCallback;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.ContainerProperties.AssignmentCommitOption;
import org.springframework.kafka.listener.ContainerProperties.EOSMode;
import org.springframework.kafka.listener.adapter.AsyncRepliesAware;
import org.springframework.kafka.listener.adapter.KafkaBackoffAwareMessageListenerAdapter;
import org.springframework.kafka.listener.adapter.RecordMessagingMessageListenerAdapter;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.LogIfLevelEnabled;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.kafka.support.TopicPartitionOffset.SeekPosition;
import org.springframework.kafka.support.micrometer.KafkaListenerObservation;
import org.springframework.kafka.support.micrometer.KafkaListenerObservation.DefaultKafkaListenerObservationConvention;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.springframework.kafka.support.micrometer.MicrometerHolder;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.kafka.transaction.KafkaAwareTransactionManager;
import org.springframework.scheduling.SchedulingAwareRunnable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Single-threaded Message listener container using the Java {@link Consumer} supporting
 * auto-partition assignment or user-configured assignment.
 * <p>
 * With the latter, initial partition offsets can be provided.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Gary Russell
 * @author Murali Reddy
 * @author Marius Bogoevici
 * @author Martin Dam
 * @author Artem Bilan
 * @author Loic Talhouarne
 * @author Vladimir Tsanev
 * @author Chen Binbin
 * @author Yang Qiju
 * @author Tom van den Berge
 * @author Lukasz Kaminski
 * @author Tomaz Fernandes
 * @author Francois Rosiere
 * @author Daniel Gentes
 * @author Soby Chacko
 * @author Wang Zhiyang
 * @author Raphael Rösch
 * @author Christian Mergenthaler
 * @author Mikael Carlstedt
 * @author Borahm Lee
 * @author Lokesh Alamuri
 * @author Sanghyeok An
 * @author Christian Fredriksson
 * @author Timofey Barabanov
 * @author Janek Lasocki-Biczysko
 */
public class KafkaMessageListenerContainer<K, V> // NOSONAR line count
		extends AbstractMessageListenerContainer<K, V> implements ConsumerPauseResumeEventPublisher {

	private static final String UNUSED = "unused";

	private static final String UNCHECKED = "unchecked";

	private static final String RAWTYPES = "rawtypes";

	private static final Map<String, Object> CONSUMER_CONFIG_DEFAULTS = ConsumerConfig.configDef().defaultValues();

	private final AbstractMessageListenerContainer<K, V> thisOrParentContainer;

	private final @Nullable TopicPartitionOffset @Nullable [] topicPartitions;

	private @Nullable String clientIdSuffix;

	private Runnable emergencyStop = () -> stopAbnormally(() -> {
	});

	@SuppressWarnings("NullAway.Init")
	private volatile ListenerConsumer listenerConsumer;

	@SuppressWarnings("NullAway.Init")
	private volatile CompletableFuture<Void> listenerConsumerFuture;

	private volatile CountDownLatch startLatch = new CountDownLatch(1);

	/**
	 * Construct an instance with the supplied configuration properties.
	 * @param consumerFactory the consumer factory.
	 * @param containerProperties the container properties.
	 */
	public KafkaMessageListenerContainer(ConsumerFactory<? super K, ? super V> consumerFactory,
			ContainerProperties containerProperties) {

		this(null, consumerFactory, containerProperties, (TopicPartitionOffset[]) null);
	}

	/**
	 * Construct an instance with the supplied configuration properties.
	 * @param container a delegating container (if this is a sub-container).
	 * @param consumerFactory the consumer factory.
	 * @param containerProperties the container properties.
	 */
	KafkaMessageListenerContainer(AbstractMessageListenerContainer<K, V> container,
			ConsumerFactory<? super K, ? super V> consumerFactory,
			ContainerProperties containerProperties) {

		this(container, consumerFactory, containerProperties, (TopicPartitionOffset[]) null);
	}

	/**
	 * Construct an instance with the supplied configuration properties and specific
	 * topics/partitions/initialOffsets.
	 * @param container a delegating container (if this is a sub-container).
	 * @param consumerFactory the consumer factory.
	 * @param containerProperties the container properties.
	 * @param topicPartitions the topics/partitions; duplicates are eliminated.
	 */
	KafkaMessageListenerContainer(@Nullable AbstractMessageListenerContainer<K, V> container,
			ConsumerFactory<? super K, ? super V> consumerFactory,
			ContainerProperties containerProperties, @Nullable TopicPartitionOffset @Nullable ... topicPartitions) {

		super(consumerFactory, containerProperties);
		Assert.notNull(consumerFactory, "A ConsumerFactory must be provided");
		this.thisOrParentContainer = container == null ? this : container;
		if (topicPartitions != null) {
			this.topicPartitions = Arrays.copyOf(topicPartitions, topicPartitions.length);
		}
		else {
			this.topicPartitions = containerProperties.getTopicPartitions();
		}
	}

	/**
	 * Set a {@link Runnable} to call whenever a fatal error occurs on the listener
	 * thread.
	 * @param emergencyStop the Runnable.
	 * @since 2.2.1
	 */
	public void setEmergencyStop(Runnable emergencyStop) {
		Assert.notNull(emergencyStop, "'emergencyStop' cannot be null");
		this.emergencyStop = emergencyStop;
	}

	/**
	 * Set a suffix to add to the {@code client.id} consumer property (if the consumer
	 * factory supports it).
	 * @param clientIdSuffix the suffix to add.
	 * @since 1.0.6
	 */
	public void setClientIdSuffix(String clientIdSuffix) {
		this.clientIdSuffix = clientIdSuffix;
	}

	/**
	 * Return the {@link TopicPartition}s currently assigned to this container,
	 * either explicitly or by Kafka; may be null if not assigned yet.
	 * @return the {@link TopicPartition}s currently assigned to this container,
	 * either explicitly or by Kafka; may be null if not assigned yet.
	 */
	@Override
	@Nullable
	public Collection<TopicPartition> getAssignedPartitions() {
		ListenerConsumer partitionsListenerConsumer = this.listenerConsumer;
		if (partitionsListenerConsumer != null) {
			if (partitionsListenerConsumer.definedPartitions != null) {
				return Collections.unmodifiableCollection(partitionsListenerConsumer.definedPartitions.keySet());
			}
			else if (partitionsListenerConsumer.assignedPartitions != null) {
				return Collections.unmodifiableCollection(partitionsListenerConsumer.assignedPartitions);
			}
		}
		return null;
	}

	@Override
	@Nullable
	public Map<String, Collection<TopicPartition>> getAssignmentsByClientId() {
		ListenerConsumer partitionsListenerConsumer = this.listenerConsumer;
		if (partitionsListenerConsumer != null) {
			return Collections.singletonMap(partitionsListenerConsumer.getClientId(), getAssignedPartitions());
		}
		return null;
	}

	@Override
	public boolean isContainerPaused() {
		return isPauseRequested() && this.listenerConsumer != null && this.listenerConsumer.isConsumerPaused();
	}

	@Override
	public boolean isPartitionPaused(TopicPartition topicPartition) {
		return this.listenerConsumer != null && this.listenerConsumer.isPartitionPaused(topicPartition);
	}

	@Override
	public boolean isInExpectedState() {
		return isRunning() || isStoppedNormally();
	}

	@Override
	public void enforceRebalance() {
		this.thisOrParentContainer.enforceRebalanceRequested.set(true);
		consumerWakeIfNecessary();
	}

	@Override
	public void pause() {
		super.pause();
		consumerWakeIfNecessary();
	}

	@Override
	public void resume() {
		super.resume();
		consumerWakeIfNecessary();
	}

	@Override
	public void resumePartition(TopicPartition topicPartition) {
		super.resumePartition(topicPartition);
		consumerWakeIfNecessary();
	}

	private void consumerWakeIfNecessary() {
		KafkaMessageListenerContainer<K, V>.ListenerConsumer consumer = this.listenerConsumer;
		if (consumer != null) {
			consumer.wakeIfNecessary();
		}
	}

	@Override
	public Map<String, Map<MetricName, ? extends Metric>> metrics() {
		ListenerConsumer listenerConsumerForMetrics = this.listenerConsumer;
		if (listenerConsumerForMetrics != null) {
			Map<MetricName, ? extends Metric> metrics = listenerConsumerForMetrics.consumer.metrics();
			return Collections.singletonMap(listenerConsumerForMetrics.getClientId(), metrics);
		}
		return Collections.emptyMap();
	}

	@Override
	protected void doStart() {
		if (isRunning()) {
			return;
		}
		if (this.clientIdSuffix == null) { // stand-alone container
			checkTopics();
		}
		ContainerProperties containerProperties = getContainerProperties();

		Object messageListener = containerProperties.getMessageListener();
		AsyncTaskExecutor consumerExecutor = containerProperties.getListenerTaskExecutor();
		if (consumerExecutor == null) {
			consumerExecutor = new SimpleAsyncTaskExecutor(
					(getBeanName() == null ? "" : getBeanName()) + "-C-");
			containerProperties.setListenerTaskExecutor(consumerExecutor);
		}
		GenericMessageListener<?> listener = (GenericMessageListener<?>) messageListener;
		Assert.state(listener != null, "'messageListener' cannot be null");
		ListenerType listenerType = determineListenerType(listener);
		ObservationRegistry observationRegistry = containerProperties.getObservationRegistry();
		if (observationRegistry.isNoop()) {
			ApplicationContext applicationContext = getApplicationContext();
			if (applicationContext != null && containerProperties.isObservationEnabled()) {
				ObservationRegistry reg = applicationContext.getBeanProvider(ObservationRegistry.class)
						.getIfUnique();
				if (reg != null) {
					observationRegistry = reg;
				}
			}
		}
		this.listenerConsumer = new ListenerConsumer(listener, listenerType, observationRegistry);
		setRunning(true);
		this.startLatch = new CountDownLatch(1);
		this.listenerConsumerFuture = consumerExecutor.submitCompletable(this.listenerConsumer);
		try {
			if (!this.startLatch.await(containerProperties.getConsumerStartTimeout().toMillis(),
					TimeUnit.MILLISECONDS)) {

				this.logger.error("Consumer thread failed to start - does the configured task executor "
						+ "have enough threads to support all containers and concurrency?");
				publishConsumerFailedToStart();
			}
		}
		catch (@SuppressWarnings(UNUSED) InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private ListenerType determineListenerType(GenericMessageListener<?> listener) {
		Object delegating = listener;
		while (delegating instanceof DelegatingMessageListener<?> dml) {
			delegating = dml.getDelegate();
		}
		return ListenerUtils.determineListenerType(delegating);
	}

	@Override
	protected void doStop(final Runnable callback, boolean normal) {
		if (isRunning()) {
			this.listenerConsumerFuture.whenComplete(new StopCallback(callback));
			setRunning(false);
			this.listenerConsumer.wakeIfNecessaryForStop();
			setStoppedNormally(normal);
		}
	}

	@Override
	public void childStopped(MessageListenerContainer child, Reason reason) {
		if (reason.equals(Reason.AUTH) && child.equals(this)
				&& getContainerProperties().isRestartAfterAuthExceptions()) {
			setStoppedNormally(true);
			start();
		}
	}

	private void publishIdlePartitionEvent(long idleTime, TopicPartition topicPartition, Consumer<K, V> consumer, boolean paused) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ListenerContainerPartitionIdleEvent(this,
					this.thisOrParentContainer, idleTime, getBeanName(), topicPartition, consumer, paused));
		}
	}

	private void publishNoLongerIdlePartitionEvent(long idleTime, Consumer<K, V> consumer, TopicPartition topicPartition) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ListenerContainerPartitionNoLongerIdleEvent(this,
					this.thisOrParentContainer, idleTime, getBeanName(), topicPartition, consumer));
		}
	}

	private void publishIdleContainerEvent(long idleTime, Consumer<?, ?> consumer, boolean paused) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ListenerContainerIdleEvent(this,
					this.thisOrParentContainer, idleTime, getBeanName(), getAssignedPartitions(), consumer, paused));
		}
	}

	private void publishNoLongerIdleContainerEvent(long idleTime, Consumer<?, ?> consumer) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ListenerContainerNoLongerIdleEvent(this,
					this.thisOrParentContainer, idleTime, getBeanName(), getAssignedPartitions(), consumer));
		}
	}

	private void publishNonResponsiveConsumerEvent(long timeSinceLastPoll, Consumer<?, ?> consumer) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(
					new NonResponsiveConsumerEvent(this, this.thisOrParentContainer, timeSinceLastPoll,
							getBeanName(), getAssignedPartitions(), consumer));
		}
	}

	@Override
	public void publishConsumerPausedEvent(Collection<TopicPartition> partitions, String reason) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerPausedEvent(this, this.thisOrParentContainer,
					Collections.unmodifiableCollection(partitions), reason));
		}
	}

	@Override
	public void publishConsumerResumedEvent(Collection<TopicPartition> partitions) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerResumedEvent(this, this.thisOrParentContainer,
					Collections.unmodifiableCollection(partitions)));
		}
	}

	private void publishConsumerPartitionPausedEvent(TopicPartition partition) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerPartitionPausedEvent(this, this.thisOrParentContainer,
					partition));
		}
	}

	private void publishConsumerPartitionResumedEvent(TopicPartition partition) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerPartitionResumedEvent(this, this.thisOrParentContainer,
					partition));
		}
	}

	private void publishConsumerStoppingEvent(Consumer<?, ?> consumer) {
		try {
			ApplicationEventPublisher publisher = getApplicationEventPublisher();
			if (publisher != null) {
				publisher.publishEvent(
						new ConsumerStoppingEvent(this, this.thisOrParentContainer, consumer, getAssignedPartitions()));
			}
		}
		catch (Exception e) {
			this.logger.error(e, "Failed to publish consumer stopping event");
		}
	}

	private void publishConsumerStoppedEvent(@Nullable Throwable throwable) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			Reason reason;
			if (throwable instanceof Error) {
				reason = Reason.ERROR;
			}
			else if (throwable instanceof StopAfterFenceException || throwable instanceof FencedInstanceIdException) {
				reason = Reason.FENCED;
			}
			else if (throwable instanceof AuthenticationException || throwable instanceof AuthorizationException) {
				reason = Reason.AUTH;
			}
			else if (throwable instanceof NoOffsetForPartitionException) {
				reason = Reason.NO_OFFSET;
			}
			else if (!this.isStoppedNormally()) {
				reason = Reason.ABNORMAL;
			}
			else {
				reason = Reason.NORMAL;
			}
			publisher.publishEvent(new ConsumerStoppedEvent(this, this.thisOrParentContainer,
					reason));
			this.thisOrParentContainer.childStopped(this, reason);
		}
	}

	private void publishConsumerStartingEvent() {
		this.startLatch.countDown();
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerStartingEvent(this, this.thisOrParentContainer));
		}
	}

	private void publishConsumerStartedEvent() {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerStartedEvent(this, this.thisOrParentContainer));
		}
	}

	private void publishConsumerFailedToStart() {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerFailedToStartEvent(this, this.thisOrParentContainer));
		}
	}

	private void publishRetryAuthEvent(Throwable throwable) {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			ConsumerRetryAuthEvent.Reason reason;
			if (throwable instanceof AuthenticationException) {
				reason = ConsumerRetryAuthEvent.Reason.AUTHENTICATION;
			}
			else if (throwable instanceof AuthorizationException) {
				reason = ConsumerRetryAuthEvent.Reason.AUTHORIZATION;
			}
			else {
				throw new IllegalArgumentException("Only Authentication or Authorization Exceptions are allowed", throwable);
			}
			publisher.publishEvent(new ConsumerRetryAuthEvent(this, this.thisOrParentContainer, reason));
		}
	}

	private void publishRetryAuthSuccessfulEvent() {
		ApplicationEventPublisher publisher = getApplicationEventPublisher();
		if (publisher != null) {
			publisher.publishEvent(new ConsumerRetryAuthSuccessfulEvent(this, this.thisOrParentContainer));
		}
	}

	@Override
	protected AbstractMessageListenerContainer<?, ?> parentOrThis() {
		return this.thisOrParentContainer;
	}

	@Override
	public String toString() {
		return "KafkaMessageListenerContainer [id=" + getBeanName()
				+ (this.clientIdSuffix != null ? ", clientIndex=" + this.clientIdSuffix : "")
				+ ", topicPartitions="
				+ (getAssignedPartitions() == null ? "none assigned" : getAssignedPartitions())
				+ "]";
	}

	private final class ListenerConsumer implements SchedulingAwareRunnable, ConsumerSeekCallback {

		private static final String COMMITTING = "Committing: ";

		private static final String ERROR_HANDLER_THREW_AN_EXCEPTION = "Error handler threw an exception";

		private final LogAccessor logger = KafkaMessageListenerContainer.this.logger; // NOSONAR hide

		private final ContainerProperties containerProperties = getContainerProperties();

		private final OffsetCommitCallback commitCallback = this.containerProperties.getCommitCallback() != null
				? this.containerProperties.getCommitCallback()
				: new LoggingCommitCallback();

		private final OffsetAndMetadataProvider offsetAndMetadataProvider = this.containerProperties.getOffsetAndMetadataProvider() == null
				?  (metadata, offset) -> new OffsetAndMetadata(offset)
				: this.containerProperties.getOffsetAndMetadataProvider();

		private final ListenerMetadata listenerMetadata = new DefaultListenerMetadata(KafkaMessageListenerContainer.this);

		private final Consumer<K, V> consumer;

		private final Map<TopicPartition, Long> offsets = new LinkedHashMap<>();

		private final Collection<TopicPartition> assignedPartitions = Collections.synchronizedSet(new LinkedHashSet<>());

		private final Map<TopicPartition, OffsetAndMetadata> lastCommits = new HashMap<>();

		private final Map<TopicPartition, Long> savedPositions = new HashMap<>();

		private final GenericMessageListener<?> genericListener;

		private final @Nullable ConsumerSeekAware consumerSeekAwareListener;

		private final @Nullable MessageListener<K, V> listener;

		private final @Nullable BatchMessageListener<K, V> batchListener;

		private final ListenerType listenerType;

		private final boolean isConsumerAwareListener;

		private final boolean isBatchListener;

		private final boolean wantsFullRecords;

		private final boolean wantsBatchRecoverAfterRollback;

		private final boolean asyncReplies;

		private final boolean autoCommit;

		private final AckMode ackMode;

		private final boolean isManualAck;

		private final boolean isCountAck;

		private final boolean isTimeOnlyAck;

		private final boolean isTimeAck;

		private final boolean isManualImmediateAck;

		private final boolean isAnyManualAck;

		private final boolean isRecordAck;

		private final BlockingQueue<ConsumerRecord<K, V>> acks = new LinkedBlockingQueue<>();

		private final BlockingQueue<TopicPartitionOffset> seeks = new LinkedBlockingQueue<>();

		private final @Nullable CommonErrorHandler commonErrorHandler;

		@Deprecated(since = "3.2", forRemoval = true)
		@SuppressWarnings("removal")
		private final @Nullable PlatformTransactionManager transactionManager =
				this.containerProperties.getKafkaAwareTransactionManager() != null ?
						this.containerProperties.getKafkaAwareTransactionManager() :
						this.containerProperties.getTransactionManager();

		private final @Nullable KafkaAwareTransactionManager<?, ?> kafkaTxManager =
				this.transactionManager instanceof KafkaAwareTransactionManager<?, ?> kafkaAwareTransactionManager ?
						kafkaAwareTransactionManager : null;

		private final @Nullable TransactionTemplate transactionTemplate;

		private final @Nullable String consumerGroupId = KafkaMessageListenerContainer.this.getGroupId();

		private final TaskScheduler taskScheduler;

		private final ScheduledFuture<?> monitorTask;

		private final LogIfLevelEnabled commitLogger = new LogIfLevelEnabled(this.logger,
				this.containerProperties.getCommitLogLevel());

		private final Duration pollTimeout = Duration.ofMillis(this.containerProperties.getPollTimeout());

		private final Duration pollTimeoutWhilePaused = this.containerProperties.getPollTimeoutWhilePaused();

		private final boolean checkNullKeyForExceptions;

		private final boolean checkNullValueForExceptions;

		private final boolean syncCommits = this.containerProperties.isSyncCommits();

		private final @Nullable Duration syncCommitTimeout;

		private final @Nullable RecordInterceptor<K, V> recordInterceptor =
				!isInterceptBeforeTx() || this.transactionManager == null
						? getRecordInterceptor()
						: null;

		private final @Nullable RecordInterceptor<K, V> earlyRecordInterceptor =
				isInterceptBeforeTx() && this.transactionManager != null
						? getRecordInterceptor()
						: null;

		private final @Nullable RecordInterceptor<K, V> commonRecordInterceptor = getRecordInterceptor();

		private final @Nullable BatchInterceptor<K, V> batchInterceptor =
				!isInterceptBeforeTx() || this.transactionManager == null
						? getBatchInterceptor()
						: null;

		private final @Nullable  BatchInterceptor<K, V> earlyBatchInterceptor =
				isInterceptBeforeTx() && this.transactionManager != null
						? getBatchInterceptor()
						: null;

		private final @Nullable BatchInterceptor<K, V> commonBatchInterceptor = getBatchInterceptor();

		private final @Nullable ThreadStateProcessor pollThreadStateProcessor;

		private final ConsumerSeekCallback seekCallback = new InitialOrIdleSeekCallback();

		private final long maxPollInterval;

		private final @Nullable MicrometerHolder micrometerHolder;

		private final boolean observationEnabled;

		private final AtomicBoolean polling = new AtomicBoolean();

		private final boolean subBatchPerPartition = this.containerProperties.isSubBatchPerPartition();

		private final @Nullable Duration authExceptionRetryInterval =
				this.containerProperties.getAuthExceptionRetryInterval();

		private final AssignmentCommitOption autoCommitOption = this.containerProperties.getAssignmentCommitOption();

		private final boolean commitCurrentOnAssignment;

		private final @Nullable DeliveryAttemptAware deliveryAttemptAware;

		private final EOSMode eosMode = this.containerProperties.getEosMode();

		private final Map<TopicPartition, OffsetAndMetadata> commitsDuringRebalance = new HashMap<>();

		private final @Nullable String clientId;

		private final boolean fixTxOffsets = this.containerProperties.isFixTxOffsets();

		private final boolean stopImmediate = this.containerProperties.isStopImmediate();

		private final Set<TopicPartition> pausedPartitions = new HashSet<>();

		private final @Nullable Map<TopicPartition, List<Long>> offsetsInThisBatch;

		private final @Nullable Map<TopicPartition, List<ConsumerRecord<K, V>>> deferredOffsets;

		private final Map<TopicPartition, Long> lastReceivePartition;

		private final Map<TopicPartition, Long> lastAlertPartition;

		private final Map<TopicPartition, Boolean> wasIdlePartition;

		private final byte[] listenerinfo = getListenerInfo();

		private final Header infoHeader = new RecordHeader(KafkaHeaders.LISTENER_INFO, this.listenerinfo);

		private final Set<TopicPartition> pausedForNack = new HashSet<>();

		private final boolean pauseImmediate = this.containerProperties.isPauseImmediate();

		private final ObservationRegistry observationRegistry;

		@Nullable
		private final KafkaAdmin kafkaAdmin;

		private final @Nullable Object bootstrapServers;

		@Nullable
		private final Function<ConsumerRecord<?, ?>, Map<String, String>> micrometerTagsProvider =
				this.containerProperties.getMicrometerTagsProvider();

		private @Nullable String clusterId;

		private @Nullable Map<TopicPartition, OffsetMetadata> definedPartitions;

		private int count;

		private long last = System.currentTimeMillis();

		private boolean fatalError;

		private boolean taskSchedulerExplicitlySet;

		private long lastReceive;

		private long lastAlertAt = this.lastReceive;

		private long nackSleepDurationMillis = -1;

		private long nackWakeTimeMillis;

		private int nackIndex;

		private @Nullable Iterator<TopicPartition> batchIterator;

		private @Nullable ConsumerRecords<K, V> lastBatch;

		private @Nullable Producer<?, ?> producer;

		private boolean wasIdle;

		private boolean batchFailed;

		private boolean pausedForAsyncAcks;

		private boolean receivedSome;

		private @Nullable ConsumerRecords<K, V> remainingRecords;

		private boolean pauseForPending;

		private boolean firstPoll;

		private volatile boolean consumerPaused;

		private volatile @Nullable Thread consumerThread;

		private volatile long lastPoll = System.currentTimeMillis();

		private final ConcurrentLinkedDeque<FailedRecordTuple<K, V>> failedRecords = new ConcurrentLinkedDeque<>();

		@SuppressWarnings(UNCHECKED)
		ListenerConsumer(GenericMessageListener<?> listener, ListenerType listenerType,
				ObservationRegistry observationRegistry) {

			this.asyncReplies = listener instanceof AsyncRepliesAware hmd && hmd.isAsyncReplies()
					|| this.containerProperties.isAsyncAcks();
			this.ackMode = determineAckMode();
			this.isCountAck = AckMode.COUNT.equals(this.ackMode)
					|| AckMode.COUNT_TIME.equals(this.ackMode);
			this.isTimeOnlyAck = AckMode.TIME.equals(this.ackMode);
			this.isTimeAck = this.isTimeOnlyAck
					|| AckMode.COUNT_TIME.equals(this.ackMode);
			this.isManualAck = AckMode.MANUAL.equals(this.ackMode);
			this.isManualImmediateAck = AckMode.MANUAL_IMMEDIATE.equals(this.ackMode);
			this.isAnyManualAck = this.isManualAck || this.isManualImmediateAck;
			this.isRecordAck = this.ackMode.equals(AckMode.RECORD);
			boolean isOutOfCommit = this.isAnyManualAck && this.asyncReplies;
			this.offsetsInThisBatch = isOutOfCommit ? new ConcurrentHashMap<>() : null;
			this.deferredOffsets = isOutOfCommit ? new ConcurrentHashMap<>() : null;

			this.observationRegistry = observationRegistry;
			Properties consumerProperties = propertiesFromConsumerPropertyOverrides();
			checkGroupInstance(consumerProperties, KafkaMessageListenerContainer.this.consumerFactory);
			this.autoCommit = determineAutoCommit(consumerProperties);
			this.consumer =
					KafkaMessageListenerContainer.this.consumerFactory.createConsumer(
							this.consumerGroupId,
							this.containerProperties.getClientId(),
							KafkaMessageListenerContainer.this.clientIdSuffix,
							consumerProperties);
			this.bootstrapServers = determineBootstrapServers(consumerProperties);

			this.clientId = determineClientId();
			this.transactionTemplate = determineTransactionTemplate();
			this.wantsBatchRecoverAfterRollback = this.containerProperties.isBatchRecoverAfterRollback();
			this.genericListener = listener;
			this.consumerSeekAwareListener = checkConsumerSeekAware(listener);
			this.commitCurrentOnAssignment = determineCommitCurrent(consumerProperties,
					KafkaMessageListenerContainer.this.consumerFactory.getConfigurationProperties());
			subscribeOrAssignTopics(this.consumer);
			if (listener instanceof BatchMessageListener) {
				this.listener = null;
				this.batchListener = (BatchMessageListener<K, V>) listener;
				this.isBatchListener = true;
				this.wantsFullRecords = this.batchListener.wantsPollResult();
				this.pollThreadStateProcessor = setUpPollProcessor(true);
				this.observationEnabled =  this.containerProperties.isObservationEnabled() && this.containerProperties.isRecordObservationsInBatch();
			}
			else if (listener instanceof MessageListener) {
				this.listener = (MessageListener<K, V>) listener;
				this.batchListener = null;
				this.isBatchListener = false;
				this.wantsFullRecords = false;
				this.pollThreadStateProcessor = setUpPollProcessor(false);
				this.observationEnabled = this.containerProperties.isObservationEnabled();

				if (!AopUtils.isAopProxy(this.genericListener) &&
					this.genericListener instanceof KafkaBackoffAwareMessageListenerAdapter<?, ?>) {
					KafkaBackoffAwareMessageListenerAdapter<K, V> genListener =
							(KafkaBackoffAwareMessageListenerAdapter<K, V>) this.genericListener;
					if (genListener.getDelegate() instanceof RecordMessagingMessageListenerAdapter<K, V> adapterListener) {
						// This means that the async retry feature is supported only for SingleRecordListener with @RetryableTopic.
						adapterListener.setCallbackForAsyncFailure(this::callbackForAsyncFailure);
					}
				}
			}
			else {
				throw new IllegalArgumentException("Listener must be one of 'MessageListener', "
						+ "'BatchMessageListener', or the variants that are consumer aware and/or "
						+ "Acknowledging not " + listener.getClass().getName());
			}
			this.listenerType = listenerType;
			this.isConsumerAwareListener = listenerType.equals(ListenerType.ACKNOWLEDGING_CONSUMER_AWARE)
					|| listenerType.equals(ListenerType.CONSUMER_AWARE);
			this.commonErrorHandler = determineCommonErrorHandler();
			Assert.state(!this.isBatchListener || !this.isRecordAck,
					"Cannot use AckMode.RECORD with a batch listener");
			if (this.containerProperties.getScheduler() != null) {
				this.taskScheduler = this.containerProperties.getScheduler();
				this.taskSchedulerExplicitlySet = true;
			}
			else {
				ThreadPoolTaskScheduler threadPoolTaskScheduler = new ThreadPoolTaskScheduler();
				threadPoolTaskScheduler.initialize();
				this.taskScheduler = threadPoolTaskScheduler;
			}
			this.monitorTask = this.taskScheduler.scheduleAtFixedRate(this::checkConsumer, // NOSONAR
					Duration.ofSeconds(this.containerProperties.getMonitorInterval()));
			if (this.containerProperties.isLogContainerConfig()) {
				this.logger.info(toString());
			}
			ApplicationContext applicationContext = getApplicationContext();
			ClassLoader classLoader = applicationContext == null
					? getClass().getClassLoader()
					: applicationContext.getClassLoader();
			this.checkNullKeyForExceptions = this.containerProperties.isCheckDeserExWhenKeyNull()
					|| ErrorHandlingUtils.checkDeserializer(KafkaMessageListenerContainer.this.consumerFactory,
							consumerProperties, false, classLoader);
			this.checkNullValueForExceptions = this.containerProperties.isCheckDeserExWhenValueNull()
					|| ErrorHandlingUtils.checkDeserializer(KafkaMessageListenerContainer.this.consumerFactory,
							consumerProperties, true, classLoader);
			this.syncCommitTimeout = determineSyncCommitTimeout();
			if (this.containerProperties.getSyncCommitTimeout() == null) {
				// update the property, so we can use it directly from code elsewhere
				this.containerProperties.setSyncCommitTimeout(this.syncCommitTimeout);
				if (KafkaMessageListenerContainer.this.thisOrParentContainer != null) {
					KafkaMessageListenerContainer.this.thisOrParentContainer
							.getContainerProperties()
							.setSyncCommitTimeout(this.syncCommitTimeout);
				}
			}
			this.maxPollInterval = obtainMaxPollInterval(consumerProperties);
			this.micrometerHolder = obtainMicrometerHolder();
			this.deliveryAttemptAware = setupDeliveryAttemptAware();
			this.lastReceivePartition = new HashMap<>();
			this.lastAlertPartition = new HashMap<>();
			this.wasIdlePartition = new HashMap<>();
			this.kafkaAdmin = obtainAdmin();

			if (isListenerAdapterObservationAware()) {
				RecordMessagingMessageListenerAdapter<?, ?> recordMessagingMessageListenerAdapter = (RecordMessagingMessageListenerAdapter<?, ?>) this.listener;
				if (recordMessagingMessageListenerAdapter != null) {
					recordMessagingMessageListenerAdapter.setObservationRegistry(observationRegistry);
				}
			}
		}

		private AckMode determineAckMode() {
			AckMode ackMode = this.containerProperties.getAckMode();
			if (this.consumerGroupId == null && KafkaMessageListenerContainer.this.topicPartitions != null) {
				ackMode = AckMode.MANUAL;
			}
			if (this.asyncReplies && !(AckMode.MANUAL_IMMEDIATE.equals(ackMode) || AckMode.MANUAL.equals(ackMode))) {
				ackMode = AckMode.MANUAL;
			}
			return ackMode;
		}

		@Nullable
		private Object determineBootstrapServers(Properties consumerProperties) {
			Object servers = consumerProperties.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
			if (servers == null) {
				servers = KafkaMessageListenerContainer.this.consumerFactory.getConfigurationProperties()
						.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
			}
			return servers;
		}

		@Nullable
		private KafkaAdmin obtainAdmin() {
			KafkaAdmin customAdmin = KafkaMessageListenerContainer.this.thisOrParentContainer.getKafkaAdmin();
			if (customAdmin == null && this.observationEnabled) {
				ApplicationContext applicationContext = getApplicationContext();
				if (applicationContext != null) {
					KafkaAdmin admin = applicationContext.getBeanProvider(KafkaAdmin.class).getIfUnique();
					if (admin != null) {
						Map<String, Object> props = new HashMap<>(admin.getConfigurationProperties());
						Object bootstrapServer = props.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
						if (bootstrapServer != null && !bootstrapServer.equals(this.bootstrapServers)) {
							props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServers);
							int opTo = admin.getOperationTimeout();
							admin = new KafkaAdmin(props);
							admin.setOperationTimeout(opTo);
						}
					}
					return admin;
				}
			}
			else {
				return customAdmin;
			}
			return null;
		}

		@Nullable
		private String clusterId() {
			if (this.kafkaAdmin != null && this.clusterId == null) {
				obtainClusterId();
			}
			return this.clusterId;
		}

		private void obtainClusterId() {
			if (this.kafkaAdmin != null) {
				this.clusterId = this.kafkaAdmin.clusterId();
			}
		}

		@Nullable
		private ThreadStateProcessor setUpPollProcessor(boolean batch) {
			return batch ? this.commonBatchInterceptor : this.commonRecordInterceptor;
		}

		@Nullable
		private CommonErrorHandler determineCommonErrorHandler() {
			CommonErrorHandler common = getCommonErrorHandler();
			if (common == null && this.transactionManager == null) {
				common = new DefaultErrorHandler();
			}
			return common;
		}

		@Nullable
		String getClientId() {
			return this.clientId;
		}

		private @Nullable String determineClientId() {
			Map<MetricName, ? extends Metric> metrics = this.consumer.metrics();
			Iterator<MetricName> metricIterator = metrics.keySet().iterator();
			if (metricIterator.hasNext()) {
				return metricIterator.next().tags().get("client-id");
			}
			return "unknown.client.id";
		}

		private void checkGroupInstance(Properties properties, ConsumerFactory<K, V> consumerFactory) {
			String groupInstance = properties.getProperty(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
			if (!StringUtils.hasText(groupInstance)) {
				Object factoryConfig = consumerFactory.getConfigurationProperties()
						.get(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
				if (factoryConfig instanceof String str) {
					groupInstance = str;
				}
			}
			if (StringUtils.hasText(KafkaMessageListenerContainer.this.clientIdSuffix)
					&& StringUtils.hasText(groupInstance)) {

				properties.setProperty(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG,
						groupInstance + KafkaMessageListenerContainer.this.clientIdSuffix);
			}
		}

		@Nullable
		private DeliveryAttemptAware setupDeliveryAttemptAware() {
			DeliveryAttemptAware aware = null;
			if (this.containerProperties.isDeliveryAttemptHeader()) {
				if (this.transactionManager != null) {
					if (getAfterRollbackProcessor() instanceof DeliveryAttemptAware daa) {
						aware = daa;
					}
				}
				else {
					if (Objects.requireNonNull(this.commonErrorHandler).deliveryAttemptHeader()) {
						aware = this.commonErrorHandler;
					}
				}
			}
			return aware;
		}

		private boolean determineCommitCurrent(Properties consumerProperties, Map<String, Object> factoryConfigs) {
			if (AssignmentCommitOption.NEVER.equals(this.autoCommitOption)) {
				return false;
			}
			if (!this.autoCommit && AssignmentCommitOption.ALWAYS.equals(this.autoCommitOption)) {
				return true;
			}
			String autoOffsetReset = consumerProperties.getProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
			if (autoOffsetReset == null) {
				Object config = factoryConfigs.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
				if (config instanceof String str) {
					autoOffsetReset = str;
				}
			}
			boolean resetLatest = autoOffsetReset == null || autoOffsetReset.equals("latest");
			boolean latestOnlyOption = AssignmentCommitOption.LATEST_ONLY.equals(this.autoCommitOption)
					|| AssignmentCommitOption.LATEST_ONLY_NO_TX.equals(this.autoCommitOption);
			return !this.autoCommit && resetLatest && latestOnlyOption;
		}

		@SuppressWarnings("NullAway") // Dataflow analysis limitation
		private long obtainMaxPollInterval(Properties consumerProperties) {
			Object timeout = consumerProperties.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
			if (timeout == null) {
				timeout = KafkaMessageListenerContainer.this.consumerFactory.getConfigurationProperties()
						.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
			}

			if (timeout instanceof Duration dur) {
				return dur.toMillis();
			}
			else if (timeout instanceof Number nbr) {
				return nbr.longValue();
			}
			else if (timeout instanceof String str) {
				return Long.parseLong(str);
			}
			else {
				if (timeout != null) {
					Object timeoutToLog = timeout;
					this.logger.warn(() -> "Unexpected type: " + timeoutToLog.getClass().getName()
							+ " in property '"
							+ ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG
							+ "'; using Kafka default.");
				}
				Object maxPollIntervalMs = CONSUMER_CONFIG_DEFAULTS.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
				return maxPollIntervalMs == null ? null : (int) maxPollIntervalMs;
			}
		}

		@Nullable
		private ConsumerSeekAware checkConsumerSeekAware(GenericMessageListener<?> candidate) {
			return candidate instanceof ConsumerSeekAware csa ? csa : null;
		}

		boolean isConsumerPaused() {
			return this.consumerPaused;
		}

		boolean isPartitionPaused(TopicPartition topicPartition) {
			return this.pausedPartitions.contains(topicPartition);
		}

		@Nullable
		private TransactionTemplate determineTransactionTemplate() {
			if (this.transactionManager != null) {
				TransactionTemplate template = new TransactionTemplate(this.transactionManager);
				TransactionDefinition definition = this.containerProperties.getTransactionDefinition();
				Assert.state(definition == null
						|| definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED
						|| definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW,
						"Transaction propagation behavior must be REQUIRED or REQUIRES_NEW");
				if (definition != null) {
					BeanUtils.copyProperties(definition, template);
				}
				return template;
			}
			else {
				return null;
			}
		}

		private boolean determineAutoCommit(Properties consumerProperties) {
			boolean isAutoCommit;
			String autoCommitOverride = consumerProperties.getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
			if (!KafkaMessageListenerContainer.this.consumerFactory.getConfigurationProperties()
							.containsKey(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)
					&& autoCommitOverride == null) {
				consumerProperties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
				isAutoCommit = false;
			}
			else if (autoCommitOverride != null) {
				isAutoCommit = Boolean.parseBoolean(autoCommitOverride);
			}
			else {
				isAutoCommit = KafkaMessageListenerContainer.this.consumerFactory.isAutoCommit();
			}
			Assert.state(!this.isAnyManualAck || !isAutoCommit,
					() -> "Consumer cannot be configured for auto commit for ackMode " + this.ackMode);
			return isAutoCommit;
		}

		private @Nullable Duration determineSyncCommitTimeout() {
			Duration syncTimeout = this.containerProperties.getSyncCommitTimeout();
			if (syncTimeout != null) {
				return syncTimeout;
			}
			else {
				Object timeout = this.containerProperties.getKafkaConsumerProperties()
						.get(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG);
				if (timeout == null) {
					timeout = KafkaMessageListenerContainer.this.consumerFactory.getConfigurationProperties()
							.get(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG);
				}
				if (timeout instanceof Duration dur) {
					return dur;
				}
				else if (timeout instanceof Number nbr) {
					return Duration.ofMillis(nbr.longValue());
				}
				else if (timeout instanceof String str) {
					return Duration.ofMillis(Long.parseLong(str));
				}
				else {
					if (timeout != null) {
						Object timeoutToLog = timeout;
						this.logger.warn(() -> "Unexpected type: " + timeoutToLog.getClass().getName()
							+ " in property '"
							+ ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG
							+ "'; defaulting to Kafka default for sync commit timeouts");
					}
					Object defaultApiTimeout = CONSUMER_CONFIG_DEFAULTS.get(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG);
					return defaultApiTimeout == null ? null : Duration
							.ofMillis((int) defaultApiTimeout);
				}
			}
		}

		private boolean isListenerAdapterObservationAware() {
			return this.listener != null && RecordMessagingMessageListenerAdapter.class.equals(this.listener.getClass());
		}

		private void subscribeOrAssignTopics(final Consumer<? super K, ? super V> subscribingConsumer) {
			if (KafkaMessageListenerContainer.this.topicPartitions == null) {
				ConsumerRebalanceListener rebalanceListener = new ListenerConsumerRebalanceListener();
				Pattern topicPattern = this.containerProperties.getTopicPattern();
				if (topicPattern != null) {
					subscribingConsumer.subscribe(topicPattern, rebalanceListener);
				}
				else {
					subscribingConsumer.subscribe(Arrays.asList(this.containerProperties.getTopics()), // NOSONAR
							rebalanceListener);
				}
			}
			else {
				List<TopicPartitionOffset> topicPartitionsToAssign =
						Arrays.asList(KafkaMessageListenerContainer.this.topicPartitions);
				this.definedPartitions = Collections.synchronizedMap(
						new LinkedHashMap<>(topicPartitionsToAssign.size()));
				for (TopicPartitionOffset topicPartition : topicPartitionsToAssign) {
					this.definedPartitions.put(topicPartition.getTopicPartition(),
							new OffsetMetadata(topicPartition.getOffset(), topicPartition.isRelativeToCurrent(),
									topicPartition.getPosition()));
				}
				subscribingConsumer.assign(new ArrayList<>(this.definedPartitions.keySet()));
			}
		}

		protected void checkConsumer() {
			long timeSinceLastPoll = System.currentTimeMillis() - this.lastPoll;
			if (((float) timeSinceLastPoll) / (float) this.containerProperties.getPollTimeout()
					> this.containerProperties.getNoPollThreshold()) {
				publishNonResponsiveConsumerEvent(timeSinceLastPoll, this.consumer);
			}
		}

		@Nullable
		private MicrometerHolder obtainMicrometerHolder() {
			MicrometerHolder holder = null;
			try {
				if (KafkaUtils.MICROMETER_PRESENT && this.containerProperties.isMicrometerEnabled()
						&& !this.observationEnabled) {

					Function<@Nullable Object, Map<String, String>> mergedProvider =
							cr -> this.containerProperties.getMicrometerTags();
					if (this.micrometerTagsProvider != null) {
						mergedProvider = cr -> {
							Map<String, String> tags = new HashMap<>(this.containerProperties.getMicrometerTags());
							if (cr != null) {
								tags.putAll(this.micrometerTagsProvider.apply((ConsumerRecord<?, ?>) cr));
							}
							return tags;
						};
					}
					holder = new MicrometerHolder(getApplicationContext(), getBeanName(),
							"spring.kafka.listener", "Kafka Listener Timer", mergedProvider);
				}
			}
			catch (@SuppressWarnings(UNUSED) IllegalStateException ex) {
				// NOSONAR - no micrometer or meter registry
			}
			return holder;
		}

		private void seekPartitions(Collection<TopicPartition> partitions, boolean idle) {
			Objects.requireNonNull(this.consumerSeekAwareListener).registerSeekCallback(this);
			Map<TopicPartition, Long> current = new HashMap<>();
			for (TopicPartition topicPartition : partitions) {
				current.put(topicPartition, ListenerConsumer.this.consumer.position(topicPartition));
			}
			if (idle) {
				this.consumerSeekAwareListener.onIdleContainer(current, this.seekCallback);
			}
			else {
				this.consumerSeekAwareListener.onPartitionsAssigned(current, this.seekCallback);
			}
		}

		@Override
		public boolean isLongLived() {
			return true;
		}

		@Override // NOSONAR complexity
		public void run() {
			initialize();
			Throwable exitThrowable = null;
			boolean failedAuthRetry = false;
			this.lastReceive = System.currentTimeMillis();
			while (isRunning()) {

				try {
					handleAsyncFailure();
				}
				catch (Exception e) {
					ListenerConsumer.this.logger.error(
							"Failed to process async retry messages. skip this time, try it again next loop.");
				}

				try {
					pollAndInvoke();
					if (failedAuthRetry) {
						publishRetryAuthSuccessfulEvent();
						failedAuthRetry = false;
					}
				}
				catch (NoOffsetForPartitionException nofpe) {
					this.fatalError = true;
					ListenerConsumer.this.logger.error(nofpe, "No offset and no reset policy");
					exitThrowable = nofpe;
					break;
				}
				catch (AuthenticationException | AuthorizationException ae) {
					if (this.authExceptionRetryInterval == null) {
						ListenerConsumer.this.logger.error(ae,
								"Authentication/Authorization Exception and no authExceptionRetryInterval set");
						this.fatalError = true;
						exitThrowable = ae;
						break;
					}
					else {
						ListenerConsumer.this.logger.error(ae,
								"Authentication/Authorization Exception, retrying in "
										+ this.authExceptionRetryInterval.toMillis() + " ms");
						publishRetryAuthEvent(ae);
						failedAuthRetry = true;
						// We can't pause/resume here, as KafkaConsumer doesn't take pausing
						// into account when committing, hence risk of being flooded with
						// GroupAuthorizationExceptions.
						// see: https://github.com/spring-projects/spring-kafka/pull/1337
						sleepFor(this.authExceptionRetryInterval);
					}
				}
				catch (FencedInstanceIdException fie) {
					this.fatalError = true;
					ListenerConsumer.this.logger.error(fie, "'" + ConsumerConfig.GROUP_INSTANCE_ID_CONFIG
							+ "' has been fenced");
					exitThrowable = fie;
					break;
				}
				catch (StopAfterFenceException e) {
					this.logger.error(e, "Stopping container due to fencing");
					stop(false);
					exitThrowable = e;
				}
				catch (Error e) { // NOSONAR - rethrown
					this.logger.error(e, "Stopping container due to an Error");
					this.fatalError = true;
					wrapUp(e);
					throw e;
				}
				catch (Exception e) {
					handleConsumerException(e);
				}
				finally {
					clearThreadState();
				}
			}
			wrapUp(exitThrowable);
		}

		protected void initialize() {
			if (KafkaMessageListenerContainer.this.thisOrParentContainer.isChangeConsumerThreadName()) {
				Thread.currentThread().setName(
						KafkaMessageListenerContainer.this.thisOrParentContainer.getThreadNameSupplier()
								.apply(KafkaMessageListenerContainer.this));
			}
			publishConsumerStartingEvent();
			this.consumerThread = Thread.currentThread();
			KafkaUtils.setConsumerGroupId(this.consumerGroupId);
			setupSeeks();
			this.count = 0;
			this.last = System.currentTimeMillis();
			initAssignedPartitions();
			KafkaMessageListenerContainer.this.thisOrParentContainer.childStarted(KafkaMessageListenerContainer.this);
			publishConsumerStartedEvent();
		}

		private void setupSeeks() {
			if (this.consumerSeekAwareListener != null) {
				this.consumerSeekAwareListener.registerSeekCallback(this);
			}
		}

		private void initAssignedPartitions() {
			if (isRunning() && this.definedPartitions != null) {
				try {
					initPartitionsIfNeeded();
				}
				catch (Exception e) {
					this.logger.error(e, "Failed to set initial offsets");
				}
			}
		}

		protected void pollAndInvoke() {
			doProcessCommits();
			fixTxOffsetsIfNeeded();
			idleBetweenPollIfNecessary();
			if (!this.seeks.isEmpty()) {
				processSeeks();
			}
			enforceRebalanceIfNecessary();
			pauseConsumerIfNecessary();
			pausePartitionsIfNecessary();
			this.lastPoll = System.currentTimeMillis();
			if (!isRunning()) {
				return;
			}
			this.polling.set(true);
			ConsumerRecords<K, V> records = doPoll();
			if (!this.polling.compareAndSet(true, false) && records != null) {
				/*
				 * There is a small race condition where wakeIfNecessaryForStop was called between
				 * exiting the poll and before we reset the boolean.
				 */
				if (records.count() > 0) {
					this.logger.debug(() -> "Discarding polled records, container stopped: " + records.count());
				}
				return;
			}
			if (!this.firstPoll && this.definedPartitions != null && this.consumerSeekAwareListener != null) {
				this.firstPoll = true;
				this.consumerSeekAwareListener.onFirstPoll();
			}
			if (records != null && records.count() == 0 && this.isCountAck && this.count > 0) {
				commitIfNecessary();
				this.count = 0;
			}
			debugRecords(records);

			invokeIfHaveRecords(records);
			if (this.remainingRecords == null) {
				resumeConsumerIfNeccessary();
				if (!this.consumerPaused) {
					resumePartitionsIfNecessary();
				}
			}
		}

		protected void handleAsyncFailure() {
			List<FailedRecordTuple<K, V>> copyFailedRecords = new ArrayList<>(this.failedRecords);

			// If we use failedRecords.clear() to remove copied record from failed records,
			// We may encounter race condition during this operation.
			// Other, the thread which execute this block, may miss one failed record.
			int capturedRecordsCount = copyFailedRecords.size();
			for (int i = 0; i < capturedRecordsCount; i++) {
				this.failedRecords.pollFirst();
			}

			// If any copied and failed record fails to complete due to an unexpected error,
			// We will give up on retrying with the remaining copied and failed Records.
			for (FailedRecordTuple<K, V> copyFailedRecord : copyFailedRecords) {
				try {
					copyFailedRecord.observation.scoped(() -> invokeErrorHandlerBySingleRecord(copyFailedRecord));
				}
				catch (Exception e) {
					this.logger.warn(() ->
								"Async failed record failed to complete, thus skip it. record :"
								+ copyFailedRecord.toString()
								+ ", Exception : "
								+ e.getMessage());
				}
			}
		}

		private void doProcessCommits() {
			if (!this.autoCommit && !this.isRecordAck) {
				try {
					processCommits();
				}
				catch (CommitFailedException cfe) {
					if (this.remainingRecords != null && !this.isBatchListener) {
						ConsumerRecords<K, V> pending = this.remainingRecords;
						this.remainingRecords = null;
						List<ConsumerRecord<?, ?>> records = new ArrayList<>();
						for (ConsumerRecord<K, V> kvConsumerRecord : pending) {
							records.add(kvConsumerRecord);
						}
						Objects.requireNonNull(this.commonErrorHandler).handleRemaining(cfe, records, this.consumer,
								KafkaMessageListenerContainer.this.thisOrParentContainer);
					}
				}
			}
		}

		private void invokeIfHaveRecords(@Nullable ConsumerRecords<K, V> records) {
			if (records != null && records.count() > 0) {
				this.receivedSome = true;
				savePositionsIfNeeded(records);
				notIdle();
				notIdlePartitions(records.partitions());
				invokeListener(records);
			}
			else {
				checkIdle();
			}
			if (records == null || records.count() == 0
					|| records.partitions().size() < this.consumer.assignment().size()) {
				checkIdlePartitions();
			}
		}

		private void clearThreadState() {
			if (this.pollThreadStateProcessor != null) {
				this.pollThreadStateProcessor.clearThreadState(this.consumer);
			}
		}

		private void checkIdlePartitions() {
			Set<TopicPartition> partitions = this.consumer.assignment();
			partitions.forEach(this::checkIdlePartition);
		}

		private void checkIdlePartition(TopicPartition topicPartition) {
			Long idlePartitionEventInterval = this.containerProperties.getIdlePartitionEventInterval();
			if (idlePartitionEventInterval != null) {
				long now = System.currentTimeMillis();
				Long lstReceive = this.lastReceivePartition.computeIfAbsent(topicPartition, newTopicPartition -> now);
				Long lstAlertAt = this.lastAlertPartition.computeIfAbsent(topicPartition, newTopicPartition -> now);
				if (now > lstReceive + idlePartitionEventInterval
						&& now > lstAlertAt + idlePartitionEventInterval) {
					this.wasIdlePartition.put(topicPartition, true);
					publishIdlePartitionEvent(now - lstReceive, topicPartition, this.consumer,
							isPartitionPauseRequested(topicPartition));
					this.lastAlertPartition.put(topicPartition, now);
					if (this.consumerSeekAwareListener != null) {
						seekPartitions(Collections.singletonList(topicPartition), true);
					}
				}
			}
		}

		private void notIdlePartitions(Set<TopicPartition> partitions) {
			if (this.containerProperties.getIdlePartitionEventInterval() != null) {
				partitions.forEach(this::notIdlePartition);
			}
		}

		private void notIdlePartition(TopicPartition topicPartition) {
			long now = System.currentTimeMillis();
			Boolean partitionWasIdle = this.wasIdlePartition.get(topicPartition);
			if (partitionWasIdle != null && partitionWasIdle) {
				this.wasIdlePartition.put(topicPartition, false);
				Long lstReceive = this.lastReceivePartition.computeIfAbsent(topicPartition, newTopicPartition -> now);
				publishNoLongerIdlePartitionEvent(now - lstReceive, this.consumer, topicPartition);
			}
			this.lastReceivePartition.put(topicPartition, now);
		}

		private void notIdle() {
			if (this.containerProperties.getIdleEventInterval() != null) {
				long now = System.currentTimeMillis();
				if (this.wasIdle) {
					this.wasIdle = false;
					publishNoLongerIdleContainerEvent(now - this.lastReceive, this.consumer);
				}
				this.lastReceive = now;
			}
		}

		private void savePositionsIfNeeded(ConsumerRecords<K, V> records) {
			if (this.fixTxOffsets) {
				this.savedPositions.clear();
				records.partitions().forEach(tp -> this.savedPositions.put(tp, this.consumer.position(tp)));
			}
		}

		@SuppressWarnings("rawtypes")
		private void fixTxOffsetsIfNeeded() {
			if (this.fixTxOffsets) {
				try {
					Map<TopicPartition, OffsetAndMetadata> toFix = new HashMap<>();
					this.lastCommits.forEach((tp, oamd) -> {
						long position = this.consumer.position(tp);
						Long saved = this.savedPositions.get(tp);
						if (saved != null && saved != position) {
							this.logger.debug(() -> "Skipping TX offset correction - seek(s) have been performed; "
									+ "saved: " + this.savedPositions + ", "
									+ "committed: " + oamd + ", "
									+ "current: " + tp + "@" + position);
							return;
						}
						if (position > oamd.offset()) {
							toFix.put(tp, createOffsetAndMetadata(position));
						}
					});
					if (!toFix.isEmpty()) {
						this.logger.debug(() -> "Fixing TX offsets: " + toFix);
						if (this.kafkaTxManager == null) {
							commitOffsets(toFix);
						}
						else {
							Objects.requireNonNull(this.transactionTemplate).executeWithoutResult(status -> {
								doSendOffsets(getTxProducer(), toFix);
							});
						}
					}
				}
				catch (Exception e) {
					this.logger.error(e, () -> "Failed to correct transactional offset(s): "
							+ ListenerConsumer.this.lastCommits);
				}
				finally {
					ListenerConsumer.this.lastCommits.clear();
				}
			}
		}

		@Nullable
		private ConsumerRecords<K, V> doPoll() {
			ConsumerRecords<K, V> records;
			if (this.isBatchListener && this.subBatchPerPartition) {
				if (this.batchIterator == null) {
					this.lastBatch = pollConsumer();
					captureOffsets(this.lastBatch);
					if (this.lastBatch.count() == 0) {
						return this.lastBatch;
					}
					else {
						this.batchIterator = this.lastBatch.partitions().iterator();
					}
				}
				TopicPartition next = this.batchIterator.next();
				List<ConsumerRecord<K, V>> subBatch = Objects.requireNonNull(this.lastBatch).records(next);
				records = new ConsumerRecords<>(Collections.singletonMap(next, subBatch), Map.of());
				if (!this.batchIterator.hasNext()) {
					this.batchIterator = null;
				}
			}
			else {
				records = pollConsumer();
				if (this.remainingRecords != null) {
					int howManyRecords = records.count();
					if (howManyRecords > 0) {
						this.logger.error(() -> String.format("Poll returned %d record(s) while consumer was paused "
								+ "after an error; emergency stop invoked to avoid message loss", howManyRecords));
						KafkaMessageListenerContainer.this.emergencyStop.run();
					}
					TopicPartition firstPart = this.remainingRecords.partitions().iterator().next();
					boolean isPaused = isPauseRequested() || isPartitionPauseRequested(firstPart);
					this.logger.debug(() -> "First pending after error: " + firstPart + "; paused: " + isPaused);
					if (!isPaused) {
						records = this.remainingRecords;
						this.remainingRecords = null;
					}
				}
				captureOffsets(records);
				checkRebalanceCommits();
			}
			return records;
		}

		private ConsumerRecords<K, V> pollConsumer() {
			beforePoll();
			try {
				return this.consumer.poll(this.consumerPaused ? this.pollTimeoutWhilePaused : this.pollTimeout);
			}
			catch (WakeupException ex) {
				return ConsumerRecords.empty();
			}
		}

		private void beforePoll() {
			if (this.pollThreadStateProcessor != null) {
				this.pollThreadStateProcessor.setupThreadState(this.consumer);
			}
		}

		private synchronized void captureOffsets(ConsumerRecords<K, V> records) {
			if (this.offsetsInThisBatch != null && records.count() > 0) {
				this.offsetsInThisBatch.clear();
				Objects.requireNonNull(this.deferredOffsets).clear();
				records.partitions().forEach(part -> {
					LinkedList<Long> offs = new LinkedList<>();
					this.offsetsInThisBatch.put(part, offs);
					this.deferredOffsets.put(part, new LinkedList<>());
					records.records(part).forEach(rec -> offs.add(rec.offset()));
				});
			}
		}

		private void checkRebalanceCommits() {
			if (!this.commitsDuringRebalance.isEmpty()) {
				// Attempt to recommit the offsets for partitions that we still own
				Map<TopicPartition, OffsetAndMetadata> commits = this.commitsDuringRebalance.entrySet()
						.stream()
						.filter(entry -> this.assignedPartitions.contains(entry.getKey()))
						.collect(Collectors.toMap(Entry::getKey, Entry::getValue));

				Map<TopicPartition, OffsetAndMetadata> uncommitted = this.commitsDuringRebalance.entrySet()
						.stream()
						.filter(entry -> !this.assignedPartitions.contains(entry.getKey()))
						.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
				if (!uncommitted.isEmpty()) {
					this.logger.warn(() -> "These offsets could not be committed; partition(s) lost during rebalance: "
							+ uncommitted);
				}
				this.commitsDuringRebalance.clear();
				this.logger.debug(() -> "Commit list: " + commits);
				commitSync(commits);
			}
		}

		void wakeIfNecessaryForStop() {
			if (this.polling.getAndSet(false)) {
				this.consumer.wakeup();
			}
		}

		void wakeIfNecessary() {
			if (this.polling.get()) {
				this.consumer.wakeup();
			}
		}

		private void debugRecords(@Nullable ConsumerRecords<K, V> records) {
			if (records != null) {
				this.logger.debug(() -> "Received: " + records.count() + " records");
				if (records.count() > 0) {
					this.logger.trace(() -> records.partitions().stream()
							.flatMap(p -> records.records(p).stream())
							// map to same format as send metadata toString()
							.map(r -> r.topic() + "-" + r.partition() + "@" + r.offset())
							.toList()
							.toString());
				}
			}
		}

		private void sleepFor(Duration duration) {
			try {
				ListenerUtils.stoppableSleep(KafkaMessageListenerContainer.this, duration.toMillis());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				this.logger.error(e, "Interrupted while sleeping");
			}
		}

		private void enforceRebalanceIfNecessary() {
			try {
				if (KafkaMessageListenerContainer.this.thisOrParentContainer.enforceRebalanceRequested.get()) {
					String enforcedRebalanceReason = String.format("Enforced rebalance requested for container: %s",
							KafkaMessageListenerContainer.this.getListenerId());
					this.logger.info(enforcedRebalanceReason);
					this.consumer.enforceRebalance(enforcedRebalanceReason);
				}
			}
			finally {
				KafkaMessageListenerContainer.this.thisOrParentContainer.enforceRebalanceRequested.set(false);
			}
		}

		private void pauseConsumerIfNecessary() {
			if (this.offsetsInThisBatch != null) {
				synchronized (this) {
					doPauseConsumerIfNecessary();
				}
			}
			else {
				doPauseConsumerIfNecessary();
			}
		}

		private void doPauseConsumerIfNecessary() {
			if (!this.pausedForNack.isEmpty()) {
				this.logger.debug("Still paused for nack sleep");
				return;
			}
			if (this.offsetsInThisBatch != null && !this.offsetsInThisBatch.isEmpty() && !this.pausedForAsyncAcks) {
				this.pausedForAsyncAcks = true;
				this.logger.debug(() -> "Pausing for incomplete async acks: " + this.offsetsInThisBatch);
			}
			if (!this.consumerPaused && (isPauseRequested() || this.pausedForAsyncAcks)
					|| this.pauseForPending) {

				Collection<TopicPartition> assigned = getAssignedPartitions();
				if (!CollectionUtils.isEmpty(assigned)) {
					this.consumer.pause(assigned);
					this.consumerPaused = true;
					this.pauseForPending = false;
					this.logger.debug(() -> "Paused consumption from: " + this.consumer.paused());
					publishConsumerPausedEvent(assigned, this.pausedForAsyncAcks
							? "Incomplete out of order acks"
							: "User requested");
				}
			}
		}

		private void resumeConsumerIfNeccessary() {
			if (this.nackWakeTimeMillis > 0) {
				if (System.currentTimeMillis() > this.nackWakeTimeMillis) {
					this.nackWakeTimeMillis = 0;
					this.consumer.resume(this.pausedForNack);
					this.logger.debug(() -> "Resumed after nack sleep: " + this.pausedForNack);
					publishConsumerResumedEvent(this.pausedForNack);
					this.pausedForNack.clear();
				}
			}
			else if (this.offsetsInThisBatch != null) {
				synchronized (this) {
					doResumeConsumerIfNeccessary();
				}
			}
			else {
				doResumeConsumerIfNeccessary();
			}
		}

		private void doResumeConsumerIfNeccessary() {
			if (this.pausedForAsyncAcks && Objects.requireNonNull(this.offsetsInThisBatch).isEmpty()) {
				this.pausedForAsyncAcks = false;
				this.logger.debug("Resuming after manual async acks cleared");
			}
			if (this.consumerPaused && !isPauseRequested() && !this.pausedForAsyncAcks) {
				this.logger.debug(() -> "Resuming consumption from: " + this.consumer.paused());
				Collection<TopicPartition> paused = new LinkedList<>(this.consumer.paused());
				paused.removeAll(this.pausedPartitions);
				this.consumer.resume(paused);
				this.consumerPaused = false;
				publishConsumerResumedEvent(paused);
			}
		}

		private void pausePartitionsIfNecessary() {
			Set<TopicPartition> pausedConsumerPartitions = this.consumer.paused();
			Collection<TopicPartition> partitions = getAssignedPartitions();
			if (partitions != null) {
				List<TopicPartition> partitionsToPause = partitions
						.stream()
						.filter(tp -> isPartitionPauseRequested(tp)
								&& !pausedConsumerPartitions.contains(tp))
						.toList();
				if (!partitionsToPause.isEmpty()) {
					this.consumer.pause(partitionsToPause);
					this.pausedPartitions.addAll(partitionsToPause);
					this.logger.debug(() -> "Paused consumption from " + partitionsToPause);
					partitionsToPause.forEach(KafkaMessageListenerContainer.this::publishConsumerPartitionPausedEvent);
				}
			}
		}

		private void resumePartitionsIfNecessary() {
			Collection<TopicPartition> assigned = getAssignedPartitions();
			if (assigned != null) {
				List<TopicPartition> partitionsToResume = assigned
						.stream()
						.filter(tp -> !isPartitionPauseRequested(tp)
								&& this.pausedPartitions.contains(tp))
						.toList();
				if (!partitionsToResume.isEmpty()) {
					this.consumer.resume(partitionsToResume);
					this.pausedPartitions.removeAll(partitionsToResume);
					this.logger.debug(() -> "Resumed consumption from " + partitionsToResume);
					partitionsToResume
							.forEach(KafkaMessageListenerContainer.this::publishConsumerPartitionResumedEvent);
				}
			}
		}

		private void checkIdle() {
			Long idleEventInterval = this.containerProperties.getIdleEventInterval();
			if (idleEventInterval != null) {
				long idleEventInterval2 = idleEventInterval;
				long now = System.currentTimeMillis();
				if (!this.receivedSome) {
					idleEventInterval2 = (long) (idleEventInterval2 * this.containerProperties.getIdleBeforeDataMultiplier());
				}
				if (now > this.lastReceive + idleEventInterval2
						&& now > this.lastAlertAt + idleEventInterval2) {
					this.wasIdle = true;
					publishIdleContainerEvent(now - this.lastReceive, this.consumer, this.consumerPaused);
					this.lastAlertAt = now;
					if (this.consumerSeekAwareListener != null) {
						Collection<TopicPartition> partitions = getAssignedPartitions();
						if (partitions != null) {
							seekPartitions(partitions, true);
						}
					}
				}
			}
		}

		private void idleBetweenPollIfNecessary() {
			long idleBetweenPolls = this.containerProperties.getIdleBetweenPolls();
			Collection<TopicPartition> assigned = getAssignedPartitions();
			if (idleBetweenPolls > 0 && assigned != null && !assigned.isEmpty()) {
				idleBetweenPolls = Math.min(idleBetweenPolls,
						this.maxPollInterval - (System.currentTimeMillis() - this.lastPoll)
								- 5000); // NOSONAR - less by five seconds to avoid race condition with rebalance
				if (idleBetweenPolls > 0) {
					try {
						ListenerUtils.stoppableSleep(KafkaMessageListenerContainer.this, idleBetweenPolls);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("Consumer Thread [" + this + "] has been interrupted", ex);
					}
				}
			}
		}

		private void wrapUp(@Nullable Throwable throwable) {
			KafkaUtils.clearConsumerGroupId();
			if (this.micrometerHolder != null) {
				this.micrometerHolder.destroy();
			}
			publishConsumerStoppingEvent(this.consumer);
			Collection<TopicPartition> partitions = getAssignedPartitions();
			if (!this.fatalError) {
				if (this.kafkaTxManager == null) {
					commitPendingAcks();
					try {
						this.consumer.unsubscribe();
					}
					catch (@SuppressWarnings(UNUSED) WakeupException e) {
						// No-op. Continue process
					}
				}
			}
			else {
				if (!(throwable instanceof Error)) {
					this.logger.error("Fatal consumer exception; stopping container");
				}
				KafkaMessageListenerContainer.this.emergencyStop.run();
			}
			this.monitorTask.cancel(true);
			if (!this.taskSchedulerExplicitlySet) {
				((ThreadPoolTaskScheduler) this.taskScheduler).destroy();
			}
			this.consumer.close();
			getAfterRollbackProcessor().clearThreadState();
			if (this.commonErrorHandler != null) {
				this.commonErrorHandler.clearThreadState();
			}
			if (this.consumerSeekAwareListener != null) {
				this.consumerSeekAwareListener.onPartitionsRevoked(partitions);
				this.consumerSeekAwareListener.unregisterSeekCallback();
			}
			this.logger.info(() -> this.consumerGroupId + ": Consumer stopped");
			publishConsumerStoppedEvent(throwable);
		}

		/**
		 * Handle exceptions thrown by the consumer outside of message listener
		 * invocation (e.g. commit exceptions).
		 * @param e the exception.
		 */
		protected void handleConsumerException(Exception e) {
			if (e instanceof RetriableCommitFailedException) {
				this.logger.error(e, "Commit retries exhausted");
				return;
			}
			try {
				if (this.commonErrorHandler != null) {
						this.commonErrorHandler.handleOtherException(e, this.consumer,
								KafkaMessageListenerContainer.this.thisOrParentContainer, this.isBatchListener);
				}
				else {
					this.logger.error(e, "Consumer exception");
				}
			}
			catch (Exception ex) {
				this.logger.error(ex, "Consumer exception");
			}
		}

		private void commitPendingAcks() {
			processCommits();
			if (!this.offsets.isEmpty()) {
				// we always commit after stopping the invoker
				commitIfNecessary();
			}
		}

		/**
		 * Process any acks that have been queued.
		 */
		private void handleAcks() {
			ConsumerRecord<K, V> cRecord = this.acks.poll();
			while (cRecord != null) {
				traceAck(cRecord);
				processAck(cRecord);
				cRecord = this.acks.poll();
			}
		}

		private void traceAck(ConsumerRecord<K, V> cRecord) {
			this.logger.trace(() -> "Ack: " + KafkaUtils.format(cRecord));
		}

		private void doAck(ConsumerRecord<K, V> cRecord) {
			traceAck(cRecord);
			if (this.offsetsInThisBatch != null) { // NOSONAR (sync)
				ackInOrder(cRecord);
			}
			else {
				processAck(cRecord);
			}
		}

		private void processAck(ConsumerRecord<K, V> cRecord) {
			if (!Thread.currentThread().equals(this.consumerThread)) {
				try {
					this.acks.put(cRecord);
					if (this.isManualImmediateAck || this.pausedForAsyncAcks) {  // NOSONAR (sync)
						this.consumer.wakeup();
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new KafkaException("Interrupted while storing ack", e);
				}
			}
			else {
				if (this.isManualImmediateAck) {
					try {
						ackImmediate(cRecord);
					}
					catch (@SuppressWarnings(UNUSED) WakeupException e) {
						// ignore - not polling
					}
				}
				else {
					addOffset(cRecord);
				}
			}
		}

		private void processAcks(ConsumerRecords<K, V> records) {
			if (!Thread.currentThread().equals(this.consumerThread)) {
				try {
					for (ConsumerRecord<K, V> cRecord : records) {
						this.acks.put(cRecord);
					}
					if (this.isManualImmediateAck) {
						this.consumer.wakeup();
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new KafkaException("Interrupted while storing ack", e);
				}
			}
			else {
				if (this.isManualImmediateAck) {
					try {
						ackImmediate(records);
					}
					catch (@SuppressWarnings(UNUSED) WakeupException e) {
						// ignore - not polling
					}
				}
				else {
					for (ConsumerRecord<K, V> cRecord : records) {
						addOffset(cRecord);
					}
				}
			}
		}

		private synchronized void ackInOrder(ConsumerRecord<K, V> cRecord) {
			TopicPartition part = new TopicPartition(cRecord.topic(), cRecord.partition());
			List<Long> offs = Objects.requireNonNull(this.offsetsInThisBatch).get(part);
			if (!ObjectUtils.isEmpty(offs)) {
				List<ConsumerRecord<K, V>> deferred = Objects.requireNonNull(this.deferredOffsets).get(part);
				if (offs.get(0) == cRecord.offset()) {
					offs.remove(0);
					ConsumerRecord<K, V> recordToAck = cRecord;
					if (!CollectionUtils.isEmpty(deferred)) {
						deferred.sort((a, b) -> Long.compare(a.offset(), b.offset()));
						while (!ObjectUtils.isEmpty(deferred) && deferred.get(0).offset() == recordToAck.offset() + 1) {
							recordToAck = deferred.remove(0);
							offs.remove(0);
						}
					}
					processAck(recordToAck);
					if (offs.isEmpty()) {
						this.deferredOffsets.remove(part);
						this.offsetsInThisBatch.remove(part);
					}
				}
				else if (cRecord.offset() < offs.get(0)) {
					throw new IllegalStateException("First remaining offset for this batch is " + offs.get(0)
							+ "; you are acknowledging a stale record: " + KafkaUtils.format(cRecord));
				}
				else {
					if (deferred != null) {
					deferred.add(cRecord);
					}
				}
			}
			else {
				throw new IllegalStateException("Unexpected ack for " + KafkaUtils.format(cRecord)
						+ "; offsets list is empty");
			}
		}

		private void ackImmediate(ConsumerRecord<K, V> cRecord) {
			Map<TopicPartition, OffsetAndMetadata> commits = buildSingleCommits(cRecord);
			commitOffsetsInTransactions(commits);
		}

		private void ackImmediate(ConsumerRecords<K, V> records) {
			Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
			for (TopicPartition part : records.partitions()) {
				commits.put(part, createOffsetAndMetadata(records.records(part)
						.get(records.records(part).size() - 1).offset() + 1));
			}
			commitOffsetsInTransactions(commits);
		}

		private void invokeListener(final ConsumerRecords<K, V> records) {
			if (this.isBatchListener) {
				invokeBatchListener(records);
			}
			else {
				invokeRecordListener(records);
			}
		}

		private void invokeBatchListener(final ConsumerRecords<K, V> recordsArg) {
			ConsumerRecords<K, V> records = checkEarlyIntercept(recordsArg);
			if (records == null || records.count() == 0) {
				return;
			}
			List<ConsumerRecord<K, V>> recordList = new ArrayList<>();
			if (!this.wantsFullRecords) {
				recordList = createRecordList(records);
			}
			if (this.wantsFullRecords || !recordList.isEmpty()) {
				if (this.transactionTemplate != null) {
					invokeBatchListenerInTx(records, recordList); // NOSONAR
				}
				else {
					doInvokeBatchListener(records, recordList); // NOSONAR
				}
			}
		}

		private void invokeBatchListenerInTx(final ConsumerRecords<K, V> records,
				final List<ConsumerRecord<K, V>> recordList) {

			try {
				Objects.requireNonNull(this.transactionTemplate).execute(new TransactionCallbackWithoutResult() {

					@Override
					public void doInTransactionWithoutResult(TransactionStatus s) {
						if (ListenerConsumer.this.kafkaTxManager != null) {
							ListenerConsumer.this.producer = getTxProducer();
						}
						RuntimeException aborted = doInvokeBatchListener(records, recordList);
						if (aborted != null) {
							throw aborted;
						}
					}
				});
			}
			catch (ProducerFencedException | FencedInstanceIdException e) {
				this.logger.error(e, "Producer or '"
						+ ConsumerConfig.GROUP_INSTANCE_ID_CONFIG
						+ "' fenced during transaction");
				if (this.containerProperties.isStopContainerWhenFenced()) {
					throw new StopAfterFenceException("Container stopping due to fencing", e);
				}
			}
			catch (RuntimeException e) {
				this.logger.error(e, "Transaction rolled back");
				batchRollback(records, recordList, e);
			}
		}

		private void batchRollback(final ConsumerRecords<K, V> records,
				final List<ConsumerRecord<K, V>> recordList, RuntimeException e) {

			@SuppressWarnings(UNCHECKED)
			AfterRollbackProcessor<K, V> afterRollbackProcessorToUse =
					(AfterRollbackProcessor<K, V>) getAfterRollbackProcessor();
			if (afterRollbackProcessorToUse.isProcessInTransaction() && this.transactionTemplate != null) {
				this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {

					@Override
					protected void doInTransactionWithoutResult(TransactionStatus status) {
						afterRollbackProcessorToUse.processBatch(records,
								Objects.requireNonNullElseGet(recordList, () -> createRecordList(records)),
								ListenerConsumer.this.consumer,
								KafkaMessageListenerContainer.this.thisOrParentContainer, e,
								ListenerConsumer.this.wantsBatchRecoverAfterRollback, ListenerConsumer.this.eosMode);
					}

				});
			}
			else {
				try {
					afterRollbackProcessorToUse.processBatch(records,
							Objects.requireNonNullElseGet(recordList, () -> createRecordList(records)), this.consumer,
							KafkaMessageListenerContainer.this.thisOrParentContainer, e,
							this.wantsBatchRecoverAfterRollback, this.eosMode);
				}
				catch (Exception ex) {
					this.logger.error(ex, "AfterRollbackProcessor threw exception");
				}
			}
		}

		private List<ConsumerRecord<K, V>> createRecordList(final ConsumerRecords<K, V> records) {
			List<ConsumerRecord<K, V>> recordList = new ArrayList<>(records.count());
			records.forEach(recordList::add);
			return recordList;
		}

		/**
		 * Actually invoke the batch listener.
		 * @param records the records (needed to invoke the error handler)
		 * @param recordList the list of records (actually passed to the listener).
		 * @return an exception.
		 * @throws Error an error.
		 */
		@Nullable
		private RuntimeException doInvokeBatchListener(final ConsumerRecords<K, V> records, // NOSONAR
				List<ConsumerRecord<K, V>> recordList) {
			try {
				invokeBatchOnMessage(records, recordList);
				if (this.batchFailed) {
					this.batchFailed = false;
					if (this.commonErrorHandler != null) {
						this.commonErrorHandler.clearThreadState();
					}
					getAfterRollbackProcessor().clearThreadState();
				}
				if (!this.autoCommit && !this.isRecordAck) {
					processCommits();
				}
			}
			catch (RuntimeException e) {
				if (this.commonErrorHandler == null) {
					throw e;
				}
				try {
					invokeBatchErrorHandler(records, recordList, e);
					commitOffsetsIfNeededAfterHandlingError(records);
				}
				catch (RecordInRetryException rire) {
					this.logger.info("Record in retry and not yet recovered");
					return rire;
				}
				catch (KafkaException ke) {
					ke.selfLog(ERROR_HANDLER_THREW_AN_EXCEPTION, this.logger);
					return ke;
				}
				catch (RuntimeException ee) {
					this.logger.error(ee, ERROR_HANDLER_THREW_AN_EXCEPTION);
					return ee;
				}
				catch (Error er) { // NOSONAR
					this.logger.error(er, "Error handler threw an error");
					throw er;
				}
			}
			catch (@SuppressWarnings(UNUSED) InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		}

		private void commitOffsetsIfNeededAfterHandlingError(final ConsumerRecords<K, V> records) {
			if ((!this.autoCommit && Objects.requireNonNull(this.commonErrorHandler).isAckAfterHandle() && this.consumerGroupId != null)
					|| this.producer != null) {
				if (this.remainingRecords != null) {
					ConsumerRecord<K, V> firstUncommitted = this.remainingRecords.iterator().next();
					for (ConsumerRecord<K, V> next : records) {
						if (!next.equals(firstUncommitted)) {
							this.acks.add(next);
						}
						else {
							break;
						}
					}
				}
				else {
					this.acks.addAll(getHighestOffsetRecords(records));
				}
				if (this.producer != null) {
					sendOffsetsToTransaction();
				}
			}
		}

		private void batchInterceptAfter(ConsumerRecords<K, V> records, @Nullable Exception exception) {
			if (this.commonBatchInterceptor != null) {
				try {
					if (exception == null) {
						this.commonBatchInterceptor.success(records, this.consumer);
					}
					else {
						this.commonBatchInterceptor.failure(records, exception, this.consumer);
					}
				}
				catch (Exception e) {
					this.logger.error(e, "BatchInterceptor.success/failure threw an exception");
				}
			}
		}

		@Nullable
		private Object startMicrometerSample() {
			if (this.micrometerHolder != null) {
				return this.micrometerHolder.start();
			}
			return null;
		}

		private void successTimer(@Nullable Object sample, @Nullable ConsumerRecord<?, ?> record) {
			if (sample != null && this.micrometerHolder != null) {
				if (this.micrometerTagsProvider == null || record == null) {
					this.micrometerHolder.success(sample);
				}
				else {
					this.micrometerHolder.success(sample, record);
				}
			}
		}

		private void failureTimer(@Nullable Object sample, @Nullable ConsumerRecord<?, ?> record,
				Throwable exception) {
			if (sample != null) {
				String exceptionName = exception.getCause() != null
						? exception.getCause().getClass().getSimpleName()
						: exception.getClass().getSimpleName();

				if (this.micrometerHolder != null) {
					if (this.micrometerTagsProvider == null || record == null) {
						this.micrometerHolder.failure(sample, exceptionName);
					}
					else {
						this.micrometerHolder.failure(sample, exceptionName, record);
					}
				}
			}
		}

		private void invokeBatchOnMessage(final ConsumerRecords<K, V> records, // NOSONAR - Cyclomatic Complexity
				List<ConsumerRecord<K, V>> recordList) throws InterruptedException {

			invokeBatchOnMessageWithRecordsOrList(records, recordList);
			List<ConsumerRecord<?, ?>> toSeek = null;
			if (this.nackSleepDurationMillis >= 0) {
				int index = 0;
				toSeek = new ArrayList<>();
				for (ConsumerRecord<K, V> cRecord : records) {
					if (index++ >= this.nackIndex) {
						toSeek.add(cRecord);
					}
				}
			}
			if (this.producer != null || (!this.isAnyManualAck && !this.autoCommit)) {
				if (this.nackSleepDurationMillis < 0) {
					ackBatch(records);
				}
				if (this.producer != null) {
					sendOffsetsToTransaction();
				}
			}
			if (toSeek != null) {
				if (!this.autoCommit) {
					processCommits();
				}
				SeekUtils.doSeeks(toSeek, this.consumer, null, true, (rec, ex) -> false, this.logger); // NOSONAR
				pauseForNackSleep();
			}
		}

		private void ackBatch(final ConsumerRecords<K, V> records) throws InterruptedException {
			for (ConsumerRecord<K, V> cRecord : getHighestOffsetRecords(records)) {
				this.acks.put(cRecord);
			}
		}

		private void invokeBatchWithIndividualRecordObservation(List<ConsumerRecord<K, V>> recordList) {
			// Create individual observations for each record without scopes
			for (ConsumerRecord<K, V> record : recordList) {
				Observation observation = KafkaListenerObservation.LISTENER_OBSERVATION.observation(
						this.containerProperties.getObservationConvention(),
							DefaultKafkaListenerObservationConvention.INSTANCE,
							() -> new KafkaRecordReceiverContext(record, getListenerId(), getClientId(), this.consumerGroupId,
									this::clusterId),
							this.observationRegistry);
				observation.observe(() -> {
					this.logger.debug(() -> "Observing record in batch: " + KafkaUtils.format(record));
				});
			}
		}

		private void invokeBatchOnMessageWithRecordsOrList(final ConsumerRecords<K, V> recordsArg,
				List<ConsumerRecord<K, V>> recordListArg) {

			ConsumerRecords<K, V> records = recordsArg;
			List<ConsumerRecord<K, V>> recordList = recordListArg;
			if (this.listenerinfo != null) {
				records.iterator().forEachRemaining(this::listenerInfo);
			}
			if (this.batchInterceptor != null) {
				records = this.batchInterceptor.intercept(recordsArg, this.consumer);
				if (records == null) {
					this.logger.debug(() -> "BatchInterceptor returned null, skipping: "
							+ recordsArg + " with " + recordsArg.count() + " records");
					return;
				}
				else {
					recordList = createRecordList(records);
				}
			}
			Object sample = startMicrometerSample();


			try {
				if (this.observationEnabled) {
					invokeBatchWithIndividualRecordObservation(recordList);
				}

				if (this.wantsFullRecords) {
					Objects.requireNonNull(this.batchListener).onMessage(records, // NOSONAR
							this.isAnyManualAck
									? new ConsumerBatchAcknowledgment(records, recordList)
									: null,
							this.consumer);
				}
				else {
					doInvokeBatchOnMessage(records, recordList); // NOSONAR
				}
				batchInterceptAfter(records, null);
				successTimer(sample, null);
			}
			catch (RuntimeException e) {
				this.batchFailed = true;
				failureTimer(sample, null, e);
				batchInterceptAfter(records, e);
				throw e;
			}
		}

		private void doInvokeBatchOnMessage(final ConsumerRecords<K, V> records,
				List<ConsumerRecord<K, V>> recordList) {

			try {
				switch (this.listenerType) {
					case ACKNOWLEDGING_CONSUMER_AWARE ->
						Objects.requireNonNull(this.batchListener).onMessage(recordList,
								this.isAnyManualAck
										? new ConsumerBatchAcknowledgment(records, recordList)
										: null, this.consumer);
					case ACKNOWLEDGING ->
						Objects.requireNonNull(this.batchListener).onMessage(recordList,
								this.isAnyManualAck
										? new ConsumerBatchAcknowledgment(records, recordList)
										: null);
					case CONSUMER_AWARE -> Objects.requireNonNull(this.batchListener).onMessage(recordList, this.consumer);
					case SIMPLE -> Objects.requireNonNull(this.batchListener).onMessage(recordList);
				}
			}
			catch (Exception ex) { //  NOSONAR
				throw decorateException(ex);
			}
		}

		private void invokeBatchErrorHandler(final ConsumerRecords<K, V> records,
				List<ConsumerRecord<K, V>> list, RuntimeException rte) {

			if (Objects.requireNonNull(this.commonErrorHandler).seeksAfterHandling() || this.transactionManager != null
					|| rte instanceof CommitFailedException) {

				this.commonErrorHandler.handleBatch(rte, records, this.consumer,
						KafkaMessageListenerContainer.this.thisOrParentContainer,
						() -> invokeBatchOnMessageWithRecordsOrList(records, list));
			}
			else {
				ConsumerRecords<K, V> afterHandling = this.commonErrorHandler.handleBatchAndReturnRemaining(rte,
						records, this.consumer, KafkaMessageListenerContainer.this.thisOrParentContainer,
						() -> invokeBatchOnMessageWithRecordsOrList(records, list));
				if (afterHandling != null && !afterHandling.isEmpty()) {
					this.remainingRecords = afterHandling;
					this.pauseForPending = true;
				}
			}
		}

		private void invokeRecordListener(final ConsumerRecords<K, V> records) {
			if (this.transactionTemplate != null) {
				invokeRecordListenerInTx(records);
			}
			else {
				doInvokeWithRecords(records);
			}
		}

		/**
		 * Invoke the listener with each record in a separate transaction.
		 * @param records the records.
		 */
		private void invokeRecordListenerInTx(final ConsumerRecords<K, V> records) {
			Iterator<ConsumerRecord<K, V>> iterator = records.iterator();
			while (iterator.hasNext()) {
				if (this.stopImmediate && !isRunning()) {
					break;
				}
				final ConsumerRecord<K, V> cRecord = checkEarlyIntercept(iterator.next());
				if (cRecord == null) {
					continue;
				}
				this.logger.trace(() -> "Processing " + KafkaUtils.format(cRecord));
				try {
					invokeInTransaction(iterator, cRecord);
				}
				catch (ProducerFencedException | FencedInstanceIdException e) {
					this.logger.error(e, "Producer or 'group.instance.id' fenced during transaction");
					if (this.containerProperties.isStopContainerWhenFenced()) {
						throw new StopAfterFenceException("Container stopping due to fencing", e);
					}
					break;
				}
				catch (RuntimeException ex) {
					this.logger.error(ex, "Transaction rolled back");
					recordAfterRollback(iterator, cRecord, ex);
				}
				if (this.commonRecordInterceptor != null) {
					this.commonRecordInterceptor.afterRecord(cRecord, this.consumer);
				}
				if (this.nackSleepDurationMillis >= 0) {
					handleNack(records, cRecord);
					break;
				}
				if (checkImmediatePause(iterator)) {
					break;
				}
			}
		}

		private void invokeInTransaction(Iterator<ConsumerRecord<K, V>> iterator, final ConsumerRecord<K, V> cRecord) {
			Objects.requireNonNull(this.transactionTemplate).execute(new TransactionCallbackWithoutResult() {

				@Override
				public void doInTransactionWithoutResult(TransactionStatus s) {
					if (ListenerConsumer.this.kafkaTxManager != null) {
						ListenerConsumer.this.producer = getTxProducer();
					}
					RuntimeException aborted = doInvokeRecordListener(cRecord, iterator);
					if (aborted != null) {
						throw aborted;
					}
				}

			});
		}

		private void recordAfterRollback(Iterator<ConsumerRecord<K, V>> iterator, final ConsumerRecord<K, V> cRecord,
				RuntimeException e) {

			List<ConsumerRecord<K, V>> unprocessed = new ArrayList<>();
			unprocessed.add(cRecord);
			while (iterator.hasNext()) {
				unprocessed.add(iterator.next());
			}
			@SuppressWarnings(UNCHECKED)
			AfterRollbackProcessor<K, V> afterRollbackProcessorToUse =
					(AfterRollbackProcessor<K, V>) getAfterRollbackProcessor();
			if (afterRollbackProcessorToUse.isProcessInTransaction() && this.transactionTemplate != null) {
				this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {

					@Override
					protected void doInTransactionWithoutResult(TransactionStatus status) {
						afterRollbackProcessorToUse.process(unprocessed, ListenerConsumer.this.consumer,
								KafkaMessageListenerContainer.this.thisOrParentContainer, e, true,
								ListenerConsumer.this.eosMode);
					}

				});
			}
			else {
				try {
					afterRollbackProcessorToUse.process(unprocessed, this.consumer,
							KafkaMessageListenerContainer.this.thisOrParentContainer, e, true, this.eosMode);
				}
				catch (KafkaException ke) {
					ke.selfLog("AfterRollbackProcessor threw an exception", this.logger);
				}
				catch (Exception ex) {
					this.logger.error(ex, "AfterRollbackProcessor threw exception");
				}
			}
		}

		private void doInvokeWithRecords(final ConsumerRecords<K, V> records) {
			Iterator<ConsumerRecord<K, V>> iterator = records.iterator();
			while (iterator.hasNext()) {
				if (this.stopImmediate && !isRunning()) {
					break;
				}
				final ConsumerRecord<K, V> cRecord = checkEarlyIntercept(iterator.next());
				if (cRecord == null) {
					continue;
				}
				this.logger.trace(() -> "Processing " + KafkaUtils.format(cRecord));
				doInvokeRecordListener(cRecord, iterator);
				if (this.commonRecordInterceptor !=  null) {
					this.commonRecordInterceptor.afterRecord(cRecord, this.consumer);
				}
				if (this.nackSleepDurationMillis >= 0) {
					handleNack(records, cRecord);
					break;
				}
				if (checkImmediatePause(iterator)) {
					break;
				}
			}
		}

		private boolean checkImmediatePause(Iterator<ConsumerRecord<K, V>> iterator) {
			if (isPauseRequested() && this.pauseImmediate) {
				Map<TopicPartition, List<ConsumerRecord<K, V>>> remaining = new LinkedHashMap<>();
				while (iterator.hasNext()) {
					ConsumerRecord<K, V> next = iterator.next();
					remaining.computeIfAbsent(new TopicPartition(next.topic(), next.partition()),
							tp -> new ArrayList<ConsumerRecord<K, V>>()).add(next);
				}
				if (!remaining.isEmpty()) {
					this.remainingRecords = new ConsumerRecords<>(remaining, Map.of());
					return true;
				}
			}
			return false;
		}

		@Nullable
		private ConsumerRecords<K, V> checkEarlyIntercept(ConsumerRecords<K, V> nextArg) {
			ConsumerRecords<K, V> next = nextArg;
			if (this.earlyBatchInterceptor != null) {
				next = this.earlyBatchInterceptor.intercept(next, this.consumer);
				if (next == null) {
					this.logger.debug(() -> "BatchInterceptor returned null, skipping: "
						+ nextArg + " with " + nextArg.count() + " records");
					try {
						ackBatch(nextArg);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					this.earlyBatchInterceptor.success(nextArg, this.consumer);
				}
			}
			return next;
		}

		@Nullable
		private ConsumerRecord<K, V> checkEarlyIntercept(ConsumerRecord<K, V> recordArg) {
			internalHeaders(recordArg);
			ConsumerRecord<K, V> cRecord = recordArg;
			if (this.earlyRecordInterceptor != null) {
				cRecord = this.earlyRecordInterceptor.intercept(cRecord, this.consumer);
				if (cRecord == null) {
					this.logger.debug(() -> "RecordInterceptor returned null, skipping: "
						+ KafkaUtils.format(recordArg));
					ackCurrent(recordArg);
					this.earlyRecordInterceptor.success(recordArg, this.consumer);
					this.earlyRecordInterceptor.afterRecord(recordArg, this.consumer);
				}
			}
			return cRecord;
		}

		private void internalHeaders(final ConsumerRecord<K, V> cRecord) {
			if (this.deliveryAttemptAware != null) {
				byte[] buff = new byte[4]; // NOSONAR (magic #)
				ByteBuffer bb = ByteBuffer.wrap(buff);
				bb.putInt(this.deliveryAttemptAware
						.deliveryAttempt(
								new TopicPartitionOffset(cRecord.topic(), cRecord.partition(), cRecord.offset())));
				cRecord.headers().add(new RecordHeader(KafkaHeaders.DELIVERY_ATTEMPT, buff));
			}
			if (this.listenerinfo != null) {
				listenerInfo(cRecord);
			}
		}

		private void listenerInfo(final ConsumerRecord<K, V> cRecord) {
			cRecord.headers().add(this.infoHeader);
		}

		private void handleNack(final ConsumerRecords<K, V> records, final ConsumerRecord<K, V> cRecord) {
			if (!this.autoCommit && !this.isRecordAck) {
				processCommits();
			}
			List<ConsumerRecord<?, ?>> list = new ArrayList<>();
			Iterator<ConsumerRecord<K, V>> iterator2 = records.iterator();
			while (iterator2.hasNext()) {
				ConsumerRecord<K, V> next = iterator2.next();
				if (!list.isEmpty() || recordsEqual(cRecord, next)) {
					list.add(next);
				}
			}
			SeekUtils.doSeeks(list, this.consumer, null, true, (rec, ex) -> false, this.logger); // NOSONAR
			pauseForNackSleep();
		}

		private boolean recordsEqual(ConsumerRecord<K, V> rec1, ConsumerRecord<K, V> rec2) {
			return rec1.topic().equals(rec2.topic())
					&& rec1.partition() == rec2.partition()
					&& rec1.offset() == rec2.offset();
		}

		private void pauseForNackSleep() {
			if (this.nackSleepDurationMillis > 0) {
				this.nackWakeTimeMillis = System.currentTimeMillis() + this.nackSleepDurationMillis;
				Set<TopicPartition> alreadyPaused = this.consumer.paused();
				Collection<TopicPartition> assigned = getAssignedPartitions();
				if (assigned != null) {
					this.pausedForNack.addAll(assigned);
				}
				this.pausedForNack.removeAll(alreadyPaused);
				this.logger.debug(() -> "Pausing for nack sleep: " + ListenerConsumer.this.pausedForNack);
				try {
					this.consumer.pause(this.pausedForNack);
					publishConsumerPausedEvent(this.pausedForNack, "Nack with sleep time received");
				}
				catch (IllegalStateException ex) {
					// this should never happen; defensive, just in case...
					this.logger.warn(() -> "Could not pause for nack, possible rebalance in process: "
							+ ex.getMessage());
					Set<TopicPartition> nowPaused = new HashSet<>(this.consumer.paused());
					nowPaused.removeAll(alreadyPaused);
					this.consumer.resume(nowPaused);
				}
			}
			this.nackSleepDurationMillis = -1;
		}

		@SuppressWarnings(RAWTYPES)
		private Producer<?, ?> getTxProducer() {
			return ((KafkaResourceHolder) Objects.requireNonNull(TransactionSynchronizationManager
					.getResource(Objects.requireNonNull(ListenerConsumer.this.kafkaTxManager).getProducerFactory())))
					.getProducer(); // NOSONAR
		}

		/**
		 * Actually invoke the listener.
		 * @param cRecord the record.
		 * @param iterator the {@link ConsumerRecords} iterator.
		 * @return an exception.
		 * @throws Error an error.
		 */
		@Nullable
		private RuntimeException doInvokeRecordListener(final ConsumerRecord<K, V> cRecord, // NOSONAR
				Iterator<ConsumerRecord<K, V>> iterator) {

			Object sample = startMicrometerSample();
			Observation observation = KafkaListenerObservation.LISTENER_OBSERVATION.observation(
					this.containerProperties.getObservationConvention(),
					DefaultKafkaListenerObservationConvention.INSTANCE,
					() -> new KafkaRecordReceiverContext(cRecord, getListenerId(), getClientId(), this.consumerGroupId,
							this::clusterId),
					this.observationRegistry);

			observation.start();
			Observation.Scope observationScope = observation.openScope();
			// We cannot use 'try-with-resource' because the resource is closed just before catch block
			try {
				invokeOnMessage(cRecord);
				successTimer(sample, cRecord);
				recordInterceptAfter(cRecord, null);
			}
			catch (RuntimeException e) {
				failureTimer(sample, cRecord, e);
				recordInterceptAfter(cRecord, e);
				if (!isListenerAdapterObservationAware()) {
					observation.error(e);
				}
				if (this.commonErrorHandler == null) {
					throw e;
				}
				try {
					invokeErrorHandler(cRecord, iterator, e);
					commitOffsetsIfNeededAfterHandlingError(cRecord);
				}
				catch (RecordInRetryException rire) {
					this.logger.info("Record in retry and not yet recovered");
					return rire;
				}
				catch (KafkaException ke) {
					ke.selfLog(ERROR_HANDLER_THREW_AN_EXCEPTION, this.logger);
					return ke;
				}
				catch (RuntimeException ee) {
					this.logger.error(ee, ERROR_HANDLER_THREW_AN_EXCEPTION);
					return ee;
				}
				catch (Error er) { // NOSONAR
					this.logger.error(er, "Error handler threw an error");
					throw er;
				}
			}
			finally {
				if (!isListenerAdapterObservationAware()) {
					observation.stop();
				}
				observationScope.close();
			}
			return null;
		}

		private void commitOffsetsIfNeededAfterHandlingError(final ConsumerRecord<K, V> cRecord) {
			if ((!this.autoCommit && Objects.requireNonNull(this.commonErrorHandler).isAckAfterHandle() && this.consumerGroupId != null)
					|| this.producer != null) {
				if (this.remainingRecords == null
						|| !cRecord.equals(this.remainingRecords.iterator().next())) {
					if (this.offsetsInThisBatch != null) { // NOSONAR (sync)
						ackInOrder(cRecord);
					}
					else {
						ackCurrent(cRecord, this.isManualAck);
					}
				}
			}
		}

		private void recordInterceptAfter(ConsumerRecord<K, V> records, @Nullable Exception exception) {
			if (this.commonRecordInterceptor != null) {
				try {
					if (exception == null) {
						this.commonRecordInterceptor.success(records, this.consumer);
					}
					else {
						this.commonRecordInterceptor.failure(records, exception, this.consumer);
					}
				}
				catch (Exception e) {
					this.logger.error(e, "RecordInterceptor.success/failure threw an exception");
				}
			}
		}

		private void invokeOnMessage(final ConsumerRecord<K, V> cRecord) {

			if (cRecord.value() instanceof DeserializationException ex) {
				throw ex;
			}
			if (cRecord.key() instanceof DeserializationException ex) {
				throw ex;
			}
			if (cRecord.value() == null && this.checkNullValueForExceptions) {
				checkDeser(cRecord, SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER);
			}
			if (cRecord.key() == null && this.checkNullKeyForExceptions) {
				checkDeser(cRecord, SerializationUtils.KEY_DESERIALIZER_EXCEPTION_HEADER);
			}
			doInvokeOnMessage(cRecord);
			if (this.nackSleepDurationMillis < 0 && !this.isManualImmediateAck) {
				ackCurrent(cRecord);
			}
			if (this.isCountAck || this.isTimeOnlyAck) {
				doProcessCommits();
			}
		}

		private void doInvokeOnMessage(final ConsumerRecord<K, V> recordArg) {
			ConsumerRecord<K, V> cRecord = recordArg;
			if (this.recordInterceptor != null) {
				cRecord = this.recordInterceptor.intercept(cRecord, this.consumer);
			}
			if (cRecord == null) {
				this.logger.debug(() -> "RecordInterceptor returned null, skipping: "
						+ KafkaUtils.format(recordArg));
			}
			else {
				try {
					switch (this.listenerType) {
						case ACKNOWLEDGING_CONSUMER_AWARE ->
							Objects.requireNonNull(this.listener).onMessage(cRecord,
									this.isAnyManualAck
											? new ConsumerAcknowledgment(cRecord)
											: null, this.consumer);
						case CONSUMER_AWARE -> Objects.requireNonNull(this.listener).onMessage(cRecord, this.consumer);
						case ACKNOWLEDGING ->
							Objects.requireNonNull(this.listener).onMessage(cRecord,
									this.isAnyManualAck
											? new ConsumerAcknowledgment(cRecord)
											: null);
						case SIMPLE -> Objects.requireNonNull(this.listener).onMessage(cRecord);
					}
				}
				catch (Exception ex) { // NOSONAR
					throw decorateException(ex);
				}
			}
		}

		private void invokeErrorHandlerBySingleRecord(FailedRecordTuple<K, V> failedRecordTuple) {
			final ConsumerRecord<K, V> cRecord = failedRecordTuple.record;
			RuntimeException rte = failedRecordTuple.ex;
			if (Objects.requireNonNull(this.commonErrorHandler).seeksAfterHandling() || rte instanceof CommitFailedException) {
				try {
					if (this.producer == null) {
						processCommits();
					}
				}
				catch (Exception ex) { // NO SONAR
					this.logger.error(ex, "Failed to commit before handling error");
				}
				List<ConsumerRecord<?, ?>> records = new ArrayList<>();
				records.add(cRecord);
				this.commonErrorHandler.handleRemaining(rte, records, this.consumer,
														KafkaMessageListenerContainer.this.thisOrParentContainer);
			}
			else {
				boolean handled = false;
				try {
					handled = this.commonErrorHandler.handleOne(rte, cRecord, this.consumer,
																KafkaMessageListenerContainer.this.thisOrParentContainer);
				}
				catch (Exception ex) {
					this.logger.error(ex, "ErrorHandler threw unexpected exception");
				}
				Map<TopicPartition, List<ConsumerRecord<K, V>>> records = new LinkedHashMap<>();
				if (!handled) {
					records.computeIfAbsent(new TopicPartition(cRecord.topic(), cRecord.partition()),
											tp -> new ArrayList<>()).add(cRecord);
				}
				if (!records.isEmpty()) {
					this.remainingRecords = new ConsumerRecords<>(records, Map.of());
					this.pauseForPending = true;
				}
			}
		}

		private void invokeErrorHandler(final ConsumerRecord<K, V> cRecord,
				Iterator<ConsumerRecord<K, V>> iterator, RuntimeException rte) {

			if (Objects.requireNonNull(this.commonErrorHandler).seeksAfterHandling() || rte instanceof CommitFailedException) {
				try {
					if (this.producer == null) {
						processCommits();
					}
				}
				catch (Exception ex) { // NO SONAR
					this.logger.error(ex, "Failed to commit before handling error");
				}
				List<ConsumerRecord<?, ?>> records = new ArrayList<>();
				records.add(cRecord);
				while (iterator.hasNext()) {
					records.add(iterator.next());
				}
				this.commonErrorHandler.handleRemaining(rte, records, this.consumer,
						KafkaMessageListenerContainer.this.thisOrParentContainer);
			}
			else {
				boolean handled = false;
				try {
					handled = this.commonErrorHandler.handleOne(rte, cRecord, this.consumer,
							KafkaMessageListenerContainer.this.thisOrParentContainer);
				}
				catch (Exception ex) {
					this.logger.error(ex, "ErrorHandler threw unexpected exception");
				}
				Map<TopicPartition, List<ConsumerRecord<K, V>>> records = new LinkedHashMap<>();
				if (!handled) {
					records.computeIfAbsent(new TopicPartition(cRecord.topic(), cRecord.partition()),
							tp -> new ArrayList<>()).add(cRecord);
					while (iterator.hasNext()) {
						ConsumerRecord<K, V> next = iterator.next();
						records.computeIfAbsent(new TopicPartition(next.topic(), next.partition()),
								tp -> new ArrayList<>()).add(next);
					}
				}
				if (!records.isEmpty()) {
					this.remainingRecords = new ConsumerRecords<>(records, Map.of());
					this.pauseForPending = true;
				}
			}
		}

		private RuntimeException decorateException(Exception ex) {
			Exception toHandle = ex;
			if (toHandle instanceof ListenerExecutionFailedException) {
				String message = toHandle.getMessage() == null ? "Error occurred" : toHandle.getMessage();
				toHandle = new ListenerExecutionFailedException(message, this.consumerGroupId,
						toHandle.getCause()); // NOSONAR restored below
				fixStackTrace(ex, toHandle);
			}
			else {
				toHandle = new ListenerExecutionFailedException("Listener failed", this.consumerGroupId, toHandle);
			}
			return (RuntimeException) toHandle;
		}

		private void fixStackTrace(Exception ex, Exception toHandle) {
			try {
				StackTraceElement[] stackTrace = ex.getStackTrace();
				if (stackTrace != null && stackTrace.length > 0) {
					StackTraceElement[] stackTrace2 = toHandle.getStackTrace();
					if (stackTrace2 != null) {
						int matching = -1;
						for (int i = 0; i < stackTrace2.length; i++) {
							StackTraceElement se2 = stackTrace[i];
							for (StackTraceElement se : stackTrace2) {
								if (se2.equals(se)) {
									matching = i;
									break;
								}
							}
							if (matching >= 0) {
								break;
							}
						}
						if (matching >= 0) {
							StackTraceElement[] merged = new StackTraceElement[matching];
							System.arraycopy(stackTrace, 0, merged, 0, matching);
							ListenerExecutionFailedException suppressed =
									new ListenerExecutionFailedException("Restored Stack Trace");
							suppressed.setStackTrace(merged);
							toHandle.addSuppressed(suppressed);
						}
					}
				}
			}
			catch (Exception ex2) {
				this.logger.debug(ex2,
						"Could not restore the stack trace when decorating the LEFE with the group id");
			}
		}

		public void checkDeser(final ConsumerRecord<K, V> cRecord, String headerName) {
			DeserializationException exception = SerializationUtils.getExceptionFromHeader(cRecord, headerName,
					this.logger);
			if (exception != null) {
				/*
				 * Wrapping in a LEFE is not strictly correct, but required for backwards compatibility.
				 */
				throw decorateException(exception);
			}
		}

		public void ackCurrent(final ConsumerRecord<K, V> cRecord) {
			ackCurrent(cRecord, false);
		}

		public void ackCurrent(final ConsumerRecord<K, V> cRecord, boolean commitRecovered) {
			if (this.isRecordAck && this.producer == null) {
				Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = buildSingleCommits(cRecord);
				this.commitLogger.log(() -> COMMITTING + offsetsToCommit);
				commitOffsets(offsetsToCommit);
			}
			else if (this.producer != null) {
				this.acks.add(cRecord);
				sendOffsetsToTransaction();
			}
			else if (!this.autoCommit && (!this.isAnyManualAck || commitRecovered)) {
				this.acks.add(cRecord);
			}
		}

		private void sendOffsetsToTransaction() {
			handleAcks();
			Map<TopicPartition, OffsetAndMetadata> commits = buildCommits();
			this.commitLogger.log(() -> "Sending offsets to transaction: " + commits);
			doSendOffsets(this.producer, commits);
		}

		private void doSendOffsets(@Nullable Producer<?, ?> prod, Map<TopicPartition, OffsetAndMetadata> commits) {
			if (CollectionUtils.isEmpty(commits)) {
				return;
			}
			Objects.requireNonNull(prod).sendOffsetsToTransaction(commits, this.consumer.groupMetadata());
			if (this.fixTxOffsets) {
				this.lastCommits.putAll(commits);
			}
		}

		private void processCommits() {
			this.count += this.acks.size();
			handleAcks();
			if (this.isCountAck) {
				countAcks();
			}
			else if (this.isTimeAck) {
				timedAcks();
			}
			else if (!this.isManualImmediateAck) {
				commitIfNecessary();
				this.count = 0;
			}
		}

		private void countAcks() {
			boolean countExceeded = this.isCountAck && this.count >= this.containerProperties.getAckCount();
			if (countExceeded) {
				this.logger.debug(() -> "Committing in " + this.ackMode.name() + " because count "
						+ this.count
						+ " exceeds configured limit of " + this.containerProperties.getAckCount());
				commitIfNecessary();
				this.count = 0;
				if (AckMode.COUNT_TIME.equals(this.ackMode)) {
					this.last = System.currentTimeMillis();
				}
			}
		}

		private void timedAcks() {
			long now = System.currentTimeMillis();
			boolean elapsed = this.isTimeAck && now - this.last > this.containerProperties.getAckTime();
			if (elapsed) {
				this.logger.debug(() -> "Committing in " + this.ackMode.name() +
						"because time elapsed exceeds configured limit of " +
						this.containerProperties.getAckTime());
				commitIfNecessary();
				this.last = now;
				if (AckMode.COUNT_TIME.equals(this.ackMode)) {
					this.count = 0;
				}
			}
		}

		private boolean checkPartitionAssignedBeforeSeek(@Nullable Collection<TopicPartition> assigned, TopicPartition topicPartition) {
			if (assigned != null && assigned.contains(topicPartition)) {
				return true;
			}
			this.logger.warn("No current assignment for partition '" + topicPartition +
					"' due to partition reassignment prior to seeking.");
			return false;
		}

		private void processSeeks() {
			Collection<TopicPartition> assigned = getAssignedPartitions();
			processTimestampSeeks(assigned);
			TopicPartitionOffset offset = this.seeks.poll();
			while (offset != null) {
				traceSeek(offset);
				try {
					TopicPartition topicPartition = offset.getTopicPartition();
					if (!checkPartitionAssignedBeforeSeek(assigned, topicPartition)) {
						offset = this.seeks.poll();
						continue;
					}
					SeekPosition position = offset.getPosition();
					Long whereTo = offset.getOffset();
					Function<Long, Long> offsetComputeFunction = offset.getOffsetComputeFunction();
					if (position == null) {
						if (offset.isRelativeToCurrent()) {
							long topicPartitionPosition = this.consumer.position(topicPartition);
							Assert.state(whereTo != null, "Current offset must not be null");
							whereTo += topicPartitionPosition;
							whereTo = Math.max(whereTo, 0);
						}
						else if (offsetComputeFunction != null) {
							whereTo = offsetComputeFunction.apply(this.consumer.position(topicPartition));
						}
						Assert.state(whereTo != null, "offset to seek cannot be null");
						this.consumer.seek(topicPartition, whereTo);
					}
					else if (SeekPosition.TIMESTAMP.equals(position)) {
						// possible late addition since the grouped processing above
						Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes = this.consumer
								.offsetsForTimes(
										Collections.singletonMap(topicPartition, offset.getOffset()));
						offsetsForTimes.forEach((tp, ot) -> {
							if (ot != null) {
								this.consumer.seek(tp, ot.offset());
							}
						});
					}
					else {
						if (SeekPosition.BEGINNING.equals(position)) {
							this.consumer.seekToBeginning(Collections.singletonList(topicPartition));
						}
						else {
							this.consumer.seekToEnd(Collections.singletonList(topicPartition));
						}
						if (whereTo != null) {
							whereTo += this.consumer.position(topicPartition);
							this.consumer.seek(topicPartition, whereTo);
						}
					}
				}
				catch (Exception e) {
					TopicPartitionOffset offsetToLog = offset;
					this.logger.error(e, () -> "Exception while seeking " + offsetToLog);
				}
				offset = this.seeks.poll();
			}
		}

		private void processTimestampSeeks(@Nullable Collection<TopicPartition> assigned) {
			Iterator<TopicPartitionOffset> seekIterator = this.seeks.iterator();
			Map<TopicPartition, Long> timestampSeeks = null;
			while (seekIterator.hasNext()) {
				TopicPartitionOffset tpo = seekIterator.next();
				if (!checkPartitionAssignedBeforeSeek(assigned, tpo.getTopicPartition())) {
					seekIterator.remove();
					continue;
				}
				if (SeekPosition.TIMESTAMP.equals(tpo.getPosition())) {
					if (timestampSeeks == null) {
						timestampSeeks = new HashMap<>();
					}
					timestampSeeks.put(tpo.getTopicPartition(), tpo.getOffset());
					seekIterator.remove();
					traceSeek(tpo);
				}
			}
			if (timestampSeeks != null) {
				Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes = this.consumer.offsetsForTimes(timestampSeeks);
				offsetsForTimes.forEach((tp, ot) -> {
					if (ot != null) {
						this.consumer.seek(tp, ot.offset());
					}
				});
			}
		}

		private void traceSeek(TopicPartitionOffset offset) {
			this.logger.trace(() -> "Seek: " + offset);
		}

		private void initPartitionsIfNeeded() {
			/*
			 * Note: initial position setting is only supported with explicit topic assignment.
			 * When using auto assignment (subscribe), the ConsumerRebalanceListener is not
			 * called until we poll() the consumer. Users can use a ConsumerAwareRebalanceListener
			 * or a ConsumerSeekAware listener in that case.
			 */
			Map<TopicPartition, OffsetMetadata> partitions = new LinkedHashMap<>(this.definedPartitions);
			Set<TopicPartition> beginnings = partitions.entrySet().stream()
					.filter(e -> SeekPosition.BEGINNING.equals(e.getValue().seekPosition))
					.map(Entry::getKey)
					.collect(Collectors.toSet());
			beginnings.forEach(partitions::remove);
			Set<TopicPartition> ends = partitions.entrySet().stream()
					.filter(e -> SeekPosition.END.equals(e.getValue().seekPosition))
					.map(Entry::getKey)
					.collect(Collectors.toSet());
			ends.forEach(partitions::remove);
			Map<TopicPartition, Long> times = partitions.entrySet().stream()
					.filter(e -> SeekPosition.TIMESTAMP.equals(e.getValue().seekPosition))
					.collect(Collectors.toMap(Entry::getKey, entry -> entry.getValue().offset));
			if (!times.isEmpty()) {
				times.forEach((key, value) -> partitions.remove(key));
				Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes = this.consumer.offsetsForTimes(times);
				offsetsForTimes.forEach((tp, off) -> {
					if (off == null) {
						ends.add(tp);
					}
					else {
						partitions.put(tp, new OffsetMetadata(off.offset(), false, SeekPosition.TIMESTAMP));
					}
				});
			}
			doInitialSeeks(partitions, beginnings, ends);
			if (this.consumerSeekAwareListener != null) {
				this.consumerSeekAwareListener.onPartitionsAssigned(Objects.requireNonNull(this.definedPartitions).keySet().stream()
							.map(tp -> new SimpleEntry<>(tp, this.consumer.position(tp)))
							.collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue)),
						this.seekCallback);
			}
		}

		private void doInitialSeeks(Map<TopicPartition, OffsetMetadata> partitions, Set<TopicPartition> beginnings,
				Set<TopicPartition> ends) {

			if (!beginnings.isEmpty()) {
				this.consumer.seekToBeginning(beginnings);
			}
			if (!ends.isEmpty()) {
				this.consumer.seekToEnd(ends);
			}
			for (Entry<TopicPartition, OffsetMetadata> entry : partitions.entrySet()) {
				TopicPartition topicPartition = entry.getKey();
				OffsetMetadata metadata = entry.getValue();
				Long offset = metadata.offset;
				if (offset != null) {
					long newOffset = offset;

					if (offset < 0) {
						if (!metadata.relativeToCurrent) {
							this.consumer.seekToEnd(Collections.singletonList(topicPartition));
						}
						newOffset = Math.max(0, this.consumer.position(topicPartition) + offset);
					}
					else if (metadata.relativeToCurrent) {
						newOffset = this.consumer.position(topicPartition) + offset;
					}

					try {
						this.consumer.seek(topicPartition, newOffset);
						logReset(topicPartition, newOffset);
					}
					catch (Exception e) {
						long newOffsetToLog = newOffset;
						this.logger.error(e, () -> "Failed to set initial offset for " + topicPartition
								+ " at " + newOffsetToLog + ". Position is " + this.consumer.position(topicPartition));
					}
				}
			}
		}

		private void logReset(TopicPartition topicPartition, long newOffset) {
			this.logger.debug(() -> "Reset " + topicPartition + " to offset " + newOffset);
		}

		private void addOffset(ConsumerRecord<K, V> cRecord) {
			this.offsets.compute(new TopicPartition(cRecord.topic(), cRecord.partition()),
					(k, v) -> v == null ? cRecord.offset() : Math.max(v, cRecord.offset()));
		}

		private void commitIfNecessary() {
			Map<TopicPartition, OffsetAndMetadata> commits = buildCommits();
			this.logger.debug(() -> "Commit list: " + commits);
			if (!commits.isEmpty()) {
				this.commitLogger.log(() -> COMMITTING + commits);
				try {
					commitOffsets(commits);
				}
				catch (@SuppressWarnings(UNUSED) WakeupException e) {
					// ignore - not polling
					this.logger.debug("Woken up during commit");
				}
			}
		}

		private void commitOffsetsInTransactions(Map<TopicPartition, OffsetAndMetadata> commits) {
			this.commitLogger.log(() -> COMMITTING + commits);
			if (this.producer != null) {
				doSendOffsets(this.producer, commits);
			}
			else {
				commitOffsets(commits);
			}
		}

		private void commitOffsets(Map<TopicPartition, OffsetAndMetadata> commits) {
			if (CollectionUtils.isEmpty(commits)) {
				return;
			}
			if (this.syncCommits) {
				commitSync(commits);
			}
			else {
				commitAsync(commits);
			}
		}

		private void commitAsync(Map<TopicPartition, OffsetAndMetadata> commits) {
			this.consumer.commitAsync(commits, (offsetsAttempted, exception) -> {
				this.commitCallback.onComplete(offsetsAttempted, exception);
				if (exception == null && this.fixTxOffsets) {
					this.lastCommits.putAll(commits);
				}
			});
		}

		private void commitSync(Map<TopicPartition, OffsetAndMetadata> commits) {
			doCommitSync(commits, 0);
		}

		private void doCommitSync(Map<TopicPartition, OffsetAndMetadata> commits, int retries) {
			try {
				this.consumer.commitSync(commits, this.syncCommitTimeout);
				if (this.fixTxOffsets) {
					this.lastCommits.putAll(commits);
				}
				if (!this.commitsDuringRebalance.isEmpty()) {
					// Remove failed commits during last rebalance that are superseded by these commits
					this.commitsDuringRebalance.keySet().removeAll(commits.keySet());
				}
			}
			catch (RetriableCommitFailedException e) {
				if (retries >= this.containerProperties.getCommitRetries()) {
					throw e;
				}
				doCommitSync(commits, retries + 1);
			}
			catch (RebalanceInProgressException e) {
				this.logger.debug(e, "Non-fatal commit failure");
				this.commitsDuringRebalance.putAll(commits);
			}
		}

		Map<TopicPartition, OffsetAndMetadata> buildSingleCommits(ConsumerRecord<K, V> cRecord) {
			return Collections.singletonMap(
					new TopicPartition(cRecord.topic(), cRecord.partition()),
					createOffsetAndMetadata(cRecord.offset() + 1));
		}

		private Map<TopicPartition, OffsetAndMetadata> buildCommits() {
			Map<TopicPartition, OffsetAndMetadata> commits = new LinkedHashMap<>();
			this.offsets.forEach((topicPartition, offset) -> {
				commits.put(topicPartition, createOffsetAndMetadata(offset + 1));
			});
			this.offsets.clear();
			return commits;
		}

		private Collection<ConsumerRecord<K, V>> getHighestOffsetRecords(ConsumerRecords<K, V> records) {
			return records.partitions()
					.stream()
					.collect(Collectors.toMap(tp -> tp, tp -> {
						List<ConsumerRecord<K, V>> recordList = records.records(tp);
						return recordList.get(recordList.size() - 1);
					}))
					.values();
		}

		private Observation getCurrentObservation() {
			Observation currentObservation = this.observationRegistry.getCurrentObservation();
			return currentObservation == null ? Observation.NOOP : currentObservation;
		}

		private void callbackForAsyncFailure(ConsumerRecord<K, V> cRecord, RuntimeException ex) {
			this.failedRecords.addLast(new FailedRecordTuple<>(cRecord, ex, getCurrentObservation()));
		}

		@Override
		public void seek(String topic, int partition, long offset) {
			this.seeks.add(new TopicPartitionOffset(topic, partition, offset));
		}

		@Override
		public void seek(String topic, int partition, Function<Long, Long> offsetComputeFunction) {
			this.seeks.add(new TopicPartitionOffset(topic, partition, offsetComputeFunction));
		}

		@Override
		public void seekToBeginning(String topic, int partition) {
			this.seeks.add(new TopicPartitionOffset(topic, partition, SeekPosition.BEGINNING));
		}

		@Override
		public void seekToBeginning(Collection<TopicPartition> partitions) {
			this.seeks.addAll(partitions.stream()
					.map(tp -> new TopicPartitionOffset(tp.topic(), tp.partition(), SeekPosition.BEGINNING))
					.toList());
		}

		@Override
		public void seekToEnd(String topic, int partition) {
			this.seeks.add(new TopicPartitionOffset(topic, partition, SeekPosition.END));
		}

		@Override
		public void seekToEnd(Collection<TopicPartition> partitions) {
			this.seeks.addAll(partitions.stream()
					.map(tp -> new TopicPartitionOffset(tp.topic(), tp.partition(), SeekPosition.END))
					.toList());
		}

		@Override
		public void seekRelative(String topic, int partition, long offset, boolean toCurrent) {
			if (toCurrent) {
				this.seeks.add(new TopicPartitionOffset(topic, partition, offset, true));
			}
			else if (offset >= 0) {
				this.seeks.add(new TopicPartitionOffset(topic, partition, offset, SeekPosition.BEGINNING));
			}
			else {
				this.seeks.add(new TopicPartitionOffset(topic, partition, offset, SeekPosition.END));
			}
		}

		@Override
		public void seekToTimestamp(String topic, int partition, long timestamp) {
			this.seeks.add(new TopicPartitionOffset(topic, partition, timestamp, SeekPosition.TIMESTAMP));
		}

		@Override
		public void seekToTimestamp(Collection<TopicPartition> topicParts, long timestamp) {
			topicParts.forEach(tp -> seekToTimestamp(tp.topic(), tp.partition(), timestamp));
		}

		@Override
		public @Nullable String getGroupId() {
			return this.consumerGroupId;
		}

		@Override
		public String toString() {
			return "KafkaMessageListenerContainer.ListenerConsumer ["
					+ "\ncontainerProperties=" + this.containerProperties
					+ "\nother properties ["
					+ "\n listenerType=" + this.listenerType
					+ "\n isConsumerAwareListener=" + this.isConsumerAwareListener
					+ "\n isBatchListener=" + this.isBatchListener
					+ "\n autoCommit=" + this.autoCommit
					+ "\n consumerGroupId=" + this.consumerGroupId
					+ "\n clientIdSuffix=" + KafkaMessageListenerContainer.this.clientIdSuffix
					+ "\n]";
		}

		private OffsetAndMetadata createOffsetAndMetadata(long offset) {
			return this.offsetAndMetadataProvider.provide(this.listenerMetadata, offset);
		}

		private final class ConsumerAcknowledgment implements Acknowledgment {

			private final ConsumerRecord<K, V> cRecord;

			private volatile boolean acked;

			ConsumerAcknowledgment(ConsumerRecord<K, V> cRecord) {
				this.cRecord = cRecord;
			}

			@Override
			public void acknowledge() {
				if (!this.acked) {
					doAck(this.cRecord);
					this.acked = true;
				}
			}

			@Override
			public void nack(Duration sleep) {
				Assert.state(Thread.currentThread().equals(ListenerConsumer.this.consumerThread),
						"nack() can only be called on the consumer thread");
				Assert.state(!ListenerConsumer.this.asyncReplies,
						"nack() is not supported with out-of-order commits");
				Assert.isTrue(!sleep.isNegative(), "sleep cannot be negative");
				ListenerConsumer.this.nackSleepDurationMillis = sleep.toMillis();
			}

			@Override
			public boolean isOutOfOrderCommit() {
				return ListenerConsumer.this.asyncReplies;
			}

			@Override
			public String toString() {
				return "Acknowledgment for " + KafkaUtils.format(this.cRecord);
			}

		}

		private final class ConsumerBatchAcknowledgment implements Acknowledgment {

			private final ConsumerRecords<K, V> records;

			private final @Nullable List<ConsumerRecord<K, V>> recordList;

			private volatile boolean acked;

			private volatile int partial = -1;

			ConsumerBatchAcknowledgment(ConsumerRecords<K, V> records,
					@Nullable List<ConsumerRecord<K, V>> recordList) {

				this.records = records;
				this.recordList = recordList;
			}

			@Override
			public void acknowledge() {
				if (this.partial >= 0) {
					acknowledge(this.partial + 1);
					return;
				}
				if (!this.acked) {
					Map<TopicPartition, List<Long>> offs = ListenerConsumer.this.offsetsInThisBatch;
					Map<TopicPartition, List<ConsumerRecord<K, V>>> deferred = ListenerConsumer.this.deferredOffsets;
					for (TopicPartition topicPartition : this.records.partitions()) {
						if (offs != null) {
							offs.remove(topicPartition);
							Objects.requireNonNull(deferred).remove(topicPartition);
						}
					}
					processAcks(this.records);
					this.acked = true;
				}
			}

			@Override
			public void acknowledge(int index) {
				Assert.isTrue(index > this.partial,
						() -> String.format("index (%d) must be greater than the previous partial commit (%d)", index,
								this.partial));
				Assert.state(ListenerConsumer.this.isManualImmediateAck,
						"Partial batch acknowledgment is only supported with AckMode.MANUAL_IMMEDIATE");
				Assert.state(this.recordList != null,
						"Listener must receive a List of records to use partial batch acknowledgment");
				Assert.isTrue(index >= 0 && index < this.recordList.size(),
						() -> String.format("index (%d) is out of range (%d-%d)", index, 0,
								this.recordList.size() - 1));
				Assert.state(Thread.currentThread().equals(ListenerConsumer.this.consumerThread),
						"Partial batch acknowledgment is only supported on the consumer thread");
				Map<TopicPartition, List<ConsumerRecord<K, V>>> offsetsToCommit = new LinkedHashMap<>();
				for (int i = this.partial + 1; i <= index; i++) {
					ConsumerRecord<K, V> record = this.recordList.get(i);
					offsetsToCommit.computeIfAbsent(new TopicPartition(record.topic(), record.partition()),
							tp -> new ArrayList<>()).add(record);
				}
				if (!offsetsToCommit.isEmpty()) {
					processAcks(new ConsumerRecords<>(offsetsToCommit, Map.of()));
				}
				this.partial = index;
			}

			@Override
			public void nack(int index, Duration sleep) {
				Assert.state(Thread.currentThread().equals(ListenerConsumer.this.consumerThread),
						"nack() can only be called on the consumer thread");
				Assert.state(!ListenerConsumer.this.asyncReplies,
						"nack() is not supported with out-of-order commits");
				Assert.isTrue(!sleep.isNegative(), "sleep cannot be negative");
				Assert.isTrue(index >= 0 && index < this.records.count(), "index out of bounds");
				ListenerConsumer.this.nackIndex = index;
				ListenerConsumer.this.nackSleepDurationMillis = sleep.toMillis();
				int i = 0;
				List<ConsumerRecord<K, V>> toAck = new LinkedList<>();
				for (ConsumerRecord<K, V> cRecord : this.records) {
					if (i++ < index) {
						toAck.add(cRecord);
					}
					else {
						break;
					}
				}
				Map<TopicPartition, List<ConsumerRecord<K, V>>> newRecords = new HashMap<>();
				for (ConsumerRecord<K, V> cRecord : toAck) {
					newRecords.computeIfAbsent(new TopicPartition(cRecord.topic(), cRecord.partition()),
							tp -> new LinkedList<>()).add(cRecord);
				}
				processAcks(new ConsumerRecords<K, V>(newRecords, Map.of()));
			}

			@Override
			public boolean isOutOfOrderCommit() {
				return ListenerConsumer.this.asyncReplies;
			}

			@Override
			public String toString() {
				return "Acknowledgment for " + this.records;
			}

		}

		private class ListenerConsumerRebalanceListener implements ConsumerRebalanceListener {

			private final @Nullable ConsumerRebalanceListener userListener = getContainerProperties()
					.getConsumerRebalanceListener();

			private final @Nullable ConsumerAwareRebalanceListener consumerAwareListener =
					this.userListener instanceof ConsumerAwareRebalanceListener carl ? carl : null;

			private final Collection<TopicPartition> revoked = new LinkedList<>();

			ListenerConsumerRebalanceListener() {
			}

			@Override
			public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
				this.revoked.addAll(partitions);
				removeRevocationsFromPending(partitions);
				if (this.consumerAwareListener != null) {
					this.consumerAwareListener.onPartitionsRevokedBeforeCommit(ListenerConsumer.this.consumer,
							partitions);
				}
				else {
					Objects.requireNonNull(this.userListener).onPartitionsRevoked(partitions);
				}
				try {
					// Wait until now to commit, in case the user listener added acks
					checkRebalanceCommits();
					commitPendingAcks();
					fixTxOffsetsIfNeeded();
				}
				catch (Exception e) {
					ListenerConsumer.this.logger.error(e, () -> "Fatal commit error after revocation "
							+ partitions);
				}
				if (this.consumerAwareListener != null) {
					this.consumerAwareListener.onPartitionsRevokedAfterCommit(ListenerConsumer.this.consumer,
							partitions);
				}
				if (ListenerConsumer.this.consumerSeekAwareListener != null) {
					ListenerConsumer.this.consumerSeekAwareListener.onPartitionsRevoked(partitions);
				}
				if (ListenerConsumer.this.assignedPartitions != null) {
					ListenerConsumer.this.assignedPartitions.removeAll(partitions);
				}
				ListenerConsumer.this.pausedForNack.removeAll(partitions);
				partitions.forEach(ListenerConsumer.this.lastCommits::remove);
				synchronized (ListenerConsumer.this) {
					Map<TopicPartition, List<Long>> pendingOffsets = ListenerConsumer.this.offsetsInThisBatch;
					if (pendingOffsets != null) {
						partitions.forEach(tp -> {
							pendingOffsets.remove(tp);
							Objects.requireNonNull(ListenerConsumer.this.deferredOffsets).remove(tp);
						});
						if (pendingOffsets.isEmpty()) {
							ListenerConsumer.this.consumerPaused = false;
						}
					}
				}
			}

			private void removeRevocationsFromPending(Collection<TopicPartition> partitions) {
				ConsumerRecords<K, V> remaining = ListenerConsumer.this.remainingRecords;
				if (remaining != null && !partitions.isEmpty()) {
					Set<TopicPartition> remainingParts = new LinkedHashSet<>(remaining.partitions());
					remainingParts.removeAll(partitions);
					if (!remainingParts.isEmpty()) {
						Map<TopicPartition, List<ConsumerRecord<K, V>>> trimmed = new LinkedHashMap<>();
						remainingParts.forEach(part -> trimmed.computeIfAbsent(part, tp -> remaining.records(tp)));
						ListenerConsumer.this.remainingRecords = new ConsumerRecords<>(trimmed, Map.of());
					}
					else {
						ListenerConsumer.this.remainingRecords = null;
					}
					ListenerConsumer.this.logger.debug(() -> "Removed " + partitions + " from remaining records");
				}
			}

			@Override
			public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
				repauseIfNeeded(partitions);
				ListenerConsumer.this.assignedPartitions.addAll(partitions);
				if (ListenerConsumer.this.commitCurrentOnAssignment
						&& !collectAndCommitIfNecessary(partitions)) {
					return;
				}
				if (ListenerConsumer.this.genericListener instanceof ConsumerSeekAware) {
					seekPartitions(partitions, false);
				}
				if (this.consumerAwareListener != null) {
					this.consumerAwareListener.onPartitionsAssigned(ListenerConsumer.this.consumer, partitions);
				}
				else {
					Objects.requireNonNull(this.userListener).onPartitionsAssigned(partitions);
				}
				if (!ListenerConsumer.this.firstPoll && ListenerConsumer.this.definedPartitions == null
						&& ListenerConsumer.this.consumerSeekAwareListener != null) {

					ListenerConsumer.this.firstPoll = true;
					ListenerConsumer.this.consumerSeekAwareListener.onFirstPoll();
				}
				if (ListenerConsumer.this.commonErrorHandler != null) {
					ListenerConsumer.this.commonErrorHandler.onPartitionsAssigned(ListenerConsumer.this.consumer,
							partitions, () -> publishConsumerPausedEvent(partitions,
									"Paused by error handler after rebalance"));
				}
			}

			private void repauseIfNeeded(Collection<TopicPartition> partitions) {
				boolean pending = false;
				synchronized (ListenerConsumer.this) {
					if (!ObjectUtils.isEmpty(ListenerConsumer.this.offsetsInThisBatch)) {
						pending = true;
					}
				}
				if ((pending || isPauseRequested() || ListenerConsumer.this.remainingRecords != null)
						&& !partitions.isEmpty()) {

					ListenerConsumer.this.consumer.pause(partitions);
					ListenerConsumer.this.consumerPaused = true;
					ListenerConsumer.this.logger.warn("Paused consumer resumed by Kafka due to rebalance; "
							+ "consumer paused again, so the initial poll() will never return any records");
					ListenerConsumer.this.logger.debug(() -> "Paused consumption from: " + partitions);
					publishConsumerPausedEvent(partitions, "Re-paused after rebalance");
				}
				Collection<TopicPartition> toRepause = new LinkedList<>();
				partitions.forEach(tp -> {
					if (isPartitionPauseRequested(tp)) {
						toRepause.add(tp);
					}
				});
				if (!ListenerConsumer.this.consumerPaused && !toRepause.isEmpty()) {
					ListenerConsumer.this.consumer.pause(toRepause);
					ListenerConsumer.this.logger.debug(() -> "Paused consumption from: " + toRepause);
					publishConsumerPausedEvent(toRepause, "Re-paused after rebalance");
					ListenerConsumer.this.pausedPartitions.addAll(toRepause);
				}
				this.revoked.removeAll(toRepause);
				ListenerConsumer.this.pausedPartitions.removeAll(this.revoked);
				this.revoked.clear();
				if (!ListenerConsumer.this.pausedForNack.isEmpty()) {
					ListenerConsumer.this.consumer.pause(ListenerConsumer.this.pausedForNack);
				}
			}

			private boolean collectAndCommitIfNecessary(Collection<TopicPartition> partitions) {
				// Commit initial positions - this is generally redundant but
				// it protects us from the case when another consumer starts
				// and rebalance would cause it to reset at the end
				// see https://github.com/spring-projects/spring-kafka/issues/110
				Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
				Map<TopicPartition, OffsetAndMetadata> committed =
						ListenerConsumer.this.consumer.committed(new HashSet<>(partitions));
				for (TopicPartition partition : partitions) {
					try {
						if (committed.get(partition) == null) { // no existing commit for this group
							offsetsToCommit.put(partition, createOffsetAndMetadata(ListenerConsumer.this.consumer.position(partition)));
						}
					}
					catch (NoOffsetForPartitionException e) {
						ListenerConsumer.this.fatalError = true;
						ListenerConsumer.this.logger.error(e, "No offset and no reset policy");
						return false;
					}
				}
				if (!offsetsToCommit.isEmpty()) {
					commitCurrentOffsets(offsetsToCommit);
				}
				return true;
			}

			private void commitCurrentOffsets(Map<TopicPartition, OffsetAndMetadata> offsetsToCommit) {
				ListenerConsumer.this.commitLogger.log(() -> "Committing on assignment: " + offsetsToCommit);
				if (ListenerConsumer.this.transactionTemplate != null
						&& ListenerConsumer.this.kafkaTxManager != null
						&& !AssignmentCommitOption.LATEST_ONLY_NO_TX.equals(ListenerConsumer.this.autoCommitOption)) {

					offsetsToCommit.forEach((partition, offsetAndMetadata) -> {
						ListenerConsumer.this.transactionTemplate
								.execute(new TransactionCallbackWithoutResult() {

									@Override
									protected void doInTransactionWithoutResult(TransactionStatus status) {
										KafkaResourceHolder<?, ?> holder =
												(KafkaResourceHolder<?, ?>) TransactionSynchronizationManager
														.getResource(ListenerConsumer.this.kafkaTxManager
																.getProducerFactory());
										if (holder != null) {
											doSendOffsets(holder.getProducer(),
													Collections.singletonMap(partition, offsetAndMetadata));
										}
									}

								});
					});
				}
				else {
					ContainerProperties containerProps = KafkaMessageListenerContainer.this.getContainerProperties();
					if (containerProps.isSyncCommits()) {
						try {
							ListenerConsumer.this.consumer.commitSync(offsetsToCommit,
									containerProps.getSyncCommitTimeout());
						}
						catch (RetriableCommitFailedException | RebalanceInProgressException e) {
							// ignore since this is on assignment anyway
						}
					}
					else {
						commitAsync(offsetsToCommit);
					}
				}
			}

			@Override
			public void onPartitionsLost(Collection<TopicPartition> partitions) {
				if (this.consumerAwareListener != null) {
					this.consumerAwareListener.onPartitionsLost(ListenerConsumer.this.consumer, partitions);
				}
				else {
					Objects.requireNonNull(this.userListener).onPartitionsLost(partitions);
				}
				onPartitionsRevoked(partitions);
			}

		}

		private final class InitialOrIdleSeekCallback implements ConsumerSeekCallback {

			InitialOrIdleSeekCallback() {
			}

			@Override
			public void seek(String topic, int partition, long offset) {
				ListenerConsumer.this.consumer.seek(new TopicPartition(topic, partition), offset);
			}

			@Override
			public void seek(String topic, int partition, Function<Long, Long> offsetComputeFunction) {
				ListenerConsumer.this.consumer.seek(new TopicPartition(topic, partition),
						offsetComputeFunction.apply(
								ListenerConsumer.this.consumer.position(new TopicPartition(topic, partition))));
			}

			@Override
			public void seekToBeginning(String topic, int partition) {
				ListenerConsumer.this.consumer.seekToBeginning(
						Collections.singletonList(new TopicPartition(topic, partition)));
			}

			@Override
			public void seekToBeginning(Collection<TopicPartition> partitions) {
				ListenerConsumer.this.consumer.seekToBeginning(partitions);
			}

			@Override
			public void seekToEnd(String topic, int partition) {
				ListenerConsumer.this.consumer.seekToEnd(
						Collections.singletonList(new TopicPartition(topic, partition)));
			}

			@Override
			public void seekToEnd(Collection<TopicPartition> partitions) {
				ListenerConsumer.this.consumer.seekToEnd(partitions);
			}

			@Override
			public void seekRelative(String topic, int partition, long offset, boolean toCurrent) {
				TopicPartition topicPart = new TopicPartition(topic, partition);
				Long whereTo = null;
				Consumer<K, V> consumerToSeek = ListenerConsumer.this.consumer;
				if (offset >= 0) {
					whereTo = computeForwardWhereTo(offset, toCurrent, topicPart, consumerToSeek);
				}
				else {
					whereTo = computeBackwardWhereTo(offset, toCurrent, topicPart, consumerToSeek);
				}
				if (whereTo != null) {
					consumerToSeek.seek(topicPart, whereTo);
				}
			}

			@Override
			public void seekToTimestamp(String topic, int partition, long timestamp) {
				Consumer<K, V> consumerToSeek = ListenerConsumer.this.consumer;
				Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes = consumerToSeek.offsetsForTimes(
						Collections.singletonMap(new TopicPartition(topic, partition), timestamp));
				offsetsForTimes.forEach((tp, ot) -> {
					if (ot != null) {
						consumerToSeek.seek(tp, ot.offset());
					}
				});
			}

			@Override
			public void seekToTimestamp(Collection<TopicPartition> topicParts, long timestamp) {
				Consumer<K, V> consumerToSeek = ListenerConsumer.this.consumer;
				Map<TopicPartition, Long> map = topicParts.stream()
						.collect(Collectors.toMap(tp -> tp, tp -> timestamp));
				Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes = consumerToSeek.offsetsForTimes(map);
				offsetsForTimes.forEach((tp, ot) -> {
					if (ot != null) {
						consumerToSeek.seek(tp, ot.offset());
					}
				});
			}

			@Nullable
			private Long computeForwardWhereTo(long offset, boolean toCurrent, TopicPartition topicPart,
					Consumer<K, V> consumerToSeek) {

				Long start;
				if (!toCurrent) {
					Map<TopicPartition, Long> beginning = consumerToSeek
							.beginningOffsets(Collections.singletonList(topicPart));
					start = beginning.get(topicPart);
				}
				else {
					start = consumerToSeek.position(topicPart);
				}
				if (start != null) {
					return start + offset;
				}
				return null;
			}

			@Nullable
			private Long computeBackwardWhereTo(long offset, boolean toCurrent, TopicPartition topicPart,
					Consumer<K, V> consumerToSeek) {

				Long end;
				if (!toCurrent) {
					Map<TopicPartition, Long> endings = consumerToSeek
							.endOffsets(Collections.singletonList(topicPart));
					end = endings.get(topicPart);
				}
				else {
					end = consumerToSeek.position(topicPart);
				}
				if (end != null) {
					long newOffset = end + offset;
					return newOffset < 0 ? 0 : newOffset;
				}
				return null;
			}

		}

	}


	/**
	 * Offset metadata record.
	 * @param offset            current offset.
	 * @param relativeToCurrent relative to current.
	 * @param seekPosition      seek position strategy.
	 */
	private record OffsetMetadata(@Nullable Long offset, boolean relativeToCurrent, @Nullable SeekPosition seekPosition) {
	}

	private class StopCallback implements BiConsumer<Object, Throwable> {

		private final @Nullable Runnable callback;

		StopCallback(@Nullable Runnable callback) {
			this.callback = callback;
		}

		@Override
		public void accept(Object result, @Nullable Throwable throwable) {
			if (throwable != null) {
				KafkaMessageListenerContainer.this.logger.error(throwable, "Error while stopping the container");
			}
			else {
				KafkaMessageListenerContainer.this.logger
						.debug(() -> KafkaMessageListenerContainer.this + " stopped normally");
			}
			if (this.callback != null) {
				this.callback.run();
			}
		}

	}

	@SuppressWarnings("serial")
	private static class StopAfterFenceException extends KafkaException {

		StopAfterFenceException(String message, Throwable t) {
			super(message, t);
		}

	}

	private record FailedRecordTuple<K, V>(ConsumerRecord<K, V> record, RuntimeException ex, Observation observation) { }

}
