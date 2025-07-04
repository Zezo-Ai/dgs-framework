/*
 * Copyright 2025 Netflix, Inc.
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

package com.netflix.graphql.dgs.springgraphql.autoconfig

import io.mockk.mockk
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

class DgsSpringGraphQLEnvironmentPostProcessorTest {
    val application = mockk<SpringApplication>()
    lateinit var env: MockEnvironment

    @BeforeEach
    fun setup() {
        env = MockEnvironment()
    }

    @Test
    fun `Default for graphiql-enabled`() {
        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("spring.graphql.graphiql.enabled")).isEqualTo("true")
    }

    @Test
    fun `DGS setting should propagate to spring graphql for graphiql-enabled`() {
        env.setProperty("dgs.graphql.graphiql.enabled", "false")

        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("spring.graphql.graphiql.enabled")).isEqualTo("false")
    }

    @Test
    fun `Default for graphiql-path`() {
        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("spring.graphql.graphiql.path")).isEqualTo("/graphiql")
    }

    @Test
    fun `Default for graphql-path`() {
        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("spring.graphql.http.path")).isEqualTo("/graphql")
    }

    @Test
    fun `DGS setting should propagate to spring graphql for graphiql-path`() {
        env.setProperty("dgs.graphql.graphiql.path", "/somepath")

        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("spring.graphql.graphiql.path")).isEqualTo("/somepath")
    }

    @Test
    fun `DGS setting should propagate to spring graphql for graphql-path`() {
        env.setProperty("dgs.graphql.path", "/somepath")

        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("spring.graphql.http.path")).isEqualTo("/somepath")
    }

    @Test
    fun `DGS setting should propagate to spring graphql for introspection-enabled`() {
        env.setProperty("dgs.graphql.introspection.enabled", "false")

        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("spring.graphql.schema.introspection.enabled")).isEqualTo("false")
    }

    @Test
    fun `Default for websocket-connection-timeout`() {
        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("spring.graphql.websocket.connection-init-timeout")).isEqualTo("10s")
    }

    @Test
    fun `DGS setting should propagate to spring graphql for websocket-connection-timeout`() {
        env.setProperty("dgs.graphql.websocket.connection-init-timeout", "30s")

        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("spring.graphql.websocket.connection-init-timeout")).isEqualTo("30s")
    }

    @Test
    fun `DGS virtual threads should by default be enabled when Spring virtual threads are enabled`() {
        env.setProperty("spring.threads.virtual.enabled", "true")

        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("dgs.graphql.virtualthreads.enabled")).isEqualTo("true")
    }

    @Test
    fun `DGS virtual threads should be disabled when explicitly set to false`() {
        env.setProperty("spring.threads.virtual.enabled", "true")
        env.setProperty("dgs.graphql.virtualthreads.enabled", "false")

        DgsSpringGraphQLEnvironmentPostProcessor().postProcessEnvironment(env, application)

        Assertions.assertThat(env.getProperty("dgs.graphql.virtualthreads.enabled")).isEqualTo("false")
    }
}
