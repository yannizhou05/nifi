/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.flow.synchronization;

import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.connectable.Connectable;
import org.apache.nifi.connectable.ConnectableType;
import org.apache.nifi.connectable.Connection;
import org.apache.nifi.connectable.Port;
import org.apache.nifi.controller.ComponentNode;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.ReloadComponent;
import org.apache.nifi.controller.ReportingTaskNode;
import org.apache.nifi.controller.flow.FlowManager;
import org.apache.nifi.controller.queue.FlowFileQueue;
import org.apache.nifi.controller.queue.LoadBalanceStrategy;
import org.apache.nifi.controller.service.ControllerServiceNode;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.controller.service.ControllerServiceReference;
import org.apache.nifi.controller.service.ControllerServiceState;
import org.apache.nifi.flow.Bundle;
import org.apache.nifi.flow.ComponentType;
import org.apache.nifi.flow.ConnectableComponent;
import org.apache.nifi.flow.ConnectableComponentType;
import org.apache.nifi.flow.Position;
import org.apache.nifi.flow.ScheduledState;
import org.apache.nifi.flow.VersionedComponent;
import org.apache.nifi.flow.VersionedConnection;
import org.apache.nifi.flow.VersionedControllerService;
import org.apache.nifi.flow.VersionedParameter;
import org.apache.nifi.flow.VersionedParameterContext;
import org.apache.nifi.flow.VersionedPort;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.groups.ComponentIdGenerator;
import org.apache.nifi.groups.ComponentScheduler;
import org.apache.nifi.groups.FlowSynchronizationOptions;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.groups.ScheduledStateChangeListener;
import org.apache.nifi.logging.LogLevel;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.parameter.Parameter;
import org.apache.nifi.parameter.ParameterContext;
import org.apache.nifi.parameter.ParameterContextManager;
import org.apache.nifi.parameter.ParameterDescriptor;
import org.apache.nifi.parameter.ParameterProviderConfiguration;
import org.apache.nifi.parameter.ParameterReferenceManager;
import org.apache.nifi.parameter.StandardParameterContext;
import org.apache.nifi.parameter.StandardParameterContextManager;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.registry.flow.FlowRegistryClient;
import org.apache.nifi.registry.flow.mapping.FlowMappingOptions;
import org.apache.nifi.scheduling.ExecutionNode;
import org.apache.nifi.scheduling.SchedulingStrategy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.or;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StandardVersionedComponentSynchronizerTest {

    private ProcessorNode processorA;
    private ProcessorNode processorB;
    private Connection connectionAB;
    private Port inputPort;
    private Port outputPort;
    private StandardVersionedComponentSynchronizer synchronizer;
    private FlowSynchronizationOptions synchronizationOptions;
    private ProcessGroup group;
    private ComponentScheduler componentScheduler;
    private ComponentIdGenerator componentIdGenerator;
    private ControllerServiceProvider controllerServiceProvider;
    private ParameterContextManager parameterContextManager;
    private ParameterReferenceManager parameterReferenceManager;
    private CapturingScheduledStateChangeListener scheduledStateChangeListener;

    private final Set<String> queuesWithData = Collections.synchronizedSet(new HashSet<>());
    private final Bundle bundle = new Bundle("group", "artifact", "version 1.0");

    @Before
    public void setup() {
        final ExtensionManager extensionManager = Mockito.mock(ExtensionManager.class);
        final FlowManager flowManager = Mockito.mock(FlowManager.class);
        controllerServiceProvider = Mockito.mock(ControllerServiceProvider.class);
        final Function<ProcessorNode, ProcessContext> processContextFactory = proc -> Mockito.mock(ProcessContext.class);
        final ReloadComponent reloadComponent = Mockito.mock(ReloadComponent.class);
        final FlowRegistryClient flowRegistryClient = Mockito.mock(FlowRegistryClient.class);
        componentIdGenerator = (proposed, instance, group) -> proposed == null ? instance : proposed;
        componentScheduler = Mockito.mock(ComponentScheduler.class);
        parameterContextManager = new StandardParameterContextManager();
        parameterReferenceManager = Mockito.mock(ParameterReferenceManager.class);

        when(flowManager.createControllerService(anyString(), anyString(), any(BundleCoordinate.class), anySet(), anyBoolean(), anyBoolean(), nullable(String.class)))
            .thenReturn(Mockito.mock(ControllerServiceNode.class));
        when(flowManager.getParameterContextManager()).thenReturn(parameterContextManager);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(flowManager).withParameterContextResolution(any(Runnable.class));
        doAnswer(invocation -> {
            final String id = invocation.getArgument(0, String.class);
            final String name = invocation.getArgument(1, String.class);
            final ParameterContext parameterContext = new StandardParameterContext.Builder()
                    .id(id)
                    .name(name)
                    .parameterReferenceManager(parameterReferenceManager)
                    .build();

            final Map<String, Parameter> parameterMap = invocation.getArgument(2, Map.class);
            parameterContext.setParameters(parameterMap);

            final List<String> inheritedContextIds = invocation.getArgument(3, List.class);
            final List<ParameterContext> inheritedContexts = inheritedContextIds.stream()
                .map(parameterContextManager::getParameterContext)
                .collect(Collectors.toList());
            parameterContext.setInheritedParameterContexts(inheritedContexts);

            parameterContextManager.addParameterContext(parameterContext);

            return parameterContext;
        }).when(flowManager).createParameterContext(anyString(), anyString(), anyMap(), anyList(), or(any(ParameterProviderConfiguration.class), isNull()));

        final VersionedFlowSynchronizationContext context = new VersionedFlowSynchronizationContext.Builder()
            .componentIdGenerator(componentIdGenerator)
            .componentScheduler(componentScheduler)
            .extensionManager(extensionManager)
            .flowManager(flowManager)
            .controllerServiceProvider(controllerServiceProvider)
            .flowMappingOptions(FlowMappingOptions.DEFAULT_OPTIONS)
            .processContextFactory(processContextFactory)
            .reloadComponent(reloadComponent)
            .flowRegistryClient(flowRegistryClient)
            .build();

        group = Mockito.mock(ProcessGroup.class);

        processorA = createMockProcessor();
        processorB = createMockProcessor();
        inputPort = createMockPort(ConnectableType.INPUT_PORT);
        outputPort = createMockPort(ConnectableType.OUTPUT_PORT);
        connectionAB = createMockConnection(processorA, processorB, group);

        when(group.getProcessors()).thenReturn(Arrays.asList(processorA, processorB));
        when(group.getInputPorts()).thenReturn(Collections.singleton(inputPort));
        when(group.getOutputPorts()).thenReturn(Collections.singleton(outputPort));

        scheduledStateChangeListener = new CapturingScheduledStateChangeListener();

        synchronizationOptions = new FlowSynchronizationOptions.Builder()
            .componentIdGenerator(componentIdGenerator)
            .componentComparisonIdLookup(VersionedComponent::getIdentifier)
            .componentScheduler(componentScheduler)
            .scheduledStateChangeListener(scheduledStateChangeListener)
            .build();

        synchronizer = new StandardVersionedComponentSynchronizer(context);

        queuesWithData.clear();
    }

    private FlowSynchronizationOptions createQuickFailSynchronizationOptions(final FlowSynchronizationOptions.ComponentStopTimeoutAction timeoutAction) {
        return new FlowSynchronizationOptions.Builder()
            .componentIdGenerator(componentIdGenerator)
            .componentComparisonIdLookup(VersionedComponent::getIdentifier)
            .componentScheduler(componentScheduler)
            .scheduledStateChangeListener(scheduledStateChangeListener)
            .componentStopTimeout(Duration.ofMillis(10))
            .componentStopTimeoutAction(timeoutAction)
            .build();
    }

    private ProcessorNode createMockProcessor() {
        final String uuid = UUID.randomUUID().toString();

        final ProcessorNode processor = Mockito.mock(ProcessorNode.class);
        instrumentComponentNodeMethods(uuid, processor);
        when(processor.isRunning()).thenReturn(false);
        when(processor.getProcessGroup()).thenReturn(group);
        when(processor.getConnectableType()).thenReturn(ConnectableType.PROCESSOR);
        when(processor.getScheduledState()).thenReturn(org.apache.nifi.controller.ScheduledState.STOPPED);

        return processor;
    }

    private ControllerServiceNode createMockControllerService() {
        final String uuid = UUID.randomUUID().toString();

        final ControllerServiceNode service = Mockito.mock(ControllerServiceNode.class);
        instrumentComponentNodeMethods(uuid, service);

        when(service.isActive()).thenReturn(false);
        when(service.getProcessGroup()).thenReturn(group);
        when(service.getState()).thenReturn(ControllerServiceState.DISABLED);

        return service;
    }

    private void instrumentComponentNodeMethods(final String uuid, final ComponentNode component) {
        when(component.getIdentifier()).thenReturn(uuid);
        when(component.getProperties()).thenReturn(Collections.emptyMap());
        when(component.getPropertyDescriptor(anyString())).thenAnswer(invocation -> {
            return new PropertyDescriptor.Builder()
                .name(invocation.getArgument(0, String.class))
                .build();
        });
        when(component.getBundleCoordinate()).thenReturn(new BundleCoordinate("group", "artifact", "version 1.0"));
    }

    private Port createMockPort(final ConnectableType connectableType) {
        final String uuid = UUID.randomUUID().toString();

        final Port port = Mockito.mock(Port.class);
        when(port.getIdentifier()).thenReturn(uuid);
        when(port.isRunning()).thenReturn(false);
        when(port.getProcessGroup()).thenReturn(group);
        when(port.getConnectableType()).thenReturn(connectableType);

        return port;
    }


    private Connection createMockConnection(final Connectable source, final Connectable destination, final ProcessGroup group) {
        final String uuid = UUID.randomUUID().toString();

        final FlowFileQueue flowFileQueue = Mockito.mock(FlowFileQueue.class);
        when(flowFileQueue.getIdentifier()).thenReturn(uuid);
        when(flowFileQueue.isEmpty()).thenAnswer(invocation -> !queuesWithData.contains(uuid));

        final Connection connection = Mockito.mock(Connection.class);
        when(connection.getIdentifier()).thenReturn(uuid);
        when(connection.getSource()).thenReturn(source);
        when(connection.getDestination()).thenReturn(destination);
        when(connection.getFlowFileQueue()).thenReturn(flowFileQueue);
        when(connection.getProcessGroup()).thenReturn(group);

        // Update the source's connections
        final Set<Connection> outgoing = source.getConnections();
        final Set<Connection> updatedOutgoing = outgoing == null ? new HashSet<>() : new HashSet<>(outgoing);
        updatedOutgoing.add(connection);
        when(source.getConnections()).thenReturn(updatedOutgoing);

        // Update the destination's incoming connections
        final List<Connection> incoming = destination.getIncomingConnections();
        final List<Connection> updatedIncoming = incoming == null ? new ArrayList<>() : new ArrayList<>(incoming);
        updatedIncoming.add(connection);
        when(destination.getIncomingConnections()).thenReturn(updatedIncoming);

        // Update group to return the connection
        final Set<Connection> currentConnections = group.getConnections();
        final Set<Connection> updatedConnections = currentConnections == null ? new HashSet<>() : new HashSet<>(currentConnections);
        updatedConnections.add(connection);
        when(group.getConnections()).thenReturn(updatedConnections);

        return connection;
    }

    @Test
    public void testSynchronizeStoppedProcessor() throws FlowSynchronizationException, TimeoutException, InterruptedException {
        final VersionedProcessor versionedProcessor = createMinimalVersionedProcessor();
        synchronizer.synchronize(processorA, versionedProcessor, group, synchronizationOptions);

        // Ensure that the processor was updated as expected.
        verify(processorA).setProperties(versionedProcessor.getProperties(), true, Collections.emptySet());
        verify(processorA).setName(versionedProcessor.getName());
        verify(componentScheduler, times(0)).startComponent(any(Connectable.class));
    }

    @Test
    public void testSynchronizationStartsProcessor() throws FlowSynchronizationException, TimeoutException, InterruptedException {
        final VersionedProcessor versionedProcessor = createMinimalVersionedProcessor();
        versionedProcessor.setScheduledState(ScheduledState.RUNNING);

        synchronizer.synchronize(processorA, versionedProcessor, group, synchronizationOptions);
        verify(componentScheduler, times(1)).transitionComponentState(any(Connectable.class), eq(ScheduledState.RUNNING));
    }

    @Test
    public void testRunningProcessorRestarted() throws FlowSynchronizationException, TimeoutException, InterruptedException {
        final VersionedProcessor versionedProcessor = createMinimalVersionedProcessor();
        versionedProcessor.setScheduledState(ScheduledState.RUNNING);

        when(processorA.isRunning()).thenReturn(true);
        when(group.stopProcessor(processorA)).thenReturn(CompletableFuture.completedFuture(null));

        synchronizer.synchronize(processorA, versionedProcessor, group, synchronizationOptions);

        verify(group, times(1)).stopProcessor(processorA);
        verify(processorA).setProperties(versionedProcessor.getProperties(), true, Collections.emptySet());
        verify(componentScheduler, atLeast(1)).startComponent(any(Connectable.class));
    }

    @Test
    public void testTimeoutWhenProcessorDoesNotStop() {
        final VersionedProcessor versionedProcessor = createMinimalVersionedProcessor();
        versionedProcessor.setScheduledState(ScheduledState.RUNNING);
        startProcessor(processorA, false);

        synchronizationOptions = createQuickFailSynchronizationOptions(FlowSynchronizationOptions.ComponentStopTimeoutAction.THROW_TIMEOUT_EXCEPTION);

        assertThrows(TimeoutException.class, () -> {
            synchronizer.synchronize(processorA, versionedProcessor, group, synchronizationOptions);
        });

        verifyStopped(processorA);
        verifyNotRestarted(processorA);
        verify(processorA, times(0)).terminate();
        verify(processorA, times(0)).setProperties(eq(versionedProcessor.getProperties()), anyBoolean(), anySet());
        verify(processorA, times(0)).setName(versionedProcessor.getName());
    }

    @Test
    public void testTerminateWhenProcessorDoesNotStop() throws FlowSynchronizationException, TimeoutException, InterruptedException {
        final VersionedProcessor versionedProcessor = createMinimalVersionedProcessor();
        versionedProcessor.setScheduledState(ScheduledState.RUNNING);
        startProcessor(processorA, false);

        synchronizationOptions = createQuickFailSynchronizationOptions(FlowSynchronizationOptions.ComponentStopTimeoutAction.TERMINATE);
        synchronizer.synchronize(processorA, versionedProcessor, group, synchronizationOptions);

        verifyStopped(processorA);
        verifyRestarted(processorA);
        verify(processorA, times(1)).terminate();
        verify(processorA, times(1)).setProperties(versionedProcessor.getProperties(), true, Collections.emptySet());
        verify(processorA, times(1)).setName(versionedProcessor.getName());
    }

    @Test
    public void testUpdateConnectionWithSourceDestStopped() throws FlowSynchronizationException, TimeoutException {
        final VersionedConnection versionedConnection = createMinimalVersionedConnection(processorA, processorB);
        versionedConnection.setName("Hello");

        synchronizer.synchronize(connectionAB, versionedConnection, group, synchronizationOptions);

        verify(connectionAB, times(1)).setName("Hello");
        verify(connectionAB, times(1)).setRelationships(Collections.singleton(new Relationship.Builder().name("success").build()));

        scheduledStateChangeListener.assertNumProcessorUpdates(0);
    }

    @Test
    public void testUpdateConnectionStopsSource() throws FlowSynchronizationException, TimeoutException {
        startProcessor(processorA);

        final VersionedConnection versionedConnection = createMinimalVersionedConnection(processorA, processorB);
        versionedConnection.setName("Hello");

        synchronizer.synchronize(connectionAB, versionedConnection, group, synchronizationOptions);

        // Ensure that the update occurred
        verify(connectionAB, times(1)).setName("Hello");

        // Ensure that the source was stopped and restarted
        verifyStopped(processorA);
        verifyRestarted(processorA);

        verifyCallbackIndicatedRestart(processorA);
    }

    @Test
    public void testSourceTerminatedIfNotStopped() throws FlowSynchronizationException, TimeoutException {
        startProcessor(processorA, false);

        final VersionedConnection versionedConnection = createMinimalVersionedConnection(processorA, processorB);
        versionedConnection.setName("Hello");

        synchronizationOptions = createQuickFailSynchronizationOptions(FlowSynchronizationOptions.ComponentStopTimeoutAction.TERMINATE);
        synchronizer.synchronize(connectionAB, versionedConnection, group, synchronizationOptions);

        // Ensure that we terminate the source
        verify(processorA, times(1)).terminate();

        // Ensure that the update occurred
        verify(connectionAB, times(1)).setName("Hello");

        // Ensure that the source was stopped and restarted
        verifyStopped(processorA);
        verifyRestarted(processorA);

        verifyCallbackIndicatedRestart(processorA);
    }

    @Test
    public void testTimeoutStoppingSource() {
        startProcessor(processorA, false);

        final VersionedConnection versionedConnection = createMinimalVersionedConnection(processorA, processorB);
        versionedConnection.setName("Hello");

        synchronizationOptions = createQuickFailSynchronizationOptions(FlowSynchronizationOptions.ComponentStopTimeoutAction.THROW_TIMEOUT_EXCEPTION);

        assertThrows(TimeoutException.class, () -> {
            synchronizer.synchronize(connectionAB, versionedConnection, group, synchronizationOptions);
        });

        // Ensure that we terminate the source
        verify(processorA, times(0)).terminate();

        // Ensure that the update occurred
        verify(connectionAB, times(0)).setName("Hello");

        // Ensure that the source was stopped and restarted
        verifyStopped(processorA);
        verifyNotRestarted(processorA);
        verifyCallbackIndicatedStopOnly(processorA);
    }

    private void verifyCallbackIndicatedRestart(final ProcessorNode... processors) {
        for (final ProcessorNode processor : processors) {
            scheduledStateChangeListener.assertProcessorUpdates(new ScheduledStateUpdate<>(processor, org.apache.nifi.controller.ScheduledState.STOPPED),
                    new ScheduledStateUpdate<>(processor, org.apache.nifi.controller.ScheduledState.RUNNING));
        }
        scheduledStateChangeListener.assertNumProcessorUpdates(processors.length * 2);
    }

    private void verifyCallbackIndicatedStopOnly(final ProcessorNode... processors) {
        for (final ProcessorNode processor : processors) {
            scheduledStateChangeListener.assertProcessorUpdates(new ScheduledStateUpdate<>(processor, org.apache.nifi.controller.ScheduledState.STOPPED));
        }
        scheduledStateChangeListener.assertNumProcessorUpdates(processors.length);
    }

    private void verifyCallbackIndicatedStartOnly(final ProcessorNode... processors) {
        for (final ProcessorNode processor : processors) {
            scheduledStateChangeListener.assertProcessorUpdates(new ScheduledStateUpdate<>(processor, org.apache.nifi.controller.ScheduledState.RUNNING));
        }
        scheduledStateChangeListener.assertNumProcessorUpdates(processors.length);
    }

    @Test
    public void testConnectionRemoval() throws FlowSynchronizationException, TimeoutException {
        startProcessor(processorA);

        synchronizer.synchronize(connectionAB, null, group, synchronizationOptions);

        // Ensure that the source was stopped and restarted
        verifyStopped(processorA);
        verifyNotRestarted(processorA);
        verify(group).removeConnection(connectionAB);
        verifyCallbackIndicatedStopOnly(processorA);
    }

    @Test
    public void testFailsIfDestinationStoppedQueueNotEmpty() {
        startProcessor(processorA);
        queuesWithData.add(connectionAB.getIdentifier());

        synchronizationOptions = createQuickFailSynchronizationOptions(FlowSynchronizationOptions.ComponentStopTimeoutAction.THROW_TIMEOUT_EXCEPTION);

        assertThrows(FlowSynchronizationException.class, () -> {
            synchronizer.synchronize(connectionAB, null, group, synchronizationOptions);
        });

        // Ensure that the update occurred
        verify(connectionAB, times(0)).setName("Hello");

        // Ensure that the source was stopped but not restarted. We don't restart in this situation because the intent is to drop
        // the connection so we will leave the source stopped so that the data can eventually drain from the queue and the connection
        // can be removed.
        verifyStopped(processorA);
        verifyNotRestarted(processorA);
        verifyCallbackIndicatedStopOnly(processorA);
    }

    @Test
    public void testWaitForQueueToEmpty() throws InterruptedException {
        startProcessor(processorA);
        startProcessor(processorB);
        queuesWithData.add(connectionAB.getIdentifier());

        // Use a background thread to synchronize the connection.
        final CountDownLatch completionLatch = new CountDownLatch(1);
        final Thread syncThread = new Thread(() -> {
            try {
                synchronizer.synchronize(connectionAB, null, group, synchronizationOptions);
                completionLatch.countDown();
            } catch (final Exception e) {
                Assert.fail(e.toString());
            }
        });
        syncThread.start();

        // Wait up to 1/2 second to ensure that the task does not complete.
        final boolean completed = completionLatch.await(500, TimeUnit.MILLISECONDS);
        assertFalse(completed);

        // Clear the queue's data.
        queuesWithData.clear();

        // The task should now complete quickly. Give up to 5 seconds in case this is run in a slow environment.
        assertTrue(completionLatch.await(5, TimeUnit.SECONDS));

        // Ensure that the update occurred
        verify(connectionAB, times(0)).setName("Hello");

        // Ensure that the source was stopped, destination was stopped, and the connection was removed.
        verifyStopped(processorA);
        verifyNotRestarted(processorA);
        verifyCallbackIndicatedStopOnly(processorB, processorA);
        verifyStopped(processorB);
        verifyNotRestarted(processorB);
        verify(group, times(1)).removeConnection(connectionAB);
    }

    @Test
    public void testPortUpdatedWhenStopped() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final VersionedPort versionedInputPort = createMinimalVersionedPort(ComponentType.INPUT_PORT);
        synchronizer.synchronize(inputPort, versionedInputPort, group, synchronizationOptions);

        verifyNotRestarted(inputPort);
        verify(inputPort).setName("Input");

        final VersionedPort versionedOutputPort = createMinimalVersionedPort(ComponentType.OUTPUT_PORT);
        synchronizer.synchronize(outputPort, versionedOutputPort, group, synchronizationOptions);

        verifyNotRestarted(outputPort);
        verify(outputPort).setName("Output");
    }

    @Test
    public void testPortStarted() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final VersionedPort versionedInputPort = createMinimalVersionedPort(ComponentType.INPUT_PORT);
        versionedInputPort.setScheduledState(ScheduledState.RUNNING);
        synchronizer.synchronize(inputPort, versionedInputPort, group, synchronizationOptions);

        verify(componentScheduler, atLeast(1)).transitionComponentState(inputPort, ScheduledState.RUNNING);
        verify(inputPort).setName("Input");
    }

    @Test
    public void testPortRestarted() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final VersionedPort versionedInputPort = createMinimalVersionedPort(ComponentType.INPUT_PORT);
        versionedInputPort.setScheduledState(ScheduledState.RUNNING);
        synchronizer.synchronize(inputPort, versionedInputPort, group, synchronizationOptions);

        verify(componentScheduler, atLeast(1)).transitionComponentState(inputPort, ScheduledState.RUNNING);
        verify(inputPort).setName("Input");
    }

    @Test
    public void testRemoveOutputPortFailsIfIncomingConnection() {
        createMockConnection(processorA, outputPort, group);

        assertThrows(FlowSynchronizationException.class, () -> {
            synchronizer.synchronize(outputPort, null, group, synchronizationOptions);
        });
    }

    @Test
    public void testRemoveInputPortFailsIfOutgoingConnectionNotEmpty() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final Connection connection = createMockConnection(inputPort, processorA, group);
        synchronizationOptions = createQuickFailSynchronizationOptions(FlowSynchronizationOptions.ComponentStopTimeoutAction.THROW_TIMEOUT_EXCEPTION);

        // Synchronize should succeed because connection doesn't have data.
        synchronizer.synchronize(inputPort, null, group, synchronizationOptions);

        // Now give it data
        queuesWithData.add(connection.getIdentifier());

        // Ensure that we fail to remove it due to FlowSynchronizationException because destination of connection is not running
        assertThrows(FlowSynchronizationException.class, () -> {
            synchronizer.synchronize(inputPort, null, group, synchronizationOptions);
        });

        // Start processor and ensure that we fail to remove it due to TimeoutException because destination of connection is now running
        startProcessor(processorA);
        assertThrows(TimeoutException.class, () -> {
            synchronizer.synchronize(inputPort, null, group, synchronizationOptions);
        });
    }


    @Test
    public void testAddsControllerService() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final VersionedControllerService versionedService = createMinimalVersionedControllerService();
        synchronizer.synchronize(null, versionedService, group, synchronizationOptions);

        verify(group).addControllerService(any(ControllerServiceNode.class));
    }

    @Test
    public void testControllerServiceRemoved() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final ControllerServiceNode service = createMockControllerService();
        when(service.isActive()).thenReturn(true);
        when(service.getState()).thenReturn(ControllerServiceState.ENABLED);
        when(service.getReferences()).thenReturn(Mockito.mock(ControllerServiceReference.class));

        when(controllerServiceProvider.unscheduleReferencingComponents(service)).thenReturn(Collections.emptyMap());
        when(controllerServiceProvider.disableControllerServicesAsync(anyCollection())).thenReturn(CompletableFuture.completedFuture(null));

        synchronizer.synchronize(service, null, group, synchronizationOptions);

        verify(controllerServiceProvider).unscheduleReferencingComponents(service);
        verify(controllerServiceProvider).disableControllerServicesAsync(Collections.singleton(service));
        verify(controllerServiceProvider).removeControllerService(service);
    }

    @Test
    public void testReferencesStoppedAndRestarted() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final ControllerServiceNode service = createMockControllerService();
        when(service.isActive()).thenReturn(true);
        when(service.getState()).thenReturn(ControllerServiceState.ENABLED);

        // Make Processors A and B reference the controller service and start them
        setReferences(service, processorA, processorB);
        startProcessor(processorB);

        when(controllerServiceProvider.unscheduleReferencingComponents(service)).thenReturn(Collections.singletonMap(processorB, CompletableFuture.completedFuture(null)));
        when(controllerServiceProvider.disableControllerServicesAsync(anyCollection())).thenReturn(CompletableFuture.completedFuture(null));

        final VersionedControllerService versionedControllerService = createMinimalVersionedControllerService();
        versionedControllerService.setName("Hello");
        versionedControllerService.setScheduledState(ScheduledState.RUNNING);

        synchronizer.synchronize(service, versionedControllerService, group, synchronizationOptions);

        verify(controllerServiceProvider).unscheduleReferencingComponents(service);
        verify(controllerServiceProvider).disableControllerServicesAsync(Collections.singleton(service));
        verify(controllerServiceProvider).enableControllerServicesAsync(Collections.singleton(service));
        verify(controllerServiceProvider).scheduleReferencingComponents(service, Collections.singleton(processorB), componentScheduler);
        verify(service).setName("Hello");
    }

    @Test
    public void testReferencesNotRestartedWhenServiceStopped() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final ControllerServiceNode service = createMockControllerService();
        when(service.isActive()).thenReturn(true);
        when(service.getState()).thenReturn(ControllerServiceState.ENABLED);

        // Make Processors A and B reference the controller service and start them
        setReferences(service, processorA, processorB);
        startProcessor(processorB);

        when(controllerServiceProvider.unscheduleReferencingComponents(service)).thenReturn(Collections.singletonMap(processorB, CompletableFuture.completedFuture(null)));
        when(controllerServiceProvider.disableControllerServicesAsync(anyCollection())).thenReturn(CompletableFuture.completedFuture(null));

        final VersionedControllerService versionedControllerService = createMinimalVersionedControllerService();
        versionedControllerService.setName("Hello");
        versionedControllerService.setScheduledState(ScheduledState.DISABLED);

        synchronizer.synchronize(service, versionedControllerService, group, synchronizationOptions);

        verify(controllerServiceProvider).unscheduleReferencingComponents(service);
        verify(controllerServiceProvider).disableControllerServicesAsync(Collections.singleton(service));

        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(final InvocationOnMock invocationOnMock) {
                final Set<?> services = invocationOnMock.getArgument(0);
                assertTrue(services.isEmpty());
                return null;
            }
        }).when(controllerServiceProvider).enableControllerServicesAsync(Mockito.anySet());

        verify(controllerServiceProvider, times(0)).scheduleReferencingComponents(Mockito.any(ControllerServiceNode.class), Mockito.anySet(), Mockito.any(ComponentScheduler.class));
    }

    @Test
    public void testTerminateReferenceOnTimeout() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final ControllerServiceNode service = createMockControllerService();
        when(service.isActive()).thenReturn(true);
        when(service.getState()).thenReturn(ControllerServiceState.ENABLED);

        // Make Processors A and B reference the controller service and start them
        setReferences(service, processorA, processorB);
        startProcessor(processorB, false);

        synchronizationOptions = createQuickFailSynchronizationOptions(FlowSynchronizationOptions.ComponentStopTimeoutAction.TERMINATE);

        // When unscheduleReferencingComponents is called, return a Future that will never complete.
        when(controllerServiceProvider.unscheduleReferencingComponents(service)).thenReturn(Collections.singletonMap(processorB, new CompletableFuture<>()));
        when(controllerServiceProvider.disableControllerServicesAsync(anyCollection())).thenReturn(CompletableFuture.completedFuture(null));

        final VersionedControllerService versionedControllerService = createMinimalVersionedControllerService();
        versionedControllerService.setName("Hello");
        versionedControllerService.setScheduledState(ScheduledState.RUNNING);

        synchronizer.synchronize(service, versionedControllerService, group, synchronizationOptions);

        verify(controllerServiceProvider).unscheduleReferencingComponents(service);
        verify(controllerServiceProvider).disableControllerServicesAsync(Collections.singleton(service));
        verify(processorB).terminate();
        verify(processorA, times(0)).terminate();
        verify(controllerServiceProvider).enableControllerServicesAsync(Collections.singleton(service));
        verify(controllerServiceProvider).scheduleReferencingComponents(service, Collections.singleton(processorB), componentScheduler);
        verify(service).setName("Hello");
    }


    @Test
    public void testCreatingParameterContext() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        final Map<String, String> parameterMap = new HashMap<>();
        parameterMap.put("abc", "xyz");
        parameterMap.put("secret", "yes");
        final VersionedParameterContext proposed = createVersionedParameterContext("Context 1", parameterMap, Collections.singleton("secret"));

        synchronizer.synchronize(null, proposed, synchronizationOptions);

        final Set<ParameterContext> contexts = parameterContextManager.getParameterContexts();
        assertEquals(1, contexts.size());

        final ParameterContext created = contexts.iterator().next();
        assertEquals(created.getName(), proposed.getName());

        final Map<ParameterDescriptor, Parameter> createdParameters = created.getParameters();
        assertEquals(2, createdParameters.size());

        final Parameter abc = created.getParameter("abc").get();
        assertEquals("abc", abc.getDescriptor().getName());
        assertFalse(abc.getDescriptor().isSensitive());
        assertEquals("xyz", abc.getValue());

        final Parameter secret = created.getParameter("secret").get();
        assertEquals("secret", secret.getDescriptor().getName());
        assertTrue(secret.getDescriptor().isSensitive());
        assertEquals("yes", secret.getValue());
    }

    @Test
    public void testUpdateParametersNoReferences() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        // Create the initial context
        testCreatingParameterContext();

        final ParameterContext existing = parameterContextManager.getParameterContexts().iterator().next();

        final Map<String, String> parameterMap = new HashMap<>();
        parameterMap.put("abc", "123");
        parameterMap.put("secret", "maybe");

        final VersionedParameterContext proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));

        synchronizer.synchronize(existing, proposed, synchronizationOptions);

        assertEquals("123", existing.getParameter("abc").get().getValue());
        assertEquals("maybe", existing.getParameter("secret").get().getValue());
        assertEquals("Context 2", existing.getName());
    }

    @Test
    public void testUpdateParametersReferenceProcessorNotStopping() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        // Create the initial context
        testCreatingParameterContext();

        final ParameterContext existing = parameterContextManager.getParameterContexts().iterator().next();

        final Map<String, String> parameterMap = new HashMap<>();
        parameterMap.put("abc", "123");
        parameterMap.put("secret", "maybe");

        final VersionedParameterContext proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));

        final ProcessorNode processorA = createMockProcessor();
        startProcessor(processorA, false);
        synchronizationOptions = createQuickFailSynchronizationOptions(FlowSynchronizationOptions.ComponentStopTimeoutAction.THROW_TIMEOUT_EXCEPTION);
        when(parameterReferenceManager.getProcessorsReferencing(existing, "abc")).thenReturn(Collections.singleton(processorA));

        assertThrows(TimeoutException.class, () -> {
            synchronizer.synchronize(existing, proposed, synchronizationOptions);
        });

        // Updates should not occur.
        assertEquals("xyz", existing.getParameter("abc").get().getValue());
        assertEquals("yes", existing.getParameter("secret").get().getValue());
        assertEquals("Context 1", existing.getName());
    }

    @Test
    public void testUpdateParametersReferenceStopping() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        // Create the initial context
        testCreatingParameterContext();

        final ParameterContext existing = parameterContextManager.getParameterContexts().iterator().next();

        final Map<String, String> parameterMap = new HashMap<>();
        parameterMap.put("abc", "123");
        parameterMap.put("secret", "maybe");

        final VersionedParameterContext proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));

        final ProcessorNode processorA = createMockProcessor();
        startProcessor(processorA, true);

        final ProcessorNode processorB = createMockProcessor();

        final AtomicBoolean serviceActive = new AtomicBoolean(true);

        final ControllerServiceNode service = createMockControllerService();
        when(service.isActive()).thenAnswer(invocation -> serviceActive.get());
        when(service.getState()).thenAnswer(invocation -> serviceActive.get() ? ControllerServiceState.ENABLED : ControllerServiceState.DISABLED);

        // Make Processors A and B reference the controller service and start them
        setReferences(service, processorA, processorB);
        startProcessor(processorB);

        when(controllerServiceProvider.unscheduleReferencingComponents(service)).thenReturn(Collections.singletonMap(processorB, CompletableFuture.completedFuture(null)));
        when(controllerServiceProvider.disableControllerServicesAsync(anyCollection())).thenAnswer(invocation -> {
            serviceActive.set(false);
            return CompletableFuture.completedFuture(null);
        });

        when(parameterReferenceManager.getProcessorsReferencing(existing, "abc")).thenReturn(Collections.emptySet());
        when(parameterReferenceManager.getControllerServicesReferencing(existing, "abc")).thenReturn(Collections.singleton(service));

        synchronizer.synchronize(existing, proposed, synchronizationOptions);

        // Updates should occur.
        assertEquals("123", existing.getParameter("abc").get().getValue());
        assertEquals("maybe", existing.getParameter("secret").get().getValue());
        assertEquals("Context 2", existing.getName());

        // Verify controller service/reference lifecycles
        verify(controllerServiceProvider).unscheduleReferencingComponents(service);
        verify(controllerServiceProvider).disableControllerServicesAsync(Collections.singleton(service));
        verify(controllerServiceProvider).enableControllerServicesAsync(Collections.singleton(service));
        verify(componentScheduler).startComponent(processorB);
    }

    @Test
    public void testUpdateParametersControllerServiceNotDisabling() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        // Create the initial context
        testCreatingParameterContext();

        final ParameterContext existing = parameterContextManager.getParameterContexts().iterator().next();

        final Map<String, String> parameterMap = new HashMap<>();
        parameterMap.put("abc", "123");
        parameterMap.put("secret", "maybe");

        final VersionedParameterContext proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));

        final ProcessorNode processorA = createMockProcessor();
        final ProcessorNode processorB = createMockProcessor();

        final ControllerServiceNode service = createMockControllerService();
        when(service.isActive()).thenReturn(true);
        when(service.getState()).thenReturn(ControllerServiceState.ENABLED);

        // Make Processors A and B reference the controller service and start them
        setReferences(service, processorA, processorB);
        startProcessor(processorA, true);
        startProcessor(processorB);

        final Map<ComponentNode, Future<Void>> completedFutureMap = new HashMap<>();
        completedFutureMap.put(processorA, CompletableFuture.completedFuture(null));
        completedFutureMap.put(processorB, CompletableFuture.completedFuture(null));

        when(controllerServiceProvider.unscheduleReferencingComponents(service)).thenReturn(completedFutureMap);
        when(controllerServiceProvider.disableControllerServicesAsync(anyCollection())).thenReturn(new CompletableFuture<>()); // Never complete future = never disable service

        synchronizationOptions = createQuickFailSynchronizationOptions(FlowSynchronizationOptions.ComponentStopTimeoutAction.TERMINATE);

        when(parameterReferenceManager.getProcessorsReferencing(existing, "abc")).thenReturn(Collections.emptySet());
        when(parameterReferenceManager.getControllerServicesReferencing(existing, "abc")).thenReturn(Collections.singleton(service));

        assertThrows(TimeoutException.class, () -> {
            synchronizer.synchronize(existing, proposed, synchronizationOptions);
        });

        // Updates should not occur.
        assertEquals("xyz", existing.getParameter("abc").get().getValue());
        assertEquals("yes", existing.getParameter("secret").get().getValue());
        assertEquals("Context 1", existing.getName());

        // Verify controller service/reference lifecycles
        verify(controllerServiceProvider).unscheduleReferencingComponents(service);
        verify(controllerServiceProvider).disableControllerServicesAsync(Collections.singleton(service));
        verify(controllerServiceProvider, times(0)).enableControllerServicesAsync(Collections.singleton(service));
        verify(componentScheduler).startComponent(processorA);
        verify(componentScheduler).startComponent(processorB);
    }

    @Test
    public void testGetUpdatedParameterNames() throws FlowSynchronizationException, InterruptedException, TimeoutException {
        testCreatingParameterContext();
        final ParameterContext existing = parameterContextManager.getParameterContexts().iterator().next();

        final Map<String, String> originalParams = new HashMap<>();
        originalParams.put("abc", "xyz");
        originalParams.put("secret", "yes");

        // Test no changes
        Map<String, String> parameterMap = new HashMap<>(originalParams);
        VersionedParameterContext proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));
        assertEquals(Collections.emptySet(), synchronizer.getUpdatedParameterNames(existing, proposed));

        // Test non-sensitive param change
        parameterMap = new HashMap<>(originalParams);
        parameterMap.put("abc", "hello");
        proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));
        assertEquals(Collections.singleton("abc"), synchronizer.getUpdatedParameterNames(existing, proposed));

        // Test sensitive param change
        parameterMap = new HashMap<>(originalParams);
        parameterMap.put("secret", "secret");
        proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));
        assertEquals(Collections.singleton("secret"), synchronizer.getUpdatedParameterNames(existing, proposed));

        // Test removed parameters
        parameterMap.clear();
        proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));
        assertEquals(new HashSet<>(Arrays.asList("abc", "secret")), synchronizer.getUpdatedParameterNames(existing, proposed));

        // Test added parameter
        parameterMap = new HashMap<>(originalParams);
        parameterMap.put("Added", "Added");
        proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));
        assertEquals(Collections.singleton("Added"), synchronizer.getUpdatedParameterNames(existing, proposed));

        // Test added, removed, and updated parameters
        parameterMap = new HashMap<>(originalParams);
        parameterMap.put("Added", "Added");
        parameterMap.put("Added 2", "Added");
        parameterMap.remove("secret");
        parameterMap.put("abc", "hello");
        proposed = createVersionedParameterContext("Context 2", parameterMap, Collections.singleton("secret"));
        assertEquals(new HashSet<>(Arrays.asList("abc", "secret", "Added", "Added 2")), synchronizer.getUpdatedParameterNames(existing, proposed));
    }


    private VersionedParameterContext createVersionedParameterContext(final String name, final Map<String, String> parameters, final Set<String> sensitiveParamNames) {
        final Set<VersionedParameter> versionedParameters = new HashSet<>();
        for (final Map.Entry<String, String> entry : parameters.entrySet()) {
            final VersionedParameter param = new VersionedParameter();
            param.setName(entry.getKey());
            param.setValue(entry.getValue());
            param.setSensitive(sensitiveParamNames.contains(entry.getKey()));
            versionedParameters.add(param);
        }

        final VersionedParameterContext context = new VersionedParameterContext();
        context.setName(name);
        context.setDescription("Generated for unit test");
        context.setParameters(versionedParameters);
        context.setIdentifier(UUID.randomUUID().toString());

        return context;
    }

    private void setReferences(final ControllerServiceNode service, final ComponentNode... reference) {
        final ControllerServiceReference csReference = Mockito.mock(ControllerServiceReference.class);
        when(csReference.getReferencingComponents()).thenReturn(new HashSet<>(Arrays.asList(reference)));
        when(service.getReferences()).thenReturn(csReference);
    }


    //////////
    // Convenience methods for testing
    //////////

    private void startProcessor(final ProcessorNode processor) {
        startProcessor(processor, true);
    }

    private void startProcessor(final ProcessorNode processor, final boolean allowStopToComplete) {
        when(processor.isRunning()).thenReturn(true);
        when(processor.getScheduledState()).thenReturn(org.apache.nifi.controller.ScheduledState.RUNNING);

        // If we want the stopping to complete, created an already-completed future. Otherwise, create a CompletableFuture that we will never complete.
        final CompletableFuture<Void> future = allowStopToComplete ? CompletableFuture.completedFuture(null) : new CompletableFuture<>();
        when(group.stopProcessor(processor)).thenReturn(future);
    }

    private void verifyStopped(final ProcessorNode processor) {
        verify(group, atLeast(1)).stopProcessor(processor);
    }

    private void verifyStopped(final Port port) {
        if (port.getConnectableType() == ConnectableType.INPUT_PORT) {
            verify(group, atLeast(1)).stopInputPort(port);
        } else {
            verify(group, atLeast(1)).stopOutputPort(port);
        }
    }

    private void verifyRestarted(final Connectable component) {
        verify(componentScheduler, atLeast(1)).startComponent(component);
    }

    private void verifyNotRestarted(final Connectable component) {
        verify(componentScheduler, atLeast(0)).startComponent(component);
    }

    private VersionedConnection createMinimalVersionedConnection(final ProcessorNode source, final ProcessorNode destination) {
        final ConnectableComponent connectableComponentA = createConnectableComponent(source);
        final ConnectableComponent connectableComponentB = createConnectableComponent(destination);

        final VersionedConnection versionedConnection = new VersionedConnection();
        versionedConnection.setBackPressureDataSizeThreshold("1 GB");
        versionedConnection.setBackPressureObjectThreshold(10000L);
        versionedConnection.setSource(connectableComponentA);
        versionedConnection.setDestination(connectableComponentB);
        versionedConnection.setLoadBalanceStrategy(LoadBalanceStrategy.DO_NOT_LOAD_BALANCE.name());
        versionedConnection.setLabelIndex(0);
        versionedConnection.setSelectedRelationships(Collections.singleton("success"));
        versionedConnection.setzIndex(0L);

        return versionedConnection;
    }

    private ConnectableComponent createConnectableComponent(final ProcessorNode processor) {
        final ConnectableComponent component = new ConnectableComponent();
        component.setId(processor.getIdentifier());
        component.setInstanceIdentifier(processor.getIdentifier());
        component.setType(ConnectableComponentType.PROCESSOR);
        return component;
    }

    private VersionedProcessor createMinimalVersionedProcessor() {
        final VersionedProcessor versionedProcessor = new VersionedProcessor();
        versionedProcessor.setIdentifier("12345");
        versionedProcessor.setName("name");
        versionedProcessor.setAutoTerminatedRelationships(Collections.emptySet());
        versionedProcessor.setBundle(bundle);
        versionedProcessor.setBulletinLevel(LogLevel.WARN.name());
        versionedProcessor.setConcurrentlySchedulableTaskCount(1);
        versionedProcessor.setPropertyDescriptors(Collections.emptyMap());
        versionedProcessor.setScheduledState(ScheduledState.ENABLED);
        versionedProcessor.setRunDurationMillis(0L);
        versionedProcessor.setSchedulingStrategy(SchedulingStrategy.TIMER_DRIVEN.name());
        versionedProcessor.setExecutionNode(ExecutionNode.ALL.name());
        versionedProcessor.setProperties(Collections.singletonMap("abc", "123"));
        versionedProcessor.setPosition(new Position(0D, 0D));

        return versionedProcessor;
    }

    private VersionedControllerService createMinimalVersionedControllerService() {
        final VersionedControllerService versionedService = new VersionedControllerService();
        versionedService.setIdentifier("12345");
        versionedService.setName("name");
        versionedService.setBundle(bundle);
        versionedService.setPropertyDescriptors(Collections.emptyMap());
        versionedService.setScheduledState(ScheduledState.DISABLED);
        versionedService.setProperties(Collections.singletonMap("abc", "123"));
        versionedService.setPosition(new Position(0D, 0D));
        versionedService.setType("ControllerServiceImpl");

        return versionedService;
    }

    private VersionedPort createMinimalVersionedPort(final ComponentType componentType) {
        final VersionedPort versionedPort = new VersionedPort();
        versionedPort.setIdentifier("1234");
        versionedPort.setInstanceIdentifier("1234");
        versionedPort.setName(componentType == ComponentType.INPUT_PORT ? "Input" : "Output");
        versionedPort.setScheduledState(ScheduledState.ENABLED);
        versionedPort.setComponentType(ComponentType.INPUT_PORT);
        versionedPort.setPosition(new Position(0D, 0D));
        versionedPort.setConcurrentlySchedulableTaskCount(1);

        return versionedPort;
    }

    private class ScheduledStateUpdate<T> {
        private T component;
        private org.apache.nifi.controller.ScheduledState state;

        public ScheduledStateUpdate(T component, org.apache.nifi.controller.ScheduledState state) {
            this.component = component;
            this.state = state;
        }
    }

    private class ControllerServiceStateUpdate {
        private ControllerServiceNode controllerService;
        private ControllerServiceState state;

        public ControllerServiceStateUpdate(ControllerServiceNode controllerService, ControllerServiceState state) {
            this.controllerService = controllerService;
            this.state = state;
        }
    }

    private class CapturingScheduledStateChangeListener implements ScheduledStateChangeListener {

        private List<ScheduledStateUpdate<ProcessorNode>> processorUpdates = new ArrayList<>();
        private List<ScheduledStateUpdate<Port>> portUpdates = new ArrayList<>();
        private List<ControllerServiceStateUpdate> serviceUpdates = new ArrayList<>();
        private List<ScheduledStateUpdate<ReportingTaskNode>> reportingTaskUpdates = new ArrayList<>();

        @Override
        public void onScheduledStateChange(final ProcessorNode processor, final ScheduledState intendedState) {
            processorUpdates.add(new ScheduledStateUpdate<>(processor, processor.getScheduledState()));
        }

        @Override
        public void onScheduledStateChange(ControllerServiceNode controllerService, final ScheduledState intendedState) {
            serviceUpdates.add(new ControllerServiceStateUpdate(controllerService, controllerService.getState()));
        }

        @Override
        public void onScheduledStateChange(ReportingTaskNode reportingTask, final ScheduledState intendedState) {
            reportingTaskUpdates.add(new ScheduledStateUpdate<>(reportingTask, reportingTask.getScheduledState()));
        }

        @Override
        public void onScheduledStateChange(final Port port, final ScheduledState intendedState) {
            portUpdates.add(new ScheduledStateUpdate<>(port, port.getScheduledState()));
        }

        void assertNumProcessorUpdates(int expectedNum) {
            assertEquals("Expected " + expectedNum + " processor state changes", expectedNum, processorUpdates.size());
        }

        void assertProcessorUpdates(final ScheduledStateUpdate<ProcessorNode>... updates) {
            final Iterator<ScheduledStateUpdate<ProcessorNode>> it = processorUpdates.iterator();
            for (final ScheduledStateUpdate<ProcessorNode> expectedUpdate : updates) {
                final ScheduledStateUpdate<ProcessorNode> capturedUpdate = it.next();
                assertEquals(expectedUpdate.component.getName(), capturedUpdate.component.getName());
                if (expectedUpdate.state == org.apache.nifi.controller.ScheduledState.RUNNING) {
                    verifyRestarted(capturedUpdate.component);
                } else if (expectedUpdate.state == org.apache.nifi.controller.ScheduledState.STOPPED) {
                    verifyStopped(capturedUpdate.component);
                }
            }
        }

        void assertNumPortUpdates(int expectedNum) {
            assertEquals("Expected " + expectedNum + " port state changes", expectedNum, portUpdates.size());
        }
    }
}
