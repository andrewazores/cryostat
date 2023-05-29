/*
 * Copyright The Cryostat Authors.
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
package io.cryostat.rules;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.script.ScriptException;

import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.configuration.CredentialsManager;
import io.cryostat.configuration.CredentialsManager.CredentialsEvent;
import io.cryostat.core.log.Logger;
import io.cryostat.core.net.Credentials;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.net.ConnectionDescriptor;
import io.cryostat.net.TargetConnectionManager;
import io.cryostat.platform.PlatformClient;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.TargetDiscoveryEvent;
import io.cryostat.recordings.RecordingArchiveHelper;
import io.cryostat.recordings.RecordingMetadataManager;
import io.cryostat.recordings.RecordingMetadataManager.Metadata;
import io.cryostat.recordings.RecordingOptionsBuilderFactory;
import io.cryostat.recordings.RecordingTargetHelper;
import io.cryostat.recordings.RecordingTargetHelper.ReplacementPolicy;
import io.cryostat.rules.RuleRegistry.RuleEvent;
import io.cryostat.util.events.Event;
import io.cryostat.util.events.EventListener;

import io.vertx.core.AbstractVerticle;
import org.apache.commons.lang3.tuple.Pair;

public class RuleProcessor extends AbstractVerticle implements Consumer<TargetDiscoveryEvent> {

    private final PlatformClient platformClient;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;
    private final RuleRegistry registry;
    private final CredentialsManager credentialsManager;
    private final RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    private final TargetConnectionManager targetConnectionManager;
    private final RecordingArchiveHelper recordingArchiveHelper;
    private final RecordingTargetHelper recordingTargetHelper;
    private final RecordingMetadataManager metadataManager;
    private final PeriodicArchiverFactory periodicArchiverFactory;
    private final Logger logger;

    private final Map<Pair<ServiceRef, Rule>, List<Future<?>>> tasks;

    RuleProcessor(
            PlatformClient platformClient,
            ScheduledExecutorService scheduler,
            ExecutorService executor,
            RuleRegistry registry,
            CredentialsManager credentialsManager,
            RecordingOptionsBuilderFactory recordingOptionsBuilderFactory,
            TargetConnectionManager targetConnectionManager,
            RecordingArchiveHelper recordingArchiveHelper,
            RecordingTargetHelper recordingTargetHelper,
            RecordingMetadataManager metadataManager,
            PeriodicArchiverFactory periodicArchiverFactory,
            Logger logger) {
        this.platformClient = platformClient;
        this.scheduler = scheduler;
        this.executor = executor;
        this.registry = registry;
        this.credentialsManager = credentialsManager;
        this.recordingOptionsBuilderFactory = recordingOptionsBuilderFactory;
        this.targetConnectionManager = targetConnectionManager;
        this.recordingArchiveHelper = recordingArchiveHelper;
        this.recordingTargetHelper = recordingTargetHelper;
        this.metadataManager = metadataManager;
        this.periodicArchiverFactory = periodicArchiverFactory;
        this.logger = logger;
        this.tasks = new HashMap<>();

        this.registry.addListener(this.ruleListener());
        this.credentialsManager.addListener(this.credentialsListener());
    }

    @Override
    public void start() {
        this.platformClient.addTargetDiscoveryListener(this);
    }

    @Override
    public void stop() {
        this.platformClient.removeTargetDiscoveryListener(this);
        this.tasks.forEach((ruleExecution, tasks) -> tasks.forEach(task -> task.cancel(true)));
        this.tasks.clear();
    }

    public EventListener<RuleRegistry.RuleEvent, Rule> ruleListener() {
        return new EventListener<RuleRegistry.RuleEvent, Rule>() {

            @Override
            public void onEvent(Event<RuleEvent, Rule> event) {
                switch (event.getEventType()) {
                    case ADDED:
                        if (event.getPayload().isEnabled()) {
                            applyRuleToTargets(event);
                        }
                        break;
                    case REMOVED:
                        deactivate(event.getPayload(), null);
                        break;
                    case UPDATED:
                        if (!event.getPayload().isEnabled()) {
                            deactivate(event.getPayload(), null);
                        } else {
                            applyRuleToTargets(event);
                        }
                        break;
                    default:
                        throw new UnsupportedOperationException(event.getEventType().toString());
                }
            }

            private void applyRuleToTargets(Event<RuleEvent, Rule> event) {
                executor.submit(
                        () ->
                                platformClient
                                        .listUniqueReachableServices()
                                        .forEach(RuleProcessor.this::activateAllRulesFor));
            }
        };
    }

    public EventListener<CredentialsManager.CredentialsEvent, String> credentialsListener() {
        return new EventListener<CredentialsManager.CredentialsEvent, String>() {

            @Override
            public void onEvent(Event<CredentialsEvent, String> event) {
                switch (event.getEventType()) {
                    case ADDED:
                        executor.submit(
                                () -> {
                                    credentialsManager
                                            .resolveMatchingTargets(event.getPayload())
                                            .forEach(RuleProcessor.this::activateAllRulesFor);
                                });
                        break;
                    case REMOVED:
                        break;
                    default:
                        throw new UnsupportedOperationException(event.getEventType().toString());
                }
            }
        };
    }

    @Override
    public synchronized void accept(TargetDiscoveryEvent tde) {
        switch (tde.getEventKind()) {
            case FOUND:
                activateAllRulesFor(tde.getServiceRef());
                break;
            case LOST:
                deactivate(null, tde.getServiceRef());
                break;
            case MODIFIED:
                break;
            default:
                throw new UnsupportedOperationException(tde.getEventKind().toString());
        }
    }

    private void activateAllRulesFor(ServiceRef serviceRef) {
        executor.submit(
                () -> {
                    registry.getRules(serviceRef).stream()
                            .filter(Rule::isEnabled)
                            .forEach(rule -> activate(rule, serviceRef));
                });
    }

    private void activate(Rule rule, ServiceRef serviceRef) {
        if (!rule.isEnabled()) {
            this.logger.trace(
                    "Activating rule {} for target {} aborted, rule is disabled {} ",
                    rule.getName(),
                    serviceRef.getServiceUri(),
                    rule.isEnabled());
            return;
        }
        if (tasks.containsKey(Pair.of(serviceRef, rule))) {
            this.logger.trace(
                    "Activating rule {} for target {} aborted, rule is already active",
                    rule.getName(),
                    serviceRef.getServiceUri());
            return;
        }
        this.logger.trace(
                "Activating rule {} for target {}", rule.getName(), serviceRef.getServiceUri());

        Credentials credentials = null;
        try {
            credentials = credentialsManager.getCredentials(serviceRef);
        } catch (ScriptException se) {
            logger.error(se);
            return;
        }
        if (rule.isArchiver()) {
            try {
                archiveRuleRecording(new ConnectionDescriptor(serviceRef, credentials), rule);
            } catch (Exception e) {
                logger.error(e);
            }
        } else {
            try {
                startRuleRecording(new ConnectionDescriptor(serviceRef, credentials), rule);
            } catch (Exception e) {
                logger.error(e);
            }

            PeriodicArchiver periodicArchiver =
                    periodicArchiverFactory.create(
                            serviceRef,
                            credentialsManager,
                            rule,
                            recordingArchiveHelper,
                            this::archivalFailureHandler);
            Pair<ServiceRef, Rule> key = Pair.of(serviceRef, rule);
            List<Future<?>> t = tasks.computeIfAbsent(key, k -> new ArrayList<>());
            long initialDelay = rule.getInitialDelaySeconds();
            long archivalPeriodSeconds = rule.getArchivalPeriodSeconds();
            if (initialDelay <= 0) {
                initialDelay = archivalPeriodSeconds;
            }
            if (rule.getPreservedArchives() <= 0 || archivalPeriodSeconds <= 0) {
                return;
            }
            t.add(
                    scheduler.scheduleAtFixedRate(
                            periodicArchiver::run,
                            initialDelay,
                            archivalPeriodSeconds,
                            TimeUnit.SECONDS));
        }
    }

    private void deactivate(Rule rule, ServiceRef serviceRef) {
        if (rule == null && serviceRef == null) {
            throw new IllegalArgumentException("Both parameters cannot be null");
        }
        if (rule != null) {
            logger.trace("Deactivating rule {}", rule.getName());
        }
        if (serviceRef != null) {
            logger.trace("Deactivating rules for {}", serviceRef.getServiceUri());
        }
        Iterator<Map.Entry<Pair<ServiceRef, Rule>, List<Future<?>>>> it =
                tasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Pair<ServiceRef, Rule>, List<Future<?>>> entry = it.next();
            boolean sameRule = Objects.equals(entry.getKey().getRight(), rule);
            boolean sameTarget = Objects.equals(entry.getKey().getLeft(), serviceRef);
            if (sameRule || sameTarget) {
                List<Future<?>> t = entry.getValue();
                t.forEach(f -> f.cancel(true));
                it.remove();
            }
        }
    }

    private Void archivalFailureHandler(Pair<ServiceRef, Rule> key) {
        Optional.ofNullable(tasks.get(key)).ifPresent(tasks -> tasks.forEach(f -> f.cancel(true)));
        tasks.remove(key);
        return null;
    }

    private void archiveRuleRecording(ConnectionDescriptor connectionDescriptor, Rule rule) {
        try {
            targetConnectionManager.executeConnectedTask(
                    connectionDescriptor,
                    connection -> {
                        IRecordingDescriptor descriptor =
                                connection.getService().getSnapshotRecording();
                        try {
                            recordingArchiveHelper
                                    .saveRecording(connectionDescriptor, descriptor.getName())
                                    .get();
                        } finally {
                            connection.getService().close(descriptor);
                        }

                        return null;
                    });
        } catch (Exception e) {
            logger.error(new RuleException(e));
        }
    }

    private void startRuleRecording(ConnectionDescriptor connectionDescriptor, Rule rule) {
        CompletableFuture<IRecordingDescriptor> future =
                targetConnectionManager.executeConnectedTaskAsync(
                        connectionDescriptor,
                        connection -> {
                            RecordingOptionsBuilder builder =
                                    recordingOptionsBuilderFactory
                                            .create(connection.getService())
                                            .name(rule.getRecordingName());
                            if (rule.getMaxAgeSeconds() > 0) {
                                builder = builder.maxAge(rule.getMaxAgeSeconds()).toDisk(true);
                            }
                            if (rule.getMaxSizeBytes() > 0) {
                                builder = builder.maxSize(rule.getMaxSizeBytes()).toDisk(true);
                            }
                            Pair<String, TemplateType> template =
                                    RecordingTargetHelper.parseEventSpecifierToTemplate(
                                            rule.getEventSpecifier());
                            return recordingTargetHelper.startRecording(
                                    ReplacementPolicy.ALWAYS,
                                    connectionDescriptor,
                                    builder.build(),
                                    template.getLeft(),
                                    template.getRight(),
                                    new Metadata(),
                                    false);
                        });
        try {
            future.handleAsync(
                            (recording, throwable) -> {
                                if (throwable != null) {
                                    logger.error(new RuleException(throwable));
                                    return null;
                                }
                                try {
                                    Map<String, String> labels =
                                            new HashMap<>(
                                                    metadataManager
                                                            .getMetadata(
                                                                    connectionDescriptor,
                                                                    recording.getName())
                                                            .getLabels());
                                    labels.put("rule", rule.getName());
                                    metadataManager.setRecordingMetadata(
                                            connectionDescriptor,
                                            recording.getName(),
                                            new Metadata(labels));
                                } catch (IOException ioe) {
                                    logger.error(ioe);
                                }
                                return null;
                            })
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error(new RuleException(e));
        }
    }
}
