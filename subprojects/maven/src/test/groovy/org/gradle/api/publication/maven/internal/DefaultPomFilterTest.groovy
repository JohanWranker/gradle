/*
 * Copyright 2007-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.publication.maven.internal

import org.gradle.api.artifacts.maven.MavenPom
import org.gradle.api.artifacts.maven.PublishFilter
import spock.lang.Specification

class DefaultPomFilterTest extends Specification {
    private static final String TEST_NAME = "TEST_NAME"

    private MavenPom mavenPomMock = Mock()
    private PublishFilter publishFilterMock = Mock()

    private DefaultPomFilter pomFilter = new DefaultPomFilter(TEST_NAME, mavenPomMock, publishFilterMock)

    def testGetName() {
        expect:
        pomFilter.name == TEST_NAME
        pomFilter.pomTemplate == mavenPomMock
        pomFilter.filter == publishFilterMock
    }
}
