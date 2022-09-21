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

import org.apache.commons.io.FileUtils;
import org.apache.nifi.admin.service.AuditService;
import org.apache.nifi.authorization.AbstractPolicyBasedAuthorizer;
import org.apache.nifi.authorization.MockPolicyBasedAuthorizer;
import org.apache.nifi.bundle.Bundle;
import org.apache.nifi.controller.DummyScheduledProcessor;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.repository.FlowFileEventRepository;
import org.apache.nifi.controller.status.history.StatusHistoryRepository;
import org.apache.nifi.encrypt.PropertyEncryptor;
import org.apache.nifi.encrypt.PropertyEncryptorFactory;
import org.apache.nifi.nar.ExtensionDiscoveringManager;
import org.apache.nifi.nar.StandardExtensionDiscoveringManager;
import org.apache.nifi.nar.SystemBundle;
import org.apache.nifi.parameter.Parameter;
import org.apache.nifi.parameter.ParameterContext;
import org.apache.nifi.parameter.ParameterDescriptor;
import org.apache.nifi.parameter.ParameterReferenceManager;
import org.apache.nifi.parameter.StandardParameterContext;
import org.apache.nifi.provenance.MockProvenanceRepository;
import org.apache.nifi.registry.VariableRegistry;
import org.apache.nifi.registry.flow.FlowRegistryClient;
import org.apache.nifi.registry.variable.FileBasedVariableRegistry;
import org.apache.nifi.reporting.BulletinRepository;
import org.apache.nifi.util.NiFiProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.w3c.dom.Document;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StandardFlowSerializerTest {

    private static final String RAW_COMMENTS
            = "<tagName> \"This\" is an ' example with many characters that need to be filtered and escaped \u0002 in it. \u007f \u0086 " + Character.MIN_SURROGATE;
    private static final String SERIALIZED_COMMENTS
            = "&lt;tagName&gt; \"This\" is an ' example with many characters that need to be filtered and escaped  in it. &#127; &#134; ";
    private static final String RAW_VARIABLE_NAME = "Name with \u0001 escape needed";
    private static final String SERIALIZED_VARIABLE_NAME = "Name with  escape needed";
    private static final String RAW_VARIABLE_VALUE = "Value with \u0001 escape needed";
    private static final String SERIALIZED_VARIABLE_VALUE = "Value with  escape needed";
    private static final String RAW_STRING_WITH_EMOJI = "String with \uD83D\uDCA7 droplet emoji";
    private static final String SERIALIZED_STRING_WITH_EMOJI = "String with &#128167; droplet emoji";

    private volatile String propsFile = StandardFlowSerializerTest.class.getResource("/standardflowserializertest.nifi.properties").getFile();

    private FlowController controller;
    private Bundle systemBundle;
    private ExtensionDiscoveringManager extensionManager;
    private StandardFlowSerializer serializer;

    @Before
    public void setUp() throws Exception {
        final FlowFileEventRepository flowFileEventRepo = Mockito.mock(FlowFileEventRepository.class);
        final AuditService auditService = Mockito.mock(AuditService.class);
        final Map<String, String> otherProps = new HashMap<>();
        otherProps.put(NiFiProperties.PROVENANCE_REPO_IMPLEMENTATION_CLASS, MockProvenanceRepository.class.getName());
        otherProps.put("nifi.remote.input.socket.port", "");
        otherProps.put("nifi.remote.input.secure", "");
        final NiFiProperties nifiProperties = NiFiProperties.createBasicNiFiProperties(propsFile, otherProps);
        final PropertyEncryptor encryptor = PropertyEncryptorFactory.getPropertyEncryptor(nifiProperties);

        // use the system bundle
        systemBundle = SystemBundle.create(nifiProperties);
        extensionManager = new StandardExtensionDiscoveringManager();
        extensionManager.discoverExtensions(systemBundle, Collections.emptySet());

        final AbstractPolicyBasedAuthorizer authorizer = new MockPolicyBasedAuthorizer();
        final VariableRegistry variableRegistry = new FileBasedVariableRegistry(nifiProperties.getVariableRegistryPropertiesPaths());

        final BulletinRepository bulletinRepo = Mockito.mock(BulletinRepository.class);
        controller = FlowController.createStandaloneInstance(flowFileEventRepo, nifiProperties, authorizer,
            auditService, encryptor, bulletinRepo, variableRegistry, Mockito.mock(FlowRegistryClient.class), extensionManager, Mockito.mock(StatusHistoryRepository.class));

        serializer = new StandardFlowSerializer();
    }

    @After
    public void after() throws Exception {
        controller.shutdown(true);
        FileUtils.deleteDirectory(new File("./target/standardflowserializertest"));
    }

    private static ParameterContext createParameterContext(final String id, final String name) {
        return new StandardParameterContext.Builder()
                .id(id)
                .name(name)
                .parameterReferenceManager(ParameterReferenceManager.EMPTY)
                .build();
    }

    @Test
    public void testSerializationEscapingAndFiltering() throws Exception {
        final ProcessorNode dummy = controller.getFlowManager().createProcessor(DummyScheduledProcessor.class.getName(),
            UUID.randomUUID().toString(), systemBundle.getBundleDetails().getCoordinate());

        dummy.setComments(RAW_COMMENTS);
        controller.getFlowManager().getRootGroup().addProcessor(dummy);

        final ParameterContext parameterContext = createParameterContext("context", "Context");
        final ParameterContext referencedContext = createParameterContext("referenced-context", "Referenced Context");
        final ParameterContext referencedContext2 = createParameterContext("referenced-context-2", "Referenced Context 2");
        final Map<String, Parameter> parameters = new HashMap<>();
        final ParameterDescriptor parameterDescriptor = new ParameterDescriptor.Builder().name("foo").sensitive(true).build();
        parameters.put("foo", new Parameter(parameterDescriptor, "value"));
        parameterContext.setInheritedParameterContexts(Arrays.asList(referencedContext, referencedContext2));
        parameterContext.setParameters(parameters);

        controller.getFlowManager().getParameterContextManager().addParameterContext(parameterContext);
        controller.getFlowManager().getParameterContextManager().addParameterContext(referencedContext);
        controller.getFlowManager().getParameterContextManager().addParameterContext(referencedContext2);

        controller.getFlowManager().getRootGroup().setParameterContext(parameterContext);
        controller.getFlowManager().getRootGroup().setVariables(Collections.singletonMap(RAW_VARIABLE_NAME, RAW_VARIABLE_VALUE));
        controller.getFlowManager().getRootGroup().setParameterContext(parameterContext);

        // serialize the controller
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        final Document doc = serializer.transform(controller, ScheduledStateLookup.IDENTITY_LOOKUP);
        serializer.serialize(doc, os);

        // verify the results contain the serialized string
        final String serializedFlow = os.toString(StandardCharsets.UTF_8.name());
        assertTrue(serializedFlow.contains(SERIALIZED_COMMENTS));
        assertFalse(serializedFlow.contains(RAW_COMMENTS));
        assertTrue(serializedFlow.contains(SERIALIZED_VARIABLE_NAME));
        assertFalse(serializedFlow.contains(RAW_VARIABLE_NAME));
        assertTrue(serializedFlow.contains(SERIALIZED_VARIABLE_VALUE));
        assertFalse(serializedFlow.contains(RAW_VARIABLE_VALUE));
        assertFalse(serializedFlow.contains("\u0001"));
        assertTrue(serializedFlow.contains("<inheritedParameterContextId>referenced-context</inheritedParameterContextId>"));
    }

    @Test
    public void testSerializationEmoji() throws Exception {
        final ProcessorNode dummy = controller.getFlowManager().createProcessor(DummyScheduledProcessor.class.getName(),
                UUID.randomUUID().toString(), systemBundle.getBundleDetails().getCoordinate());

        dummy.setName(RAW_STRING_WITH_EMOJI);
        controller.getFlowManager().getRootGroup().addProcessor(dummy);

        controller.getFlowManager().getRootGroup().setVariables(Collections.singletonMap(RAW_STRING_WITH_EMOJI, RAW_STRING_WITH_EMOJI));

        // serialize the controller
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        final Document doc = serializer.transform(controller, ScheduledStateLookup.IDENTITY_LOOKUP);
        serializer.serialize(doc, os);

        // verify the results contain the serialized string
        final String serializedFlow = os.toString(StandardCharsets.UTF_8.name());
        assertTrue(serializedFlow.contains(SERIALIZED_STRING_WITH_EMOJI));
        assertFalse(serializedFlow.contains(RAW_STRING_WITH_EMOJI));
    }
}
