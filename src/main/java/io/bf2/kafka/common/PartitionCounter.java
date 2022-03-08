/*
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.bf2.kafka.common;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.bf2.kafka.authorizer.AclLoggingConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class PartitionCounter implements AutoCloseable {

    public static final String MAX_PARTITIONS = "max.partitions";
    public static final String PRIVATE_TOPIC_PREFIX = "strimzi.authorization.custom-authorizer.partition-counter.private-topic-prefix";
    public static final String TIMEOUT_SECONDS = "strimzi.authorization.custom-authorizer.partition-counter.timeout-seconds";
    public static final String SCHEDULE_INTERVAL_SECONDS = "strimzi.authorization.custom-authorizer.partition-counter.schedule-interval-seconds";

    static final int DEFAULT_MAX_PARTITIONS = -1;
    static final String DEFAULT_PRIVATE_TOPIC_PREFIX = "__redhat_";
    static final int DEFAULT_TIMEOUT_SECONDS = 10;
    static final int DEFAULT_SCHEDULE_INTERVAL_SECONDS = 15;

    private static final ConfigDef configDef = new ConfigDef()
            .define(MAX_PARTITIONS, ConfigDef.Type.INT, DEFAULT_MAX_PARTITIONS, ConfigDef.Importance.MEDIUM, "Max partitions")
            .define(PRIVATE_TOPIC_PREFIX, ConfigDef.Type.STRING, DEFAULT_PRIVATE_TOPIC_PREFIX, ConfigDef.Importance.MEDIUM, "Internal Partition Prefix")
            .define(TIMEOUT_SECONDS, ConfigDef.Type.INT, DEFAULT_TIMEOUT_SECONDS,ConfigDef.Importance.MEDIUM, "Timeout duration for listing and describing topics")
            .define(SCHEDULE_INTERVAL_SECONDS, ConfigDef.Type.INT, DEFAULT_SCHEDULE_INTERVAL_SECONDS,ConfigDef.Importance.MEDIUM, "Schedule interval for scheduled counter");

    private static final Logger log = LoggerFactory.getLogger(AclLoggingConfig.class);

    private static PartitionCounter partitionCounter;

    private final int maxPartitions;

    private final Admin admin;

    private AtomicInteger existingPartitionCount;

    private AtomicInteger handles;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledPartitionCounter;

    final AbstractConfig config;

    public static synchronized PartitionCounter create(Map<String, ?> config) {
        if (partitionCounter == null) {
            partitionCounter = new PartitionCounter(config);
        }
        partitionCounter.start();
        partitionCounter.handles.incrementAndGet();
        return partitionCounter;
    }

    PartitionCounter(Map<String, ?> config) {
        this.config = new AbstractConfig(configDef, config);
        this.admin = LocalAdminClient.create(config);
        existingPartitionCount = new AtomicInteger(0);
        handles = new AtomicInteger(0);
        ThreadFactory threadFactory =
                new ThreadFactoryBuilder().setNameFormat("partition-counter").setDaemon(true).build();
        scheduler = Executors.newScheduledThreadPool(1, threadFactory);
        maxPartitions = setMaxPartitions();
    }

    @Override
    public void close() {
        if (handles.decrementAndGet() == 0) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }

            if (admin != null) {
                admin.close(Duration.ofMillis(500));
            }
        }
    }

    /**
     * @return the existingPartitionCount
     */
    public int getExistingPartitionCount() {
        return existingPartitionCount.get();
    }

    public int getMaxPartitions() {
        return maxPartitions;
    }

    private int setMaxPartitions() {
        try {
            return config.getInt(MAX_PARTITIONS);
        } catch (ConfigException | NullPointerException | NumberFormatException e) {
            return -1;
        }
    }

    private void start() {
        if (scheduledPartitionCounter == null) {
            scheduledPartitionCounter = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    int existingPartitions = countExistingPartitions();
                    existingPartitionCount.set(existingPartitions);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException e) {
                    log.error("Exception occurred when counting partitions", e);
                }
            }, 0, config.getInt(SCHEDULE_INTERVAL_SECONDS), TimeUnit.SECONDS);
        }
    }

    private int countExistingPartitions() throws InterruptedException, ExecutionException, TimeoutException {
        int timeout = config.getInt(TIMEOUT_SECONDS);
        List<String> topicNames = admin.listTopics()
                .listings()
                .get(timeout, TimeUnit.SECONDS)
                .stream()
                .map(TopicListing::name)
                .filter(name -> !name.startsWith(config.getString(PRIVATE_TOPIC_PREFIX))
                        && !"__consumer_offsets".equals(name) && !"__transaction_state".equals(name))
                .collect(Collectors.toList());

        return admin.describeTopics(topicNames)
                .all()
                .get(timeout, TimeUnit.SECONDS)
                .values()
                .stream()
                .map(description -> description.partitions().size())
                .reduce(0, Integer::sum);
    }
}
