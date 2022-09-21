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

package org.apache.nifi.controller.serialization;

import org.apache.nifi.connectable.Connectable;
import org.apache.nifi.connectable.Connection;
import org.apache.nifi.connectable.Port;
import org.apache.nifi.controller.AbstractComponentNode;
import org.apache.nifi.controller.ComponentNode;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.ParameterProviderNode;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.ReportingTaskNode;
import org.apache.nifi.controller.ScheduledState;
import org.apache.nifi.controller.UninheritableFlowException;
import org.apache.nifi.controller.flow.FlowManager;
import org.apache.nifi.controller.service.ControllerServiceNode;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.controller.service.ControllerServiceState;
import org.apache.nifi.flow.ComponentType;
import org.apache.nifi.flow.ConnectableComponent;
import org.apache.nifi.flow.ConnectableComponentType;
import org.apache.nifi.flow.VersionedComponent;
import org.apache.nifi.flow.VersionedConnection;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.groups.RemoteProcessGroup;
import org.apache.nifi.parameter.ParameterContext;
import org.apache.nifi.registry.flow.diff.DifferenceType;
import org.apache.nifi.registry.flow.diff.FlowDifference;
import org.apache.nifi.remote.RemoteGroupPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * <p>
 *     An AffectedComponentSet is a collection of components that will need to be enabled/disabled/started/stopped in order to facilitate
 *     some set of changes to a dataflow.
 * </p>
 */
public class AffectedComponentSet {
    private static final Logger logger = LoggerFactory.getLogger(AffectedComponentSet.class);
    private static final Set<ControllerServiceState> ACTIVE_CONTROLLER_SERVICE_STATES = new HashSet<>(Arrays.asList(
        ControllerServiceState.ENABLED,
        ControllerServiceState.ENABLING,
        ControllerServiceState.DISABLING
    ));
    private final FlowController flowController;
    private final FlowManager flowManager;

    private final Set<Port> inputPorts = new HashSet<>();
    private final Set<Port> outputPorts = new HashSet<>();
    private final Set<RemoteGroupPort> remoteInputPorts = new HashSet<>();
    private final Set<RemoteGroupPort> remoteOutputPorts = new HashSet<>();
    private final Set<ProcessorNode> processors = new HashSet<>();
    private final Set<ControllerServiceNode> controllerServices = new HashSet<>();
    private final Set<ReportingTaskNode> reportingTasks = new HashSet<>();
    private final Set<ParameterProviderNode> parameterProviders = new HashSet<>();

    public AffectedComponentSet(final FlowController flowController) {
        this.flowController = flowController;
        this.flowManager = flowController.getFlowManager();
    }

    public void addInputPort(final Port port) {
        if (port == null) {
            return;
        }

        inputPorts.add(port);
    }

    public void addOutputPort(final Port port) {
        if (port == null) {
            return;
        }

        outputPorts.add(port);
    }

    public void addRemoteInputPort(final RemoteGroupPort port) {
        if (port == null) {
            return;
        }

        remoteInputPorts.add(port);
    }

    public void addRemoteOutputPort(final RemoteGroupPort port) {
        if (port == null) {
            return;
        }

        remoteOutputPorts.add(port);
    }

    public void addRemoteProcessGroup(final RemoteProcessGroup remoteProcessGroup) {
        if (remoteProcessGroup == null) {
            return;
        }

        remoteProcessGroup.getInputPorts().forEach(this::addRemoteInputPort);
        remoteProcessGroup.getOutputPorts().forEach(this::addRemoteOutputPort);
    }

    public void addProcessor(final ProcessorNode processor) {
        if (processor == null) {
            return;
        }

        processors.add(processor);
    }

    public void addControllerService(final ControllerServiceNode controllerService) {
        if (controllerService == null) {
            return;
        }

        controllerServices.add(controllerService);

        final List<ComponentNode> referencingComponents = controllerService.getReferences().findRecursiveReferences(ComponentNode.class);
        for (final ComponentNode reference : referencingComponents) {
            if (reference instanceof ControllerServiceNode) {
                addControllerService((ControllerServiceNode) reference);
            } else if (reference instanceof ProcessorNode) {
                addProcessor((ProcessorNode) reference);
            } else if (reference instanceof ReportingTaskNode) {
                addReportingTask((ReportingTaskNode) reference);
            } else if (reference instanceof ParameterProviderNode) {
                addParameterProvider((ParameterProviderNode) reference);
            }
        }
    }

    public boolean isControllerServiceAffected(final String serviceId) {
        for (final ControllerServiceNode serviceNode : controllerServices) {
            if (serviceNode.getIdentifier().equals(serviceId)) {
                return true;
            }
        }

        return false;
    }

    private void addControllerServiceWithoutReferences(final ControllerServiceNode controllerService) {
        if (controllerService == null) {
            return;
        }

        controllerServices.add(controllerService);
    }

    public void addReportingTask(final ReportingTaskNode task) {
        if (task == null) {
            return;
        }

        reportingTasks.add(task);
    }

    public boolean isReportingTaskAffected(final String reportingTaskId) {
        for (final ReportingTaskNode taskNode : reportingTasks) {
            if (taskNode.getIdentifier().equals(reportingTaskId)) {
                return true;
            }
        }

        return false;
    }

    public void addParameterProvider(final ParameterProviderNode parameterProvider) {
        if (parameterProvider == null) {
            return;
        }

        parameterProviders.add(parameterProvider);
    }

    public boolean isParameterProviderAffected(final String parameterProviderId) {
        for (final ParameterProviderNode parameterProviderNode : parameterProviders) {
            if (parameterProviderNode.getIdentifier().equals(parameterProviderId)) {
                return true;
            }
        }

        return false;
    }

    public void addConnection(final Connection connection) {
        if (connection == null) {
            return;
        }

        addConnectable(connection.getSource());
        addConnectable(connection.getDestination());
    }

    public void addConnectable(final Connectable connectable) {
        if (connectable == null) {
            return;
        }

        switch (connectable.getConnectableType()) {
            case INPUT_PORT:
                addInputPort((Port) connectable);
                break;
            case OUTPUT_PORT:
                addOutputPort((Port) connectable);
                break;
            case PROCESSOR:
                addProcessor((ProcessorNode) connectable);
                break;
            case REMOTE_INPUT_PORT:
                addRemoteInputPort((RemoteGroupPort) connectable);
                break;
            case REMOTE_OUTPUT_PORT:
                addRemoteOutputPort((RemoteGroupPort) connectable);
        }
    }

    /**
     * Adds any component that is affected by the given Flow Difference
     * @param difference the Flow Difference
     */
    public void addAffectedComponents(final FlowDifference difference) {
        final DifferenceType differenceType = difference.getDifferenceType();

        if (differenceType == DifferenceType.COMPONENT_ADDED) {
            // The component doesn't exist. But if it's a connection, the source or the destination might. And those need to be accounted for.
            if (difference.getComponentB().getComponentType() == ComponentType.CONNECTION) {
                addComponentsForNewConnection((VersionedConnection) difference.getComponentB());
            }

            return;
        }

        if (differenceType == DifferenceType.PARAMETER_VALUE_CHANGED || differenceType == DifferenceType.PARAMETER_DESCRIPTION_CHANGED || differenceType == DifferenceType.PARAMETER_REMOVED) {
            addComponentsForParameterUpdate(difference);
            return;
        }

        if (differenceType == DifferenceType.PARAMETER_CONTEXT_CHANGED) {
            addComponentsForParameterContextChange(difference);
            return;
        }

        if (differenceType == DifferenceType.INHERITED_CONTEXTS_CHANGED) {
            addComponentsForInheritedParameterContextChange(difference);
        }

        if (differenceType == DifferenceType.VARIABLE_CHANGED || differenceType == DifferenceType.VARIABLE_ADDED || differenceType == DifferenceType.VARIABLE_REMOVED) {
            addComponentsForVariableChange(difference.getComponentA().getInstanceIdentifier(), difference.getFieldName().orElse(null));
            return;
        }

        if (differenceType == DifferenceType.RPG_URL_CHANGED) {
            final String instanceId = difference.getComponentA().getInstanceIdentifier();
            final RemoteProcessGroup rpg = flowManager.getRootGroup().findRemoteProcessGroup(instanceId);
            if (rpg != null) {
                addRemoteProcessGroup(rpg);
            }
        }

        if (differenceType == DifferenceType.COMPONENT_REMOVED && difference.getComponentA().getComponentType() == ComponentType.PROCESS_GROUP) {
            // If a Process Group is removed, we need to consider any component within the Process Group as affected also
            addAllComponentsWithinGroup(difference.getComponentA().getInstanceIdentifier());
        }

        addAffectedComponents(difference.getComponentA());
    }

    private void addAllComponentsWithinGroup(final String groupId) {
        final ProcessGroup processGroup = flowManager.getGroup(groupId);
        if (processGroup == null) {
            return;
        }

        processGroup.getProcessors().forEach(this::addProcessor);
        processGroup.getControllerServices(false).forEach(this::addControllerServiceWithoutReferences);
        processGroup.getInputPorts().forEach(this::addInputPort);
        processGroup.getOutputPorts().forEach(this::addOutputPort);
        processGroup.getRemoteProcessGroups().forEach(this::addRemoteProcessGroup);
        processGroup.getProcessGroups().forEach(child -> addAllComponentsWithinGroup(child.getIdentifier()));
    }

    private void addComponentsForVariableChange(final String groupId, final String variableName) {
        if (groupId == null || variableName == null) {
            return;
        }

        final ProcessGroup group = flowManager.getGroup(groupId);
        if (group == null) {
            return;
        }

        final Set<ComponentNode> affectedComponents = group.getComponentsAffectedByVariable(variableName);
        for (final ComponentNode component : affectedComponents) {
            if (component instanceof ProcessorNode) {
                addProcessor((ProcessorNode) component);
            } else if (component instanceof ControllerServiceNode) {
                addControllerService((ControllerServiceNode) component);
            }
        }
    }

    private void addComponentsForInheritedParameterContextChange(final FlowDifference difference) {
        // If the inherited parameter contexts have changed, any component referencing a parameter in that context is affected.
        final String parameterContextId = difference.getComponentA().getInstanceIdentifier();
        final ParameterContext context = flowManager.getParameterContextManager().getParameterContext(parameterContextId);
        if (context == null) {
            return;
        }

        final Set<ProcessGroup> boundGroups = context.getParameterReferenceManager().getProcessGroupsBound(context);
        for (final ProcessGroup group : boundGroups) {
            group.getProcessors().stream()
                .filter(AbstractComponentNode::isReferencingParameter)
                .forEach(this::addProcessor);

            group.getControllerServices(false).stream()
                .filter(ComponentNode::isReferencingParameter)
                .forEach(this::addControllerService);
        }
    }

    private void addComponentsForParameterContextChange(final FlowDifference difference) {
        // When the parameter context that a PG is bound to is updated, any component referencing a parameter is affected.
        final String groupId = difference.getComponentA().getInstanceIdentifier();
        final ProcessGroup group = flowManager.getGroup(groupId);
        if (group == null) {
            return;
        }

        group.getProcessors().stream()
            .filter(AbstractComponentNode::isReferencingParameter)
            .forEach(this::addProcessor);

        group.getControllerServices(false).stream()
            .filter(ComponentNode::isReferencingParameter)
            .forEach(this::addControllerService);
    }

    private void addComponentsForParameterUpdate(final FlowDifference difference) {
        final DifferenceType differenceType = difference.getDifferenceType();

        final Optional<String> optionalParameterName = difference.getFieldName();
        if (!optionalParameterName.isPresent()) {
            logger.warn("Encountered a Flow Difference {} with Difference Type of {} but no indication as to which parameter was updated.", difference, differenceType);
            return;
        }

        final String parameterName = optionalParameterName.get();
        final String contextId = difference.getComponentA().getInstanceIdentifier();
        final ParameterContext parameterContext = flowManager.getParameterContextManager().getParameterContext(contextId);
        if (parameterContext == null) {
            logger.warn("Encountered a Flow Difference {} with a Difference Type of {} but found no Parameter Context with Instance ID {}", difference, differenceType, contextId);
            return;
        }

        final Set<ControllerServiceNode> referencingServices = parameterContext.getParameterReferenceManager().getControllerServicesReferencing(parameterContext, parameterName);
        final Set<ProcessorNode> referencingProcessors = parameterContext.getParameterReferenceManager().getProcessorsReferencing(parameterContext, parameterName);

        referencingServices.forEach(this::addControllerService);
        referencingProcessors.forEach(this::addProcessor);
    }

    private void addComponentsForNewConnection(final VersionedConnection connection) {
        final ConnectableComponent sourceComponent = connection.getSource();
        final Connectable sourceConnectable = getConnectable(sourceComponent.getType(), sourceComponent.getInstanceIdentifier());
        if (sourceConnectable != null) {
            addConnectable(sourceConnectable);
        }

        final ConnectableComponent destinationComponent = connection.getDestination();
        final Connectable destinationConnectable = getConnectable(destinationComponent.getType(), destinationComponent.getInstanceIdentifier());
        if (destinationConnectable != null) {
            addConnectable(destinationConnectable);
        }
    }

    private Connectable getConnectable(final ConnectableComponentType type, final String identifier) {
        switch (type) {
            case FUNNEL:
                return flowManager.getFunnel(identifier);
            case INPUT_PORT:
                return flowManager.getInputPort(identifier);
            case OUTPUT_PORT:
                return flowManager.getOutputPort(identifier);
            case PROCESSOR:
                return flowManager.getProcessorNode(identifier);
            case REMOTE_INPUT_PORT:
            case REMOTE_OUTPUT_PORT:
                return flowManager.getRootGroup().findRemoteGroupPort(identifier);
            default:
                return null;
        }
    }

    private void addAffectedComponents(final VersionedComponent versionedComponent) {
        final String componentId = versionedComponent.getInstanceIdentifier();
        switch (versionedComponent.getComponentType()) {
            case CONNECTION:
                addConnection(flowManager.getConnection(componentId));
                break;
            case CONTROLLER_SERVICE:
                addControllerService(flowManager.getControllerServiceNode(componentId));
                break;
            case INPUT_PORT:
                addInputPort(flowManager.getInputPort(componentId));
                break;
            case OUTPUT_PORT:
                addOutputPort(flowManager.getOutputPort(componentId));
                break;
            case PROCESS_GROUP:
                break;
            case PROCESSOR:
                addProcessor(flowManager.getProcessorNode(componentId));
                break;
            case REMOTE_INPUT_PORT:
                final RemoteGroupPort remoteInputPort = flowManager.getRootGroup().findRemoteGroupPort(componentId);
                if (remoteInputPort != null) {
                    addRemoteInputPort(remoteInputPort);
                }
                break;
            case REMOTE_OUTPUT_PORT:
                final RemoteGroupPort remoteOutputPort = flowManager.getRootGroup().findRemoteGroupPort(componentId);
                if (remoteOutputPort != null) {
                    addRemoteOutputPort(remoteOutputPort);
                }
                break;
            case REMOTE_PROCESS_GROUP:
                addRemoteProcessGroup(flowManager.getRootGroup().findRemoteProcessGroup(componentId));
                break;
            case REPORTING_TASK:
                addReportingTask(flowManager.getReportingTaskNode(componentId));
                break;
        }
    }

    /**
     * Returns a new AffectedComponentSet that represents only those components that are currently active. A component is considered active if it is an Input/Output Port
     * and is running, is a Processor or reporting task that has at least one active thread or a scheduled state of RUNNING or STARTING, or is a Controller Service that is
     * ENABLED or ENABLING.
     *
     * @return an AffectedComponentSet that represents all components within this AffectedComponentSet that are currently active. The components contained by the returned AffectedComponentSet
     * will always be a subset or equal to the set of components contained by this.
     */
    public AffectedComponentSet toActiveSet() {
        final AffectedComponentSet active = new AffectedComponentSet(flowController);
        inputPorts.stream().filter(port -> port.getScheduledState() == ScheduledState.RUNNING).forEach(active::addInputPort);
        outputPorts.stream().filter(port -> port.getScheduledState() == ScheduledState.RUNNING).forEach(active::addOutputPort);
        remoteInputPorts.stream().filter(port -> port.getScheduledState() == ScheduledState.RUNNING).forEach(active::addRemoteInputPort);
        remoteOutputPorts.stream().filter(port -> port.getScheduledState() == ScheduledState.RUNNING).forEach(active::addRemoteOutputPort);

        processors.stream().filter(this::isActive).forEach(active::addProcessor);
        reportingTasks.stream().filter(task -> task.getScheduledState() == ScheduledState.STARTING || task.getScheduledState() == ScheduledState.RUNNING || task.isRunning())
            .forEach(active::addReportingTask);
        controllerServices.stream().filter(service -> ACTIVE_CONTROLLER_SERVICE_STATES.contains(service.getState()))
            .forEach(active::addControllerServiceWithoutReferences);

        return active;
    }

    private boolean isActive(final ProcessorNode processor) {
        // We consider component active if it's starting, running, or has active threads. The call to ProcessorNode.isRunning() will only return true if it has active threads or a scheduled
        // state of RUNNING but not if it has a scheduled state of STARTING. We also consider if the processor is to be started once the flow controller has been fully initialized, as
        // the state of the processor may not yet have been set
        final ScheduledState scheduledState = processor.getPhysicalScheduledState();
        return scheduledState == ScheduledState.STARTING || scheduledState == ScheduledState.RUNNING || processor.isRunning() || flowController.isStartAfterInitialization(processor);
    }

    private boolean isStopped(final ProcessorNode processor) {
        final ScheduledState state = processor.getPhysicalScheduledState();
        final boolean stateCorrect = state == ScheduledState.STOPPED || state == ScheduledState.DISABLED;
        return stateCorrect && !processor.isRunning();
    }

    public void start() {
        logger.info("Starting the following components: {}", this);
        flowController.getControllerServiceProvider().enableControllerServices(controllerServices);

        inputPorts.forEach(port -> port.getProcessGroup().startInputPort(port));
        outputPorts.forEach(port -> port.getProcessGroup().startOutputPort(port));
        remoteInputPorts.forEach(port -> port.getRemoteProcessGroup().startTransmitting(port));
        remoteOutputPorts.forEach(port -> port.getRemoteProcessGroup().startTransmitting(port));
        processors.forEach(processor -> processor.getProcessGroup().startProcessor(processor, false));
        reportingTasks.forEach(flowController::startReportingTask);
    }

    public void removeComponents(final ComponentSetFilter filter) {
        inputPorts.removeIf(filter::testInputPort);
        outputPorts.removeIf(filter::testOutputPort);
        remoteInputPorts.removeIf(filter::testRemoteInputPort);
        remoteOutputPorts.removeIf(filter::testRemoteOutputPort);
        processors.removeIf(filter::testProcessor);
        controllerServices.removeIf(filter::testControllerService);
        reportingTasks.removeIf(filter::testReportingTask);
    }

    /**
     * Returns a new AffectedComponentSet that represents only those components that currently exist within the NiFi instance. When a set of dataflow updates have occurred, it is very possible
     * that one or more components referred to by the AffectedComponentSet no longer exist (for example, there was a dataflow update that removed a Processor, so that Processor no longer exists).
     *
     * @return an AffectedComponentSet that represents all components within this AffectedComponentSet that currently exist within the NiFi instance. The components contained by the returned
     * AffectedComponentSetwill always be a subset or equal to the set of components contained by this.
     */
    public AffectedComponentSet toExistingSet() {
        final ControllerServiceProvider serviceProvider = flowController.getControllerServiceProvider();

        final AffectedComponentSet existing = new AffectedComponentSet(flowController);
        inputPorts.stream().filter(port -> port.getProcessGroup().getInputPort(port.getIdentifier()) != null).forEach(existing::addInputPort);
        outputPorts.stream().filter(port -> port.getProcessGroup().getOutputPort(port.getIdentifier()) != null).forEach(existing::addOutputPort);
        remoteInputPorts.stream().filter(port -> port.getProcessGroup().findRemoteGroupPort(port.getIdentifier()) != null).forEach(existing::addRemoteInputPort);
        remoteOutputPorts.stream().filter(port -> port.getProcessGroup().findRemoteGroupPort(port.getIdentifier()) != null).forEach(existing::addRemoteOutputPort);
        processors.stream().filter(processor -> processor.getProcessGroup().getProcessor(processor.getIdentifier()) != null).forEach(existing::addProcessor);
        reportingTasks.stream().filter(task -> flowController.getReportingTaskNode(task.getIdentifier()) != null).forEach(existing::addReportingTask);
        controllerServices.stream().filter(service -> serviceProvider.getControllerServiceNode(service.getIdentifier()) != null).forEach(existing::addControllerServiceWithoutReferences);

        return existing;
    }


    /**
     * Returns a new AffectedComponentSet that represents only those components that currently can be started. When a set of dataflow updates have occurred, it is very possible
     * that one or more components referred to by the AffectedComponentSet can no longer be started (for example, there was a dataflow update that disabled a Processor that previously was running).
     *
     * @return an AffectedComponentSet that represents all components within this AffectedComponentSet that currently exist within the NiFi instance. The components contained by the returned
     * AffectedComponentSet will always be a subset or equal to the set of components contained by this.
     */
    public AffectedComponentSet toStartableSet() {
        final AffectedComponentSet startable = new AffectedComponentSet(flowController);
        inputPorts.stream().filter(this::isStartable).forEach(startable::addInputPort);
        outputPorts.stream().filter(this::isStartable).forEach(startable::addOutputPort);
        remoteInputPorts.stream().filter(this::isStartable).forEach(startable::addRemoteInputPort);
        remoteOutputPorts.stream().filter(this::isStartable).forEach(startable::addRemoteOutputPort);
        processors.stream().filter(this::isStartable).forEach(startable::addProcessor);
        reportingTasks.stream().filter(this::isStartable).forEach(startable::addReportingTask);
        controllerServices.stream().filter(this::isStartable).forEach(startable::addControllerServiceWithoutReferences);

        return startable;
    }

    private boolean isStartable(final ComponentNode componentNode) {
        if (componentNode == null) {
            return false;
        }

        if (componentNode instanceof ProcessorNode) {
            return ((ProcessorNode) componentNode).getScheduledState() != ScheduledState.DISABLED;
        }
        if (componentNode instanceof ReportingTaskNode) {
            return ((ReportingTaskNode) componentNode).getScheduledState() != ScheduledState.DISABLED;
        }

        return true;
    }

    private boolean isStartable(final Port port) {
        if (port == null) {
            return false;
        }

        return port.getScheduledState() != ScheduledState.DISABLED;
    }

    public void stop() {
        logger.info("Stopping the following components: {}", this);
        final long start = System.currentTimeMillis();

        inputPorts.forEach(port -> port.getProcessGroup().stopInputPort(port));
        outputPorts.forEach(port -> port.getProcessGroup().stopOutputPort(port));
        remoteInputPorts.forEach(port -> port.getRemoteProcessGroup().stopTransmitting(port));
        remoteOutputPorts.forEach(port -> port.getRemoteProcessGroup().stopTransmitting(port));
        processors.forEach(processor -> processor.getProcessGroup().stopProcessor(processor));
        reportingTasks.forEach(flowController::stopReportingTask);

        waitForConnectablesStopped();

        if (!controllerServices.isEmpty()) {
            final Future<Void> disableFuture = flowController.getControllerServiceProvider().disableControllerServicesAsync(controllerServices);
            waitForControllerServicesStopped(disableFuture);
        }

        final long millis = System.currentTimeMillis() - start;
        logger.info("Successfully stopped all components in {} milliseconds", millis);
    }

    private void waitForControllerServicesStopped(final Future<Void> future) {
        try {
            while (true) {
                logger.info("Waiting for all Controller Services to become disabled...");
                if (logger.isDebugEnabled()) {
                    final Set<ControllerServiceNode> activeServices = controllerServices.stream().filter(ControllerServiceNode::isActive).collect(Collectors.toSet());
                    logger.debug("There are currently {} active Controller Services: {}", activeServices.size(), activeServices);
                }

                try {
                    future.get(10, TimeUnit.SECONDS);
                    return;
                } catch (final TimeoutException ignored) {
                }
            }
        } catch (final Exception e) {
            throw new UninheritableFlowException("Could not disable all affected Controller Services", e);
        }
    }

    private void waitForConnectablesStopped() {
        long count = 0L;
        try {
            while (!componentsStopped()) {
                if (count++ % 1000 == 0) {
                    // The 0th time and every 1000th time (10 seconds), log an update
                    logger.info("Waiting for all required Processors and Reporting Tasks to stop...");
                    if (reportingTasks.isEmpty() && processors.isEmpty()) {
                        return;
                    }

                    if (logger.isDebugEnabled()) {
                        final Set<ReportingTaskNode> activeReportingTasks = reportingTasks.stream().filter(ReportingTaskNode::isRunning).collect(Collectors.toSet());
                        logger.debug("There are currently {} active Reporting Tasks: {}", activeReportingTasks.size(), activeReportingTasks);

                        final Set<ProcessorNode> activeProcessors = processors.stream()
                            .filter(processor -> !isStopped(processor))
                            .collect(Collectors.toSet());
                        logger.debug("There are currently {} active Processors: {}", activeProcessors.size(), activeProcessors);
                    }
                }

                Thread.sleep(10L);
            }
        } catch (final Exception e) {
            throw new UninheritableFlowException("Could not stop all affected components", e);
        }
    }

    private boolean componentsStopped() {
        if (processors.stream().anyMatch(processor -> !isStopped(processor))) {
            return false;
        }
        if (reportingTasks.stream().anyMatch(ReportingTaskNode::isRunning)) {
            return false;
        }

        return true;
    }

    @Override
    public String toString() {
        return "AffectedComponentSet[" +
            "inputPorts=" + inputPorts +
            ", outputPorts=" + outputPorts +
            ", remoteInputPorts=" + remoteInputPorts +
            ", remoteOutputPorts=" + remoteOutputPorts +
            ", processors=" + processors +
            ", parameterProviders=" + parameterProviders +
            ", controllerServices=" + controllerServices +
            ", reportingTasks=" + reportingTasks +
            "]";
    }
}
