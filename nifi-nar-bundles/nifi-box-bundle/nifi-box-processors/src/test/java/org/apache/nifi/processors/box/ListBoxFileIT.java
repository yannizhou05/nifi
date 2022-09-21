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
package org.apache.nifi.processors.box;

import com.box.sdk.BoxFolder;
import org.apache.nifi.util.MockFlowFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * See Javadoc {@link AbstractBoxFileIT} for instructions how to run this test.
 */
public class ListBoxFileIT extends AbstractBoxFileIT<ListBoxFile> {
    @BeforeEach
    public void init() throws Exception {
        super.init();
        testRunner.setProperty(ListBoxFile.FOLDER_ID, mainFolderId);
    }

    @Override
    public ListBoxFile createTestSubject() {
        ListBoxFile testSubject = new ListBoxFile();

        return testSubject;
    }

    @Test
    void listFilesFrom3LayerDeepDirectoryTree() throws Exception {
        // GIVEN
        BoxFolder.Info main_sub1 = createFolder("main_sub1", mainFolderId);
        BoxFolder.Info main_sub2 = createFolder("main_sub2", mainFolderId);

        BoxFolder.Info main_sub1_sub1 = createFolder("main_sub1_sub1", main_sub1.getID());

        createFileWithDefaultContent("main_file1", mainFolderId);
        createFileWithDefaultContent("main_file2", mainFolderId);
        createFileWithDefaultContent("main_file3", mainFolderId);

        createFileWithDefaultContent("main_sub1_file1", main_sub1.getID());

        createFileWithDefaultContent("main_sub2_file1", main_sub2.getID());
        createFileWithDefaultContent("main_sub2_file2", main_sub2.getID());

        createFileWithDefaultContent("main_sub1_sub1_file1", main_sub1_sub1.getID());
        createFileWithDefaultContent("main_sub1_sub1_file2", main_sub1_sub1.getID());
        createFileWithDefaultContent("main_sub1_sub1_file3", main_sub1_sub1.getID());

        Set<String> expectedFileNames = new HashSet<>(Arrays.asList(
            "main_file1", "main_file2", "main_file3",
            "main_sub1_file1",
            "main_sub2_file1", "main_sub2_file2",
            "main_sub1_sub1_file1", "main_sub1_sub1_file2", "main_sub1_sub1_file3"
        ));

        // The creation of the files are not (completely) synchronized.
        Thread.sleep(2000);

        // WHEN
        testRunner.run();

        // THEN
        Set<String> actualFileNames = getActualFileNames();

        assertEquals(expectedFileNames, actualFileNames);

        // Next, list a sub folder, non-recursively this time. (Changing these properties will clear the Processor state as well
        //  so all files are eligible for listing again.)

        // GIVEN
        testRunner.clearTransferState();

        expectedFileNames = new HashSet<>(Arrays.asList(
            "main_sub1_file1"
        ));

        // WHEN
        testRunner.setProperty(ListBoxFile.FOLDER_ID, main_sub1.getID());
        testRunner.setProperty(ListBoxFile.RECURSIVE_SEARCH, "false");
        testRunner.run();

        // THEN
        actualFileNames = getActualFileNames();

        assertEquals(expectedFileNames, actualFileNames);
    }

    @Test
    void doNotListTooYoungFilesWhenMinAgeIsSet() throws Exception {
        // GIVEN
        testRunner.setProperty(ListBoxFile.MIN_AGE, "15 s");

        createFileWithDefaultContent("main_file", mainFolderId);

        // Make sure the file 'arrives' and could be listed
        Thread.sleep(5000);

        // WHEN
        testRunner.run();

        // THEN
        Set<String> actualFileNames = getActualFileNames();

        assertEquals(Collections.emptySet(), actualFileNames);

        // Next, wait for another 10+ seconds for MIN_AGE to expire then list again

        // GIVEN
        Thread.sleep(10000);

        Set<String> expectedFileNames = new HashSet<>(Arrays.asList(
            "main_file"
        ));

        // WHEN
        testRunner.run();

        // THEN
        actualFileNames = getActualFileNames();

        assertEquals(expectedFileNames, actualFileNames);
    }

    private Set<String> getActualFileNames() {
        List<MockFlowFile> successFlowFiles = testRunner.getFlowFilesForRelationship(ListBoxFile.REL_SUCCESS);

        Set<String> actualFileNames = successFlowFiles.stream()
            .map(flowFile -> flowFile.getAttribute("filename"))
            .collect(Collectors.toSet());

        return actualFileNames;
    }

}
