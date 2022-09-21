/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.processors.dropbox;

import static java.util.Collections.singletonList;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.DbxUserFilesRequests;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.ListFolderBuilder;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Spliterator;
import java.util.stream.StreamSupport;
import org.apache.nifi.dropbox.credentials.service.DropboxCredentialService;
import org.apache.nifi.json.JsonRecordSetWriter;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.proxy.ProxyConfiguration;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ListDropboxTest {

    public static final String ID_1 = "id:11111";
    public static final String ID_2 = "id:22222";
    public static final String TEST_FOLDER = "/testFolder";
    public static final String FILENAME_1 = "file_name_1";
    public static final String FILENAME_2 = "file_name_2";
    public static final long SIZE = 125;
    public static final long CREATED_TIME = 1659707000;
    public static final String REVISION = "5e4ddb1320676a5c29261";
    public static final boolean IS_RECURSIVE = true;
    public static final long MIN_TIMESTAMP = 1659707000;
    public static final long OLD_CREATED_TIME = 1657375066;
    private TestRunner testRunner;

    @Mock
    private DbxClientV2 mockDropboxClient;

    @Mock
    private DropboxCredentialService credentialService;

    @Mock
    private DbxUserFilesRequests mockDbxUserFilesRequest;

    @Mock
    private ListFolderResult mockListFolderResult;

    @Mock
    private ListFolderBuilder mockListFolderBuilder;

    @BeforeEach
    void setUp() throws Exception {
        ListDropbox testSubject = new ListDropbox() {
            @Override
            public DbxClientV2 getDropboxApiClient(ProcessContext context, ProxyConfiguration proxyConfiguration, String clientId) {
                return mockDropboxClient;
            }

            @Override
            protected List<DropboxFileInfo> performListing(
                    ProcessContext context, Long minTimestamp, ListingMode ignoredListingMode) throws IOException {
                return super.performListing(context, MIN_TIMESTAMP, ListingMode.EXECUTION);
            }
        };

        testRunner = TestRunners.newTestRunner(testSubject);

        mockStandardDropboxCredentialService();

        testRunner.setProperty(ListDropbox.RECURSIVE_SEARCH, Boolean.toString(IS_RECURSIVE));
        testRunner.setProperty(ListDropbox.MIN_AGE, "0 sec");
    }

    @Test
    void testFolderValidity() {
        testRunner.setProperty(ListDropbox.FOLDER, "id:odTlUvbpIEAAAAAAAAABmw");
        testRunner.assertValid();
        testRunner.setProperty(ListDropbox.FOLDER, "/");
        testRunner.assertValid();
        testRunner.setProperty(ListDropbox.FOLDER, "/tempFolder");
        testRunner.assertValid();
        testRunner.setProperty(ListDropbox.FOLDER, "/tempFolder/tempSubFolder");
        testRunner.assertValid();
        testRunner.setProperty(ListDropbox.FOLDER, "/tempFolder/tempSubFolder/");
        testRunner.assertValid();
        testRunner.setProperty(ListDropbox.FOLDER, "tempFolder");
        testRunner.assertNotValid();
        testRunner.setProperty(ListDropbox.FOLDER, "odTlUvbpIEAAAAAAAAABmw");
        testRunner.assertNotValid();
        testRunner.setProperty(ListDropbox.FOLDER, "");
        testRunner.assertNotValid();
    }

    @Test
    void testRootIsListed() throws Exception {
        mockFileListing();

        String folderName = "/";
        testRunner.setProperty(ListDropbox.FOLDER, folderName);

        //root is listed when "" is used in Dropbox API
        when(mockDbxUserFilesRequest.listFolderBuilder("")).thenReturn(mockListFolderBuilder);
        when(mockListFolderResult.getEntries()).thenReturn(singletonList(
                createFileMetadata(FILENAME_1, folderName, ID_1, CREATED_TIME)
        ));

        testRunner.run();

        testRunner.assertAllFlowFilesTransferred(ListDropbox.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = testRunner.getFlowFilesForRelationship(ListDropbox.REL_SUCCESS);
        MockFlowFile ff0 = flowFiles.get(0);
        assertFlowFileAttributes(ff0, folderName);
    }

    @Test
    void testOnlyFilesAreListedFoldersAndShortcutsAreFiltered() throws Exception {
        mockFileListing();

        testRunner.setProperty(ListDropbox.FOLDER, TEST_FOLDER);

        when(mockDbxUserFilesRequest.listFolderBuilder(TEST_FOLDER)).thenReturn(mockListFolderBuilder);
        when(mockListFolderResult.getEntries()).thenReturn(Arrays.asList(
                createFileMetadata(FILENAME_1, TEST_FOLDER, ID_1, CREATED_TIME),
                createFolderMetadata("testFolder1", TEST_FOLDER),
                createFileMetadata(FILENAME_2, TEST_FOLDER, ID_2, CREATED_TIME, false)
        ));

        testRunner.run();

        testRunner.assertAllFlowFilesTransferred(ListDropbox.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = testRunner.getFlowFilesForRelationship(ListDropbox.REL_SUCCESS);
        MockFlowFile ff0 = flowFiles.get(0);
        assertFlowFileAttributes(ff0, TEST_FOLDER);
    }

    @Test
    void testOldItemIsFiltered() throws Exception {
        mockFileListing();

        testRunner.setProperty(ListDropbox.FOLDER, TEST_FOLDER);

        when(mockDbxUserFilesRequest.listFolderBuilder(TEST_FOLDER)).thenReturn(mockListFolderBuilder);
        when(mockListFolderResult.getEntries()).thenReturn(Arrays.asList(
                createFileMetadata(FILENAME_1, TEST_FOLDER, ID_1, CREATED_TIME),
                createFileMetadata(FILENAME_2, TEST_FOLDER, ID_2, OLD_CREATED_TIME)
        ));

        testRunner.run();

        testRunner.assertAllFlowFilesTransferred(ListDropbox.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = testRunner.getFlowFilesForRelationship(ListDropbox.REL_SUCCESS);
        MockFlowFile ff0 = flowFiles.get(0);
        assertFlowFileAttributes(ff0, TEST_FOLDER);
    }

    @Test
    void testRecordWriter() throws Exception {
        mockFileListing();
        mockRecordWriter();

        testRunner.setProperty(ListDropbox.FOLDER, TEST_FOLDER);

        when(mockDbxUserFilesRequest.listFolderBuilder(TEST_FOLDER)).thenReturn(mockListFolderBuilder);
        when(mockListFolderResult.getEntries()).thenReturn(Arrays.asList(
                createFileMetadata(FILENAME_1, TEST_FOLDER, ID_1, CREATED_TIME),
                createFileMetadata(FILENAME_2, TEST_FOLDER, ID_2, CREATED_TIME)
        ));

        testRunner.run();

        testRunner.assertAllFlowFilesTransferred(ListDropbox.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = testRunner.getFlowFilesForRelationship(ListDropbox.REL_SUCCESS);
        MockFlowFile ff0 = flowFiles.get(0);
        List<String> expectedFileNames = Arrays.asList(FILENAME_1, FILENAME_2);
        List<String> actualFileNames = getFilenames(ff0.getContent());

        assertEquals(expectedFileNames, actualFileNames);
    }

    private void assertFlowFileAttributes(MockFlowFile flowFile, String folderName) {
        flowFile.assertAttributeEquals(DropboxFileInfo.ID, ID_1);
        flowFile.assertAttributeEquals(DropboxFileInfo.FILENAME, FILENAME_1);
        flowFile.assertAttributeEquals(DropboxFileInfo.PATH, folderName);
        flowFile.assertAttributeEquals(DropboxFileInfo.TIMESTAMP, Long.toString(CREATED_TIME));
        flowFile.assertAttributeEquals(DropboxFileInfo.SIZE, Long.toString(SIZE));
        flowFile.assertAttributeEquals(DropboxFileInfo.REVISION, REVISION);
    }

    private FileMetadata createFileMetadata(
            String filename,
            String parent,
            String id,
            long createdTime,
            boolean isDownloadable) {
        return FileMetadata.newBuilder(filename, id,
                        new Date(createdTime),
                        new Date(createdTime),
                        REVISION, SIZE)
                .withPathDisplay(parent + "/" + filename)
                .withIsDownloadable(isDownloadable)
                .build();
    }

    private FileMetadata createFileMetadata(
            String filename,
            String parent,
            String id,
            long createdTime) {
        return createFileMetadata(filename, parent, id, createdTime, true);
    }

    private Metadata createFolderMetadata(String folderName, String parent) {
        return FolderMetadata.newBuilder(folderName)
                .withPathDisplay(parent + "/" + folderName)
                .build();
    }

    private void mockStandardDropboxCredentialService() throws Exception {
        String credentialServiceId = "dropbox_credentials";
        when(credentialService.getIdentifier()).thenReturn(credentialServiceId);
        testRunner.addControllerService(credentialServiceId, credentialService);
        testRunner.enableControllerService(credentialService);
        testRunner.setProperty(ListDropbox.CREDENTIAL_SERVICE, credentialServiceId);
    }

    private void mockRecordWriter() throws InitializationException {
        RecordSetWriterFactory recordWriter = new JsonRecordSetWriter();
        testRunner.addControllerService("record_writer", recordWriter);
        testRunner.enableControllerService(recordWriter);
        testRunner.setProperty(ListDropbox.RECORD_WRITER, "record_writer");
    }

    private void mockFileListing() throws DbxException {
        when(mockListFolderBuilder.withRecursive(IS_RECURSIVE)).thenReturn(mockListFolderBuilder);
        when(mockListFolderBuilder.start()).thenReturn(mockListFolderResult);
        when(mockDropboxClient.files()).thenReturn(mockDbxUserFilesRequest);
        when(mockListFolderResult.getHasMore()).thenReturn(false);
    }

    private List<String> getFilenames(String flowFileContent) {
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(flowFileContent);
            return StreamSupport.stream(spliteratorUnknownSize(jsonNode.iterator(), Spliterator.ORDERED), false)
                    .map(node -> node.get("filename").asText())
                    .collect(toList());
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }
}
