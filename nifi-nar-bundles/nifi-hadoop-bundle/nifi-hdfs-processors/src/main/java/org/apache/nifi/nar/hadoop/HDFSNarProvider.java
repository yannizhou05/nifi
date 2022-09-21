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
package org.apache.nifi.nar.hadoop;

import org.apache.nifi.annotation.behavior.RequiresInstanceClassLoading;
import org.apache.nifi.deprecation.log.DeprecationLogger;
import org.apache.nifi.deprecation.log.DeprecationLoggerFactory;
import org.apache.nifi.flow.resource.ImmutableExternalResourceDescriptor;
import org.apache.nifi.flow.resource.NarProviderAdapterInitializationContext;
import org.apache.nifi.flow.resource.hadoop.HDFSExternalResourceProvider;
import org.apache.nifi.nar.NarProvider;
import org.apache.nifi.nar.NarProviderInitializationContext;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.stream.Collectors;

@Deprecated
@RequiresInstanceClassLoading(cloneAncestorResources = true)
public class HDFSNarProvider extends HDFSExternalResourceProvider implements NarProvider {
    private static final DeprecationLogger deprecationLogger = DeprecationLoggerFactory.getLogger(HDFSNarProvider.class);

    private static final String IMPLEMENTATION_PROPERTY = "nifi.nar.library.provider.hdfs.implementation";

    @Override
    public void initialize(final NarProviderInitializationContext context) {
        deprecationLogger.warn("{} should be replaced with HDFSExternalResourceProvider for [{}] in nifi.properties",
                getClass().getSimpleName(),
                IMPLEMENTATION_PROPERTY
        );
        initialize(new NarProviderAdapterInitializationContext(context));
    }

    @Override
    public Collection<String> listNars() throws IOException {
        // Only NARs will be listed due to the filter in {@code HDFSNarProviderInitializationContext}
        return listResources().stream().map(d -> d.getLocation()).collect(Collectors.toList());
    }

    @Override
    public InputStream fetchNarContents(final String location) throws IOException {
        // Nar provider does not support modification time based fetch
        return fetchExternalResource(new ImmutableExternalResourceDescriptor(location, System.currentTimeMillis()));
    }

}
