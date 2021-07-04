/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.graphql.dgs.federation.tracing

import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

internal class DgsContextSupportsHTTPRequestHeadersInterface {

    /**
     * See https://github.com/apollographql/federation-jvm#federated-tracing for more information
     */
    @Test
    fun `An http header can be queried via Apollo Federation tracing interface HTTPRequestHeaders`() {
        val httpHeaders = HttpHeaders()
        httpHeaders.add("apollo-federation-include-trace", "ftv1")
        val context = DgsContext(null, DgsWebMvcRequestData(emptyMap(), httpHeaders))
        Assertions.assertEquals("ftv1", context.getHTTPRequestHeader("apollo-federation-include-trace"))
    }
}
