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
package org.apache.nifi.controller;

import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.RequiresInstanceClassLoading;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.configuration.DefaultSettings;
import org.apache.nifi.bundle.Bundle;
import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.components.ConfigurableComponent;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.components.state.StateManagerProvider;
import org.apache.nifi.components.validation.ValidationTrigger;
import org.apache.nifi.controller.exception.ProcessorInstantiationException;
import org.apache.nifi.controller.kerberos.KerberosConfig;
import org.apache.nifi.controller.parameter.ParameterProviderInstantiationException;
import org.apache.nifi.controller.parameter.StandardParameterProviderNode;
import org.apache.nifi.controller.reporting.ReportingTaskInstantiationException;
import org.apache.nifi.controller.reporting.StandardReportingInitializationContext;
import org.apache.nifi.controller.reporting.StandardReportingTaskNode;
import org.apache.nifi.controller.service.ControllerServiceInvocationHandler;
import org.apache.nifi.controller.service.ControllerServiceNode;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.controller.service.GhostControllerService;
import org.apache.nifi.controller.service.StandardControllerServiceInitializationContext;
import org.apache.nifi.controller.service.StandardControllerServiceInvocationHandler;
import org.apache.nifi.controller.service.StandardControllerServiceNode;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.NarCloseable;
import org.apache.nifi.parameter.GhostParameterProvider;
import org.apache.nifi.parameter.ParameterProvider;
import org.apache.nifi.parameter.ParameterProviderInitializationContext;
import org.apache.nifi.parameter.StandardParameterProviderInitializationContext;
import org.apache.nifi.processor.GhostProcessor;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.SimpleProcessLogger;
import org.apache.nifi.processor.StandardProcessorInitializationContext;
import org.apache.nifi.processor.StandardValidationContextFactory;
import org.apache.nifi.registry.ComponentVariableRegistry;
import org.apache.nifi.registry.VariableRegistry;
import org.apache.nifi.registry.variable.StandardComponentVariableRegistry;
import org.apache.nifi.reporting.GhostReportingTask;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.reporting.ReportingInitializationContext;
import org.apache.nifi.reporting.ReportingTask;
import org.apache.nifi.scheduling.SchedulingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ExtensionBuilder {
    private static final Logger logger = LoggerFactory.getLogger(ExtensionBuilder.class);

    private String type;
    private String identifier;
    private BundleCoordinate bundleCoordinate;
    private ExtensionManager extensionManager;
    private Set<URL> classpathUrls;
    private KerberosConfig kerberosConfig = KerberosConfig.NOT_CONFIGURED;
    private ControllerServiceProvider serviceProvider;
    private NodeTypeProvider nodeTypeProvider;
    private VariableRegistry variableRegistry;
    private ProcessScheduler processScheduler;
    private ValidationTrigger validationTrigger;
    private ReloadComponent reloadComponent;
    private FlowController flowController;
    private StateManagerProvider stateManagerProvider;
    private String classloaderIsolationKey;

    public ExtensionBuilder type(final String type) {
        this.type = type;
        return this;
    }

    public ExtensionBuilder identifier(final String identifier) {
        this.identifier = identifier;
        return this;
    }

    public ExtensionBuilder bundleCoordinate(final BundleCoordinate coordinate) {
        this.bundleCoordinate = coordinate;
        return this;
    }

    public ExtensionBuilder addClasspathUrls(final Set<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return this;
        }

        if (this.classpathUrls == null) {
            this.classpathUrls = new HashSet<>();
        }

        this.classpathUrls.addAll(urls);
        return this;
    }

    public ExtensionBuilder kerberosConfig(final KerberosConfig kerberosConfig) {
        this.kerberosConfig = kerberosConfig;
        return this;
    }

    public ExtensionBuilder controllerServiceProvider(final ControllerServiceProvider serviceProvider) {
        this.serviceProvider = serviceProvider;
        return this;
    }

    public ExtensionBuilder nodeTypeProvider(final NodeTypeProvider nodeTypeProvider) {
        this.nodeTypeProvider = nodeTypeProvider;
        return this;
    }

    public ExtensionBuilder variableRegistry(final VariableRegistry variableRegistry) {
        this.variableRegistry = variableRegistry;
        return this;
    }

    public ExtensionBuilder processScheduler(final ProcessScheduler scheduler) {
        this.processScheduler = scheduler;
        return this;
    }

    public ExtensionBuilder validationTrigger(final ValidationTrigger validationTrigger) {
        this.validationTrigger = validationTrigger;
        return this;
    }

    public ExtensionBuilder reloadComponent(final ReloadComponent reloadComponent) {
        this.reloadComponent = reloadComponent;
        return this;
    }

    public ExtensionBuilder flowController(final FlowController flowController) {
        this.flowController = flowController;
        return this;
    }

    public ExtensionBuilder stateManagerProvider(final StateManagerProvider stateManagerProvider) {
        this.stateManagerProvider = stateManagerProvider;
        return this;
    }

    public ExtensionBuilder extensionManager(final ExtensionManager extensionManager) {
        this.extensionManager = extensionManager;
        return this;
    }

    public ExtensionBuilder classloaderIsolationKey(final String classloaderIsolationKey) {
        this.classloaderIsolationKey = classloaderIsolationKey;
        return this;
    }

    public ProcessorNode buildProcessor() {
        if (identifier == null) {
            throw new IllegalStateException("Processor ID must be specified");
        }
        if (type == null) {
            throw new IllegalStateException("Processor Type must be specified");
        }
        if (bundleCoordinate == null) {
            throw new IllegalStateException("Bundle Coordinate must be specified");
        }
        if (extensionManager == null) {
            throw new IllegalStateException("Extension Manager must be specified");
        }
        if (serviceProvider == null) {
            throw new IllegalStateException("Controller Service Provider must be specified");
        }
        if (nodeTypeProvider == null) {
            throw new IllegalStateException("Node Type Provider must be specified");
        }
        if (variableRegistry == null) {
            throw new IllegalStateException("Variable Registry must be specified");
        }
        if (reloadComponent == null) {
            throw new IllegalStateException("Reload Component must be specified");
        }

        boolean creationSuccessful = true;
        LoggableComponent<Processor> loggableComponent;
        try {
            loggableComponent = createLoggableProcessor();
        } catch (final ProcessorInstantiationException pie) {
            logger.error("Could not create Processor of type " + type + " for ID " + identifier + " due to: " + pie.getMessage() + "; creating \"Ghost\" implementation");
            if (logger.isDebugEnabled()) {
                logger.debug(pie.getMessage(), pie);
            }

            final GhostProcessor ghostProc = new GhostProcessor();
            ghostProc.setIdentifier(identifier);
            ghostProc.setCanonicalClassName(type);
            loggableComponent = new LoggableComponent<>(ghostProc, bundleCoordinate, null);
            creationSuccessful = false;
        }

        final ProcessorNode processorNode = createProcessorNode(loggableComponent, creationSuccessful);
        return processorNode;
    }

    public ReportingTaskNode buildReportingTask() {
        if (identifier == null) {
            throw new IllegalStateException("ReportingTask ID must be specified");
        }
        if (type == null) {
            throw new IllegalStateException("ReportingTask Type must be specified");
        }
        if (bundleCoordinate == null) {
            throw new IllegalStateException("Bundle Coordinate must be specified");
        }
        if (extensionManager == null) {
            throw new IllegalStateException("Extension Manager must be specified");
        }
        if (serviceProvider == null) {
            throw new IllegalStateException("Controller Service Provider must be specified");
        }
        if (nodeTypeProvider == null) {
            throw new IllegalStateException("Node Type Provider must be specified");
        }
        if (variableRegistry == null) {
            throw new IllegalStateException("Variable Registry must be specified");
        }
        if (reloadComponent == null) {
            throw new IllegalStateException("Reload Component must be specified");
        }
        if (flowController == null) {
            throw new IllegalStateException("FlowController must be specified");
        }

        boolean creationSuccessful = true;
        LoggableComponent<ReportingTask> loggableComponent;
        try {
            loggableComponent = createLoggableReportingTask();
        } catch (final ReportingTaskInstantiationException rtie) {
            logger.error("Could not create ReportingTask of type " + type + " for ID " + identifier + "; creating \"Ghost\" implementation", rtie);
            final GhostReportingTask ghostReportingTask = new GhostReportingTask();
            ghostReportingTask.setIdentifier(identifier);
            ghostReportingTask.setCanonicalClassName(type);
            loggableComponent = new LoggableComponent<>(ghostReportingTask, bundleCoordinate, null);
            creationSuccessful = false;
        }

        final ReportingTaskNode taskNode = createReportingTaskNode(loggableComponent, creationSuccessful);
        return taskNode;
    }

    public ParameterProviderNode buildParameterProvider() {
        if (identifier == null) {
            throw new IllegalStateException("ParameterProvider ID must be specified");
        }
        if (type == null) {
            throw new IllegalStateException("ParameterProvider Type must be specified");
        }
        if (bundleCoordinate == null) {
            throw new IllegalStateException("Bundle Coordinate must be specified");
        }
        if (extensionManager == null) {
            throw new IllegalStateException("Extension Manager must be specified");
        }
        if (serviceProvider == null) {
            throw new IllegalStateException("Controller Service Provider must be specified");
        }
        if (nodeTypeProvider == null) {
            throw new IllegalStateException("Node Type Provider must be specified");
        }
        if (variableRegistry == null) {
            throw new IllegalStateException("Variable Registry must be specified");
        }
        if (reloadComponent == null) {
            throw new IllegalStateException("Reload Component must be specified");
        }
        if (flowController == null) {
            throw new IllegalStateException("FlowController must be specified");
        }

        boolean creationSuccessful = true;
        LoggableComponent<ParameterProvider> loggableComponent;
        try {
            loggableComponent = createLoggableParameterProvider();
        } catch (final ParameterProviderInstantiationException rtie) {
            logger.error("Could not create ParameterProvider of type " + type + " for ID " + identifier + "; creating \"Ghost\" implementation", rtie);
            final GhostParameterProvider ghostParameterProvider = new GhostParameterProvider();
            ghostParameterProvider.setIdentifier(identifier);
            ghostParameterProvider.setCanonicalClassName(type);
            loggableComponent = new LoggableComponent<>(ghostParameterProvider, bundleCoordinate, null);
            creationSuccessful = false;
        }

        final ParameterProviderNode taskNode = createParameterProviderNode(loggableComponent, creationSuccessful);
        return taskNode;
    }

    public ControllerServiceNode buildControllerService() {
        if (identifier == null) {
            throw new IllegalStateException("ReportingTask ID must be specified");
        }
        if (type == null) {
            throw new IllegalStateException("ReportingTask Type must be specified");
        }
        if (bundleCoordinate == null) {
            throw new IllegalStateException("Bundle Coordinate must be specified");
        }
        if (extensionManager == null) {
            throw new IllegalStateException("Extension Manager must be specified");
        }
        if (serviceProvider == null) {
            throw new IllegalStateException("Controller Service Provider must be specified");
        }
        if (nodeTypeProvider == null) {
            throw new IllegalStateException("Node Type Provider must be specified");
        }
        if (variableRegistry == null) {
            throw new IllegalStateException("Variable Registry must be specified");
        }
        if (reloadComponent == null) {
            throw new IllegalStateException("Reload Component must be specified");
        }
        if (stateManagerProvider == null) {
            throw new IllegalStateException("State Manager Provider must be specified");
        }

        try {
            return createControllerServiceNode();
        } catch (final Exception e) {
            logger.error("Could not create Controller Service of type " + type + " for ID " + identifier + " due to: " + e.getMessage() + "; creating \"Ghost\" implementation");
            if (logger.isDebugEnabled()) {
                logger.debug(e.getMessage(), e);
            }

            return createGhostControllerServiceNode();
        }
    }


    private ProcessorNode createProcessorNode(final LoggableComponent<Processor> processor, final boolean creationSuccessful) {
        final ComponentVariableRegistry componentVarRegistry = new StandardComponentVariableRegistry(this.variableRegistry);
        final ValidationContextFactory validationContextFactory = new StandardValidationContextFactory(serviceProvider, componentVarRegistry);

        final ProcessorNode procNode;
        if (creationSuccessful) {
            procNode = new StandardProcessorNode(processor, identifier, validationContextFactory, processScheduler, serviceProvider,
                    componentVarRegistry, reloadComponent, extensionManager, validationTrigger);
        } else {
            final String simpleClassName = type.contains(".") ? StringUtils.substringAfterLast(type, ".") : type;
            final String componentType = "(Missing) " + simpleClassName;
            procNode = new StandardProcessorNode(processor, identifier, validationContextFactory, processScheduler, serviceProvider,
                    componentType, type, componentVarRegistry, reloadComponent, extensionManager, validationTrigger, true);
        }

        applyDefaultSettings(procNode);
        applyDefaultRunDuration(procNode);

        return procNode;
    }


    private ReportingTaskNode createReportingTaskNode(final LoggableComponent<ReportingTask> reportingTask, final boolean creationSuccessful) {
        final ComponentVariableRegistry componentVarRegistry = new StandardComponentVariableRegistry(this.variableRegistry);
        final ValidationContextFactory validationContextFactory = new StandardValidationContextFactory(serviceProvider, componentVarRegistry);
        final ReportingTaskNode taskNode;
        if (creationSuccessful) {
            taskNode = new StandardReportingTaskNode(reportingTask, identifier, flowController, processScheduler,
                    validationContextFactory, componentVarRegistry, reloadComponent, extensionManager, validationTrigger);
            taskNode.setName(taskNode.getReportingTask().getClass().getSimpleName());
        } else {
            final String simpleClassName = type.contains(".") ? StringUtils.substringAfterLast(type, ".") : type;
            final String componentType = "(Missing) " + simpleClassName;

            taskNode = new StandardReportingTaskNode(reportingTask, identifier, flowController, processScheduler, validationContextFactory,
                    componentType, type, componentVarRegistry, reloadComponent, extensionManager, validationTrigger, true);
            taskNode.setName(componentType);
        }

        return taskNode;
    }

    private ParameterProviderNode createParameterProviderNode(final LoggableComponent<ParameterProvider> parameterProvider, final boolean creationSuccessful) {
        final ComponentVariableRegistry componentVarRegistry = new StandardComponentVariableRegistry(this.variableRegistry);
        final ValidationContextFactory validationContextFactory = new StandardValidationContextFactory(serviceProvider, componentVarRegistry);
        final ParameterProviderNode parameterProviderNode;
        if (creationSuccessful) {
            parameterProviderNode = new StandardParameterProviderNode(parameterProvider, identifier, flowController,
                    flowController.getControllerServiceProvider(), validationContextFactory, componentVarRegistry, reloadComponent, extensionManager,
                    validationTrigger);
            parameterProviderNode.setName(parameterProviderNode.getParameterProvider().getClass().getSimpleName());
        } else {
            final String simpleClassName = type.contains(".") ? StringUtils.substringAfterLast(type, ".") : type;
            final String componentType = "(Missing) " + simpleClassName;

            parameterProviderNode = new StandardParameterProviderNode(parameterProvider, identifier, flowController,
                    flowController.getControllerServiceProvider(), validationContextFactory, componentType, type, componentVarRegistry, reloadComponent,
                    extensionManager, validationTrigger, true);
            parameterProviderNode.setName(componentType);
        }

        return parameterProviderNode;
    }

    private void applyDefaultSettings(final ProcessorNode processorNode) {
        try {
            final Class<?> procClass = processorNode.getProcessor().getClass();

            final DefaultSettings ds = procClass.getAnnotation(DefaultSettings.class);
            if (ds != null) {
                processorNode.setYieldPeriod(ds.yieldDuration());
                processorNode.setPenalizationPeriod(ds.penaltyDuration());
                processorNode.setBulletinLevel(ds.bulletinLevel());
            }
        } catch (final Exception ex) {
            logger.error("Error while setting default settings from DefaultSettings annotation: {}", ex.toString(), ex);
        }
    }

    private void applyDefaultRunDuration(final ProcessorNode processorNode) {
        try {
            final Class<?> procClass = processorNode.getProcessor().getClass();

            final SupportsBatching sb = procClass.getAnnotation(SupportsBatching.class);
            if (sb != null) {
                processorNode.setRunDuration(sb.defaultDuration().getDuration().toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (final Exception ex) {
            logger.error("Set Default Run Duration failed", ex);
        }
    }

    private ControllerServiceNode createControllerServiceNode() throws ClassNotFoundException, IllegalAccessException, InstantiationException, InitializationException {
        final ClassLoader ctxClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final Bundle bundle = extensionManager.getBundle(bundleCoordinate);
            if (bundle == null) {
                throw new IllegalStateException("Unable to find bundle for coordinate " + bundleCoordinate.getCoordinate());
            }

            final ClassLoader detectedClassLoader = extensionManager.createInstanceClassLoader(type, identifier, bundle, classpathUrls == null ? Collections.emptySet() : classpathUrls);
            final Class<?> rawClass = Class.forName(type, true, detectedClassLoader);
            Thread.currentThread().setContextClassLoader(detectedClassLoader);

            final Class<? extends ControllerService> controllerServiceClass = rawClass.asSubclass(ControllerService.class);
            final ControllerService serviceImpl = controllerServiceClass.newInstance();

            final StandardControllerServiceInvocationHandler invocationHandler = new StandardControllerServiceInvocationHandler(extensionManager, serviceImpl);

            // extract all interfaces... controllerServiceClass is non null so getAllInterfaces is non null
            final List<Class<?>> interfaceList = ClassUtils.getAllInterfaces(controllerServiceClass);
            final Class<?>[] interfaces = interfaceList.toArray(new Class<?>[0]);

            final ControllerService proxiedService;
            if (detectedClassLoader == null) {
                proxiedService = (ControllerService) Proxy.newProxyInstance(getClass().getClassLoader(), interfaces, invocationHandler);
            } else {
                proxiedService = (ControllerService) Proxy.newProxyInstance(detectedClassLoader, interfaces, invocationHandler);
            }

            logger.info("Created Controller Service of type {} with identifier {}", type, identifier);
            final ComponentLog serviceLogger = new SimpleProcessLogger(identifier, serviceImpl);
            final TerminationAwareLogger terminationAwareLogger = new TerminationAwareLogger(serviceLogger);

            final StateManager stateManager = stateManagerProvider.getStateManager(identifier);
            final ControllerServiceInitializationContext initContext = new StandardControllerServiceInitializationContext(identifier, terminationAwareLogger,
                    serviceProvider, stateManager, kerberosConfig, nodeTypeProvider);
            serviceImpl.initialize(initContext);

            verifyControllerServiceReferences(serviceImpl, bundle.getClassLoader());

            final LoggableComponent<ControllerService> originalLoggableComponent = new LoggableComponent<>(serviceImpl, bundleCoordinate, terminationAwareLogger);
            final LoggableComponent<ControllerService> proxiedLoggableComponent = new LoggableComponent<>(proxiedService, bundleCoordinate, terminationAwareLogger);

            final ComponentVariableRegistry componentVarRegistry = new StandardComponentVariableRegistry(this.variableRegistry);
            final ValidationContextFactory validationContextFactory = new StandardValidationContextFactory(serviceProvider, componentVarRegistry);
            final ControllerServiceNode serviceNode = new StandardControllerServiceNode(originalLoggableComponent, proxiedLoggableComponent, invocationHandler,
                    identifier, validationContextFactory, serviceProvider, componentVarRegistry, reloadComponent, extensionManager, validationTrigger);
            serviceNode.setName(rawClass.getSimpleName());

            invocationHandler.setServiceNode(serviceNode);
            return serviceNode;
        } finally {
            if (ctxClassLoader != null) {
                Thread.currentThread().setContextClassLoader(ctxClassLoader);
            }
        }
    }

    private static void verifyControllerServiceReferences(final ConfigurableComponent component, final ClassLoader bundleClassLoader) throws InstantiationException {
        // If a component lives in the same NAR as a Controller Service API, and the component references the Controller Service API (either
        // by itself implementing the API or by having a Property Descriptor that identifies the Controller Service), then the component is not
        // allowed to Require Instance Class Loading. This is done because when a component requires Instance Class Loading, the jars within the
        // NAR and its parents must be copied to a new class loader all the way up to the point of the Controller Service APIs. If the Controller
        // Service API lives in the same NAR as the implementation itself, then we cannot duplicate the NAR ClassLoader. Otherwise, we would have
        // two different NAR ClassLoaders that each define the Service API. And the Service API class must live in the parent ClassLoader for both
        // the referencing component AND the implementing component.

        // if the extension does not require instance classloading, there is no concern.
        final boolean requiresInstanceClassLoading = component.getClass().isAnnotationPresent(RequiresInstanceClassLoading.class);
        if (!requiresInstanceClassLoading) {
            logger.debug("Instance ClassLoading is not required for {}", component);
            return;
        }

        logger.debug("Component {} requires Instance Class Loading", component);

        final Class<?> originalExtensionType = component.getClass();
        final ClassLoader originalExtensionClassLoader = originalExtensionType.getClassLoader();

        // Find any Controller Service API's that are bundled in the same NAR.
        final Set<Class<?>> cobundledApis = new HashSet<>();
        try (final NarCloseable closeable = NarCloseable.withComponentNarLoader(component.getClass().getClassLoader())) {
            final List<PropertyDescriptor> descriptors = component.getPropertyDescriptors();
            if (descriptors != null && !descriptors.isEmpty()) {
                for (final PropertyDescriptor descriptor : descriptors) {
                    final Class<? extends ControllerService> serviceApi = descriptor.getControllerServiceDefinition();
                    if (serviceApi != null && bundleClassLoader.equals(serviceApi.getClassLoader())) {
                        cobundledApis.add(serviceApi);
                    }
                }
            }
        }

        logger.debug("Component {} is co-bundled with {} Controller Service APIs based on referenced Controller Services: {}", component, cobundledApis.size(), cobundledApis);

        // If the component is a Controller Service, it should also not extend from any API that is in the same class loader.
        if (component instanceof ControllerService) {
            Class<?> extensionType = component.getClass();
            while (extensionType != null) {
                for (final Class<?> ifc : extensionType.getInterfaces()) {
                    if (originalExtensionClassLoader.equals(ifc.getClassLoader())) {
                        cobundledApis.add(ifc);
                    }
                }

                extensionType = extensionType.getSuperclass();
            }

            logger.debug("Component {} is co-bundled with {} Controller Service APIs based on referenced Controller Services and services that are implemented: {}",
                component, cobundledApis.size(), cobundledApis);
        }

        if (!cobundledApis.isEmpty()) {
            final String message = String.format("Controller Service %s is bundled with its supporting APIs %s. The service APIs should not be bundled with the implementations.",
                originalExtensionType.getName(), org.apache.nifi.util.StringUtils.join(cobundledApis.stream().map(Class::getName).collect(Collectors.toSet()), ", "));
            throw new InstantiationException(message);
        }
    }


    private ControllerServiceNode createGhostControllerServiceNode() {
        final String simpleClassName = type.contains(".") ? StringUtils.substringAfterLast(type, ".") : type;
        final String componentType = "(Missing) " + simpleClassName;

        final GhostControllerService ghostService = new GhostControllerService(identifier, type);
        final LoggableComponent<ControllerService> proxiedLoggableComponent = new LoggableComponent<>(ghostService, bundleCoordinate, null);

        final ControllerServiceInvocationHandler invocationHandler = new StandardControllerServiceInvocationHandler(extensionManager, ghostService);

        final ComponentVariableRegistry componentVarRegistry = new StandardComponentVariableRegistry(this.variableRegistry);
        final ValidationContextFactory validationContextFactory = new StandardValidationContextFactory(serviceProvider, variableRegistry);
        final ControllerServiceNode serviceNode = new StandardControllerServiceNode(proxiedLoggableComponent, proxiedLoggableComponent, invocationHandler, identifier,
                validationContextFactory, serviceProvider, componentType, type, componentVarRegistry, reloadComponent, extensionManager, validationTrigger, true);

        return serviceNode;
    }

    private LoggableComponent<Processor> createLoggableProcessor() throws ProcessorInstantiationException {
        try {
            final LoggableComponent<Processor> processorComponent = createLoggableComponent(Processor.class);
            final Processor processor = processorComponent.getComponent();

            final ProcessorInitializationContext initiContext = new StandardProcessorInitializationContext(identifier, processorComponent.getLogger(),
                    serviceProvider, nodeTypeProvider, kerberosConfig);
            processor.initialize(initiContext);

            final Bundle bundle = extensionManager.getBundle(bundleCoordinate);
            verifyControllerServiceReferences(processor, bundle.getClassLoader());

            return processorComponent;
        } catch (final Exception e) {
            throw new ProcessorInstantiationException(type, e);
        }
    }


    private LoggableComponent<ReportingTask> createLoggableReportingTask() throws ReportingTaskInstantiationException {
        try {
            final LoggableComponent<ReportingTask> taskComponent = createLoggableComponent(ReportingTask.class);

            final String taskName = taskComponent.getComponent().getClass().getSimpleName();
            final ReportingInitializationContext config = new StandardReportingInitializationContext(identifier, taskName,
                    SchedulingStrategy.TIMER_DRIVEN, "1 min", taskComponent.getLogger(), serviceProvider, kerberosConfig, nodeTypeProvider);

            taskComponent.getComponent().initialize(config);

            final Bundle bundle = extensionManager.getBundle(bundleCoordinate);
            verifyControllerServiceReferences(taskComponent.getComponent(), bundle.getClassLoader());

            return taskComponent;
        } catch (final Exception e) {
            throw new ReportingTaskInstantiationException(type, e);
        }
    }

    private LoggableComponent<ParameterProvider> createLoggableParameterProvider() throws ParameterProviderInstantiationException {
        try {
            final LoggableComponent<ParameterProvider> providerComponent = createLoggableComponent(ParameterProvider.class);

            final String taskName = providerComponent.getComponent().getClass().getSimpleName();
            final ParameterProviderInitializationContext config = new StandardParameterProviderInitializationContext(identifier, taskName,
                    providerComponent.getLogger(), kerberosConfig, nodeTypeProvider);

            providerComponent.getComponent().initialize(config);

            final Bundle bundle = extensionManager.getBundle(bundleCoordinate);
            verifyControllerServiceReferences(providerComponent.getComponent(), bundle.getClassLoader());

            return providerComponent;
        } catch (final Exception e) {
            throw new ParameterProviderInstantiationException(type, e);
        }
    }

    private <T extends ConfigurableComponent> LoggableComponent<T> createLoggableComponent(Class<T> nodeType) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        final ClassLoader ctxClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final Bundle bundle = extensionManager.getBundle(bundleCoordinate);
            if (bundle == null) {
                throw new IllegalStateException("Unable to find bundle for coordinate " + bundleCoordinate.getCoordinate());
            }

            final ClassLoader detectedClassLoader = extensionManager.createInstanceClassLoader(type, identifier, bundle, classpathUrls == null ? Collections.emptySet() : classpathUrls, true,
                classloaderIsolationKey);
            final Class<?> rawClass = Class.forName(type, true, detectedClassLoader);
            Thread.currentThread().setContextClassLoader(detectedClassLoader);

            final Object extensionInstance = rawClass.newInstance();

            final ComponentLog componentLog = new SimpleProcessLogger(identifier, extensionInstance);
            final TerminationAwareLogger terminationAwareLogger = new TerminationAwareLogger(componentLog);

            final T cast = nodeType.cast(extensionInstance);
            return new LoggableComponent<>(cast, bundleCoordinate, terminationAwareLogger);
        } finally {
            if (ctxClassLoader != null) {
                Thread.currentThread().setContextClassLoader(ctxClassLoader);
            }
        }
    }
}
