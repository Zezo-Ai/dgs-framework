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

package com.netflix.graphql.dgs.metrics.micrometer

import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataLoader
import com.netflix.graphql.dgs.DgsDataLoaderRegistryConsumer
import com.netflix.graphql.dgs.DgsEnableDataFetcherInstrumentation
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.DgsTypeDefinitionRegistry
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.exceptions.DefaultDataFetcherExceptionHandler
import com.netflix.graphql.dgs.exceptions.DgsBadRequestException
import com.netflix.graphql.dgs.exceptions.DgsException
import com.netflix.graphql.dgs.metrics.micrometer.tagging.DgsContextualTagCustomizer
import com.netflix.graphql.dgs.metrics.micrometer.tagging.DgsExecutionTagCustomizer
import com.netflix.graphql.dgs.metrics.micrometer.tagging.DgsFieldFetchTagCustomizer
import com.netflix.graphql.dgs.metrics.micrometer.utils.QuerySignatureRepository
import com.netflix.graphql.types.errors.ErrorDetail
import com.netflix.graphql.types.errors.ErrorType
import com.netflix.graphql.types.errors.TypedGraphQLError
import graphql.GraphQLError
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.cumulative.CumulativeCounter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.dataloader.BatchLoader
import org.dataloader.DataLoaderRegistry
import org.dataloader.MappedBatchLoader
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.MediaType
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.util.CollectionUtils
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

@SpringBootTest(
    properties = ["${DgsGraphQLMicrometerAutoConfiguration.AUTO_CONF_QUERY_SIG_PREFIX}.enabled=false", "dgs.graphql.apq.enabled:true"],
)
@EnableAutoConfiguration
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class MicrometerServletSmokeTest {
    companion object {
        private val logger = LoggerFactory.getLogger(MicrometerServletSmokeTest::class.java)

        private val MOCKED_QUERY_SIGNATURE =
            QuerySignatureRepository.QuerySignature("some-signature", "some-hash")
    }

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var mvc: MockMvc

    @BeforeEach
    fun resetMeterRegistry() {
        meterRegistry.clear()
    }

    @Test
    fun `Assert the type of MeterRegistry we require for the test`() {
        assertThat(meterRegistry).isNotNull
        assertThat(meterRegistry).isExactlyInstanceOf(SimpleMeterRegistry::class.java)
    }

    @Test
    fun `Metrics for a successful query`() {
        mvc
            .perform(
                // Note that the query below uses an aliased field, aliasing `ping` to `op_name`.
                // We will also assert that the tag reflected by the metric is not affected by the alias.
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "query my_op_1{ping}" }"""),
            ).andExpect(status().isOk)
            .andExpect(content().json("""{"data":{"ping":"pong"}}""", false))

        val meters = fetchMeters()

        assertThat(meters).containsOnlyKeys("gql.query", "gql.resolver")

        assertThat(meters["gql.query"]).isNotNull.hasSize(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("outcome", "success")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "my_op_1")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )

        assertThat(meters["gql.resolver"]).isNotNull.hasSize(1)
        assertThat(meters["gql.resolver"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("field-fetch-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.field", "Query.ping")
                    .and("outcome", "success")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "my_op_1")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )
    }

    @Test
    fun `Metrics for a successful mutation`() {
        mvc
            .perform(
                // Note that the query below uses an aliased field, aliasing `ping` to `op_name`.
                // We will also assert that the tag reflected by the metric is not affected by the alias.
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": " mutation my_op_1{buzz}" }""".trimMargin()),
            ).andExpect(status().isOk)
            .andExpect(content().json("""{"data":{"buzz":"buzz"}}""", false))

        val meters = fetchMeters()

        assertThat(meters).containsOnlyKeys("gql.query", "gql.resolver")

        assertThat(meters["gql.query"]).isNotNull.hasSize(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("outcome", "success")
                    .and("gql.operation", "MUTATION")
                    .and("gql.operation.name", "my_op_1")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )

        assertThat(meters["gql.resolver"]).isNotNull.hasSize(1)
        assertThat(meters["gql.resolver"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("field-fetch-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.field", "Mutation.buzz")
                    .and("outcome", "success")
                    .and("gql.operation", "MUTATION")
                    .and("gql.operation.name", "my_op_1")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )
    }

    @Test
    fun `Metrics for a successful request with explicit operation name`() {
        mvc
            .perform(
                // Note that the query below uses an aliased field, aliasing `ping` to `op_name`.
                // We will also assert that the tag reflected by the metric is not affected by the alias.
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    | {
                    |     "query": "mutation my_m_1{buzz} query my_q_1{ping}",
                    |     "operationName": "my_q_1"
                    | }
                        """.trimMargin(),
                    ),
            ).andExpect(status().isOk)
            .andExpect(content().json("""{"data":{"ping":"pong"}}""", false))

        val meters = fetchMeters()

        assertThat(meters).containsOnlyKeys("gql.query", "gql.resolver")

        assertThat(meters["gql.query"]).isNotNull.hasSize(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("outcome", "success")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "my_q_1")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )

        assertThat(meters["gql.resolver"]).isNotNull.hasSize(1)
        assertThat(meters["gql.resolver"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("field-fetch-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.field", "Query.ping")
                    .and("outcome", "success")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "my_q_1")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )
    }

    @Test
    fun `Assert metrics for a syntax error`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "fail" }"""),
            ).andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                    |   { "errors": [
                    |       {
                    |           "message": "Invalid syntax with offending token 'fail' at line 1 column 1",
                    |           "locations": [
                    |               {
                    |                   "line":1,
                    |                   "column":1
                    |               }
                    |           ],
                    |           "extensions": { "classification":"InvalidSyntax" }
                    |        }
                    |     ]
                    |   }
                    """.trimMargin(),
                    false,
                ),
            )
        val meters = fetchMeters("gql.")

        assertThat(meters).containsOnlyKeys("gql.error", "gql.query")

        assertThat(meters["gql.error"]).isNotNull.hasSize(1)
        assertThat((meters["gql.error"]?.first() as CumulativeCounter).count()).isEqualTo(1.0)
        assertThat(meters["gql.error"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.operation", "none")
                    .and("gql.operation.name", "anonymous")
                    .and("gql.query.complexity", "none")
                    .and("gql.query.sig.hash", "none")
                    .and("gql.errorDetail", "none")
                    .and("gql.errorCode", "BAD_REQUEST")
                    .and("gql.path", "[]")
                    .and("outcome", "failure"),
            )

        assertThat(meters["gql.query"]).isNotNull.hasSize(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("outcome", "failure")
                    .and("gql.operation", "none")
                    .and("gql.operation.name", "anonymous")
                    .and("gql.query.complexity", "none")
                    .and("gql.query.sig.hash", "none"),
            )
    }

    @Test
    fun `Assert metrics for a persisted query not found error`() {
        val uriBuilder =
            MockMvcRequestBuilders
                .post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                       |{
                       |    "extensions":{
                       |        "persistedQuery":{
                       |            "version":1,
                       |            "sha256Hash":"ecf4edb46db40b5132295c0291d62fb65d6759a9eedfa4d5d612dd5ec54a6b38"
                       |        }
                       |    }
                       | }
                       |
                    """.trimMargin(),
                )
        mvc
            .perform(uriBuilder)
            .andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                    |{
                    |   "errors":[
                    |     {
                    |       "message":"PersistedQueryNotFound",
                    |       "locations":[],
                    |       "extensions":{
                    |         "persistedQueryId":"ecf4edb46db40b5132295c0291d62fb65d6759a9eedfa4d5d612dd5ec54a6b38",
                    |         "generatedBy":"graphql-java",
                    |         "classification":"PersistedQueryNotFound"
                    |       }
                    |     }
                    |   ]
                    | }
                    |
                    """.trimMargin(),
                ),
            )

        val meters = fetchMeters("gql.")

        assertThat(meters).containsOnlyKeys("gql.query", "gql.persistedQueryNotFound")

        assertThat(meters["gql.query"]).isNotNull.hasSize(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.APQ.name),
            )

        assertThat(meters["gql.persistedQueryNotFound"]).isNotNull.hasSize(1)
        assertThat((meters["gql.persistedQueryNotFound"]?.first() as CumulativeCounter).count()).isEqualTo(1.0)
        assertThat(meters["gql.persistedQueryNotFound"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("gql.persistedQueryId", "ecf4edb46db40b5132295c0291d62fb65d6759a9eedfa4d5d612dd5ec54a6b38"),
            )
    }

    @Test
    fun `Assert metrics for a full persisted query `() {
        val uriBuilder =
            MockMvcRequestBuilders
                .post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                       |{
                       |    "query": "{__typename}",
                       |    "extensions":{
                       |        "persistedQuery":{
                       |            "version":1,
                       |            "sha256Hash":"ecf4edb46db40b5132295c0291d62fb65d6759a9eedfa4d5d612dd5ec54a6b38"
                       |        }
                       |    }
                       | }
                       |
                    """.trimMargin(),
                )
        mvc
            .perform(uriBuilder)
            .andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                    | {
                    |    "data": {
                    |        "__typename":"Query"
                    |    }
                    | }
                    |
                    """.trimMargin(),
                ),
            )

        val meters = fetchMeters("gql.")

        assertThat(meters).containsKeys("gql.query")

        assertThat(meters["gql.query"]).isNotNull.hasSize(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.FULL_APQ.name),
            )
    }

    @Test
    fun `Metrics for a query with a data fetcher with disabled instrumentation`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "{someTrivialThings}" }"""),
            ).andExpect(status().isOk)
            .andExpect(content().json("""{"data":{"someTrivialThings":"some insignificance"}}""", false))

        val meters = fetchMeters()

        assertThat(meters).containsOnlyKeys("gql.query")

        assertThat(meters["gql.query"]).isNotNull.hasSize(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("outcome", "success")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-someTrivialThings")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash)
                    .and("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.NOT_APQ.name),
            )
    }

    @Test
    fun `Assert metrics for a bad input error`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "{ hello }" }"""),
            ).andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                    |   { "errors": [
                    |       { 
                    |           "message": "Validation error (MissingFieldArgument@[hello]) : Missing field argument 'name'",
                    |           "locations": [ 
                    |               {
                    |                   "line":1,
                    |                   "column":3
                    |               }
                    |           ],
                    |           "extensions": { "classification":"ValidationError" }
                    |       }
                    |     ]
                    |   }
                    """.trimMargin(),
                    false,
                ),
            )
        val meters = fetchMeters("gql.")

        assertThat(meters).containsOnlyKeys("gql.error", "gql.query")

        assertThat(meters["gql.error"]).isNotNull.hasSize(1)
        assertThat((meters["gql.error"]?.first() as CumulativeCounter).count()).isEqualTo(1.0)
        assertThat(meters["gql.error"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.operation", "none")
                    .and("gql.operation.name", "anonymous")
                    .and("gql.query.complexity", "none")
                    .and("gql.query.sig.hash", "none")
                    .and("gql.errorDetail", "INVALID_ARGUMENT")
                    .and("gql.errorCode", "BAD_REQUEST")
                    .and("gql.path", "[hello]")
                    .and("outcome", "failure"),
            )

        assertThat(meters["gql.query"]).isNotNull.hasSize(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("outcome", "failure")
                    .and("gql.operation", "none")
                    .and("gql.operation.name", "anonymous")
                    .and("gql.query.complexity", "none")
                    .and("gql.query.sig.hash", "none")
                    .and("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.NOT_APQ.name),
            )
    }

    @Test
    fun `Assert metrics for internal error`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "{triggerInternalFailure}" }"""),
            ).andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                       |{
                       |    "errors":[
                       |      {
                       |       "message":"java.lang.IllegalStateException: Exception triggered.",
                       |       "locations": [{"line":1, "column":2}],
                       |       "path": ["triggerInternalFailure"],
                       |       "extensions": {"errorType":"INTERNAL"}
                       |     }
                       |    ],
                       |    "data":{"triggerInternalFailure":null}
                       |}
                    """.trimMargin(),
                    false,
                ),
            )

        val meters = fetchMeters("gql.")

        assertThat(meters).containsOnlyKeys("gql.error", "gql.query", "gql.resolver")

        logMeters(meters["gql.error"])
        assertThat(meters["gql.error"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat((meters["gql.error"]?.first() as CumulativeCounter).count()).isEqualTo(1.0)
        assertThat(meters["gql.error"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.errorCode", "INTERNAL")
                    .and("gql.errorDetail", "none")
                    .and("gql.path", "[triggerInternalFailure]")
                    .and("outcome", "failure")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-triggerInternalFailure")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )

        logMeters(meters["gql.query"])
        assertThat(meters["gql.query"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("outcome", "failure")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-triggerInternalFailure")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash)
                    .and("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.NOT_APQ.name),
            )

        logMeters(meters["gql.resolver"])
        assertThat(meters["gql.resolver"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.resolver"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("field-fetch-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.field", "Query.triggerInternalFailure")
                    .and("outcome", "failure")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-triggerInternalFailure")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )
    }

    @Test
    fun `Assert metrics for a bad-request error`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "{triggerBadRequestFailure}" }"""),
            ).andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                        |{
                        |   "errors":[
                        |      {"message":"Exception triggered.",
                        |          "path":["triggerBadRequestFailure"],
                        |          "extensions":{"errorType":"BAD_REQUEST"}}
                        |   ],
                        |   "data":{"triggerBadRequestFailure":null}
                        |}
                    """.trimMargin(),
                    false,
                ),
            )

        val meters = fetchMeters("gql.")

        assertThat(meters).containsOnlyKeys("gql.error", "gql.query", "gql.resolver")

        assertThat(meters["gql.error"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat((meters["gql.error"]?.first() as CumulativeCounter).count()).isEqualTo(1.0)
        assertThat(meters["gql.error"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.errorCode", "BAD_REQUEST")
                    .and("gql.errorDetail", "none")
                    .and("gql.path", "[triggerBadRequestFailure]")
                    .and("outcome", "failure")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-triggerBadRequestFailure")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )

        assertThat(meters["gql.query"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("outcome", "failure")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-triggerBadRequestFailure")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash)
                    .and("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.NOT_APQ.name),
            )

        assertThat(meters["gql.resolver"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.resolver"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("field-fetch-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.field", "Query.triggerBadRequestFailure")
                    .and("outcome", "failure")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-triggerBadRequestFailure")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )
    }

    @Test
    fun `Assert metrics for a successful async response with errors`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "{triggerSuccessfulRequestWithErrorAsync}" }"""),
            ).andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                        |{
                        |   "errors":[
                        |      {"message":"Exception triggered."}
                        |   ],
                        |   "data":{"triggerSuccessfulRequestWithErrorAsync":"Some data..."}
                        |}
                    """.trimMargin(),
                    false,
                ),
            )

        val meters = fetchMeters("gql.")

        assertThat(meters).containsOnlyKeys("gql.error", "gql.query", "gql.resolver")

        assertThat(meters["gql.error"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat((meters["gql.error"]?.first() as CumulativeCounter).count()).isEqualTo(1.0)
        assertThat(meters["gql.error"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("outcome", "failure"),
            )

        assertThat(meters["gql.query"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("outcome", "failure"),
            )

        assertThat(meters["gql.resolver"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.resolver"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("outcome", "failure"),
            )
    }

    @Test
    fun `Assert metrics for a successful sync response with errors`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "{triggerSuccessfulRequestWithErrorSync}" }"""),
            ).andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                        |{
                        |   "errors":[
                        |      {"message":"Exception triggered."}
                        |   ],
                        |   "data":{"triggerSuccessfulRequestWithErrorSync":"Some data..."}
                        |}
                    """.trimMargin(),
                    false,
                ),
            )

        val meters = fetchMeters("gql.")

        assertThat(meters).containsOnlyKeys("gql.error", "gql.query", "gql.resolver")

        assertThat(meters["gql.error"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat((meters["gql.error"]?.first() as CumulativeCounter).count()).isEqualTo(1.0)
        assertThat(meters["gql.error"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("outcome", "failure"),
            )

        assertThat(meters["gql.query"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("outcome", "failure"),
            )

        assertThat(meters["gql.resolver"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.resolver"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("outcome", "failure"),
            )
    }

    @Test
    fun `Assert metrics for custom error`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "{triggerCustomFailure}" }"""),
            ).andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                    |{
                    |   "errors":[
                    |       { 
                    |           "message":"Exception triggered.",
                    |           "path":["triggerCustomFailure"],
                    |           "extensions":{"errorType":"UNAVAILABLE","errorDetail":"ENHANCE_YOUR_CALM"}
                    |       }
                    |   ],
                    |   "data":{"triggerCustomFailure":null}
                    |}
                    """.trimMargin(),
                    false,
                ),
            )

        val meters = fetchMeters()

        assertThat(meters).containsOnlyKeys("gql.error", "gql.query", "gql.resolver")

        assertThat(meters["gql.error"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat((meters["gql.error"]?.first() as CumulativeCounter).count()).isEqualTo(1.0)
        assertThat(meters["gql.error"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.errorCode", "UNAVAILABLE")
                    .and("gql.errorDetail", "ENHANCE_YOUR_CALM")
                    .and("gql.path", "[triggerCustomFailure]")
                    .and("outcome", "failure")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-triggerCustomFailure")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )

        assertThat(meters["gql.query"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.query"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("execution-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("outcome", "failure")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-triggerCustomFailure")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash)
                    .and("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.NOT_APQ.name),
            )

        assertThat(meters["gql.resolver"]).isNotNull.hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.resolver"]?.first()?.id?.tags)
            .containsAll(
                Tags
                    .of("field-fetch-tag", "foo")
                    .and("contextual-tag", "foo")
                    .and("gql.field", "Query.triggerCustomFailure")
                    .and("outcome", "failure")
                    .and("gql.operation", "QUERY")
                    .and("gql.operation.name", "-triggerCustomFailure")
                    .and("gql.query.complexity", "5")
                    .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash),
            )
    }

    @Test
    fun `Assert metrics for request with multiple errors`() {
        mvc
            .perform(
                MockMvcRequestBuilders
                    .post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "query": "{ triggerInternalFailure triggerBadRequestFailure triggerCustomFailure }" }"""),
            ).andExpect(status().isOk)
            .andExpect(
                content().json(
                    """
                    | {"errors":[
                    |    {"message":"java.lang.IllegalStateException: Exception triggered.","locations":[{"line":1,"column":3}],"path":["triggerInternalFailure"],"extensions":{"errorType":"INTERNAL"}},
                    |    {"message":"Exception triggered.","path":["triggerBadRequestFailure"],"extensions":{"class":"com.netflix.graphql.dgs.exceptions.DgsBadRequestException","errorType":"BAD_REQUEST"}},
                    |    {"message":"Exception triggered.","path":["triggerCustomFailure"],"extensions":{"errorType":"UNAVAILABLE","errorDetail":"ENHANCE_YOUR_CALM"}}
                    |  ],
                    |  "data":{"triggerInternalFailure":null,"triggerBadRequestFailure":null,"triggerCustomFailure":null}}
                    """.trimMargin(),
                    false,
                ),
            )

        val meters = fetchMeters()

        assertThat(meters).containsOnlyKeys("gql.error", "gql.query", "gql.resolver")

        assertThat(meters["gql.query"]).hasSizeGreaterThanOrEqualTo(1)
        assertThat(meters["gql.error"]).hasSizeGreaterThanOrEqualTo(3)
        assertThat(meters["gql.resolver"]).hasSizeGreaterThanOrEqualTo(3)

        val errors = meters.getValue("gql.error").map { it.id.tags }
        assertThat(errors).contains(
            Tags
                .of("execution-tag", "foo")
                .and("contextual-tag", "foo")
                .and("gql.errorCode", "BAD_REQUEST")
                .and("gql.errorDetail", "none")
                .and("gql.path", "[triggerBadRequestFailure]")
                .and("outcome", "failure")
                .and("gql.operation", "QUERY")
                .and("gql.operation.name", "-triggerInternalFailure")
                .and("gql.query.complexity", "5")
                .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash)
                .and("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.NOT_APQ.name)
                .toList(),
            Tags
                .of("execution-tag", "foo")
                .and("contextual-tag", "foo")
                .and("gql.errorCode", "INTERNAL")
                .and("gql.errorDetail", "none")
                .and("gql.path", "[triggerInternalFailure]")
                .and("outcome", "failure")
                .and("gql.operation", "QUERY")
                .and("gql.operation.name", "-triggerInternalFailure")
                .and("gql.query.complexity", "5")
                .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash)
                .and("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.NOT_APQ.name)
                .toList(),
            Tags
                .of("execution-tag", "foo")
                .and("contextual-tag", "foo")
                .and("gql.errorCode", "UNAVAILABLE")
                .and("gql.errorDetail", "ENHANCE_YOUR_CALM")
                .and("gql.path", "[triggerCustomFailure]")
                .and("outcome", "failure")
                .and("gql.operation", "QUERY")
                .and("gql.operation.name", "-triggerInternalFailure")
                .and("gql.query.complexity", "5")
                .and("gql.query.sig.hash", MOCKED_QUERY_SIGNATURE.hash)
                .and("gql.persistedQueryType", DgsGraphQLMetricsInstrumentation.PersistedQueryType.NOT_APQ.name)
                .toList(),
        )
    }

    private fun fetchMeters(prefix: String = "gql."): Map<String, List<Meter>> =
        meterRegistry.meters
            .asSequence()
            .filter { it.id.name.startsWith(prefix) }
            .groupBy { it.id.name }

    private fun logMeters(meters: Collection<Meter>?) {
        if (CollectionUtils.isEmpty(meters)) {
            logger.info("No meters found.")
            return
        }
        val meterData =
            meters?.map { it.id }?.joinToString(",\n", "{\n", "\n}") { id: Meter.Id ->
                """
            |Name: ${id.name}
            |Tags:
            |   ${id.tags.joinToString(",\n\t", "\t") { tag: Tag? -> "[${tag?.key} : ${tag?.value}]" }}
                """.trimMargin()
            }
        logger.info("Meters:\n{}", meterData)
    }

    @TestConfiguration(proxyBeanMethods = false)
    open class LocalTestConfiguration {
        @Bean
        open fun testMeterRegistry(): MeterRegistry = SimpleMeterRegistry()

        @Bean
        open fun querySignatureRepository(): QuerySignatureRepository =
            QuerySignatureRepository {
                _,
                _,
                ->
                Optional.of(MOCKED_QUERY_SIGNATURE)
            }

        @Bean
        open fun contextualTagProvider(): DgsContextualTagCustomizer = DgsContextualTagCustomizer { Tags.of("contextual-tag", "foo") }

        @Bean
        open fun executionTagCustomizer(): DgsExecutionTagCustomizer =
            DgsExecutionTagCustomizer {
                _,
                _,
                _,
                _,
                ->
                Tags.of("execution-tag", "foo")
            }

        @Bean
        open fun fieldFetchTagCustomizer(): DgsFieldFetchTagCustomizer =
            DgsFieldFetchTagCustomizer {
                _,
                _,
                _,
                ->
                Tags.of("field-fetch-tag", "foo")
            }
    }

    @SpringBootApplication(proxyBeanMethods = false, scanBasePackages = [])
    @ComponentScan(
        useDefaultFilters = false,
        includeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [])],
    )
    @SuppressWarnings("unused")
    open class LocalApp {
        @DgsComponent
        class ExampleImplementation {
            @DgsTypeDefinitionRegistry
            fun typeDefinitionRegistry(): TypeDefinitionRegistry {
                val schemaParser = SchemaParser()

                val gqlSchema =
                    """
                |type Query{
                |    ping:String
                |    someTrivialThings: String 
                |    hello(name: String!): String 
                |    transform(input:[String]): [StringTransformation]
                |    triggerInternalFailure: String
                |    triggerBadRequestFailure:String
                |    triggerCustomFailure: String
                |    triggerSuccessfulRequestWithErrorAsync:String
                |    triggerSuccessfulRequestWithErrorSync:String
                |}
                |
                |type Mutation{
                |    buzz:String
                |}
                |
                |type StringTransformation {
                |    index: Int
                |    value: String
                |    upperCased: String
                |    reversed: String
                |}
                    """.trimMargin()
                return schemaParser.parse(gqlSchema)
            }

            @DgsQuery
            fun ping(): String = "pong"

            @DgsQuery
            fun hello(
                @InputArgument name: String,
            ): String = "Hello $name"

            @DgsQuery
            @DgsEnableDataFetcherInstrumentation(false)
            fun someTrivialThings(): String = "some insignificance"

            @DgsMutation
            fun buzz(): String = "buzz"

            @DgsQuery
            fun transform(
                @InputArgument input: List<String>,
            ): List<Map<String, String>> = input.mapIndexed { i, v -> mapOf("index" to "$i", "value" to v) }

            @DgsQuery
            fun triggerInternalFailure(): String = throw IllegalStateException("Exception triggered.")

            @DgsQuery
            fun triggerBadRequestFailure(): String = throw DgsBadRequestException("Exception triggered.")

            @DgsQuery
            fun triggerSuccessfulRequestWithErrorAsync(): CompletableFuture<DataFetcherResult<String>> =
                CompletableFuture.supplyAsync {
                    DataFetcherResult
                        .newResult<String>()
                        .data("Some data...")
                        .error(TypedGraphQLError("Exception triggered.", null, null, null, null))
                        .build()
                }

            @DgsQuery
            fun triggerSuccessfulRequestWithErrorSync(): DataFetcherResult<String> =
                DataFetcherResult
                    .newResult<String>()
                    .data("Some data...")
                    .error(TypedGraphQLError("Exception triggered.", null, null, null, null))
                    .build()

            @DgsQuery
            fun triggerCustomFailure(): String = throw CustomException("Exception triggered.")

            @DgsData(parentType = "StringTransformation")
            fun upperCased(dfe: DataFetchingEnvironment): CompletableFuture<String>? {
                val dataLoader =
                    dfe.getDataLoader<String, String>("upperCaseLoader")
                        ?: throw AssertionError("upperCaseLoader not found")
                val input = dfe.getSource<Map<String, String>>().orEmpty()
                return dataLoader.load(input.getOrDefault("value", ""))
            }

            @DgsData(parentType = "StringTransformation")
            fun reversed(dfe: DataFetchingEnvironment): CompletableFuture<String>? {
                val dataLoader =
                    dfe.getDataLoader<String, String>("reverser")
                        ?: throw AssertionError("reverser not found")
                val input = dfe.getSource<Map<String, String>>().orEmpty()
                return dataLoader.load(input.getOrDefault("value", ""))
            }

            @DgsDataLoader(name = "upperCaseLoader")
            var batchLoader =
                BatchLoader { keys: List<String> ->
                    CompletableFuture.supplyAsync { keys.map { it.uppercase() } }
                }

            @DgsDataLoader(name = "reverser")
            class ReverseStringDataLoader(
                @Qualifier("dataLoaderTaskExecutor") private val dataLoaderTaskExecutor: Executor,
            ) : MappedBatchLoader<String, String>,
                DgsDataLoaderRegistryConsumer {
                var dataLoaderRegistry: Optional<DataLoaderRegistry> = Optional.empty()

                override fun load(keys: Set<String>): CompletionStage<Map<String, String>> =
                    CompletableFuture.supplyAsync(
                        { keys.associateWith { it.reversed() } },
                        dataLoaderTaskExecutor,
                    )

                override fun setDataLoaderRegistry(dataLoaderRegistry: DataLoaderRegistry) {
                    this.dataLoaderRegistry = Optional.of(dataLoaderRegistry)
                }
            }
        }

        @Bean
        open fun customDataFetchingExceptionHandler(): DataFetcherExceptionHandler = CustomDataFetchingExceptionHandler()

        @Bean
        open fun dataLoaderTaskExecutor(): Executor {
            val executor = ThreadPoolTaskExecutor()
            executor.corePoolSize = 1
            executor.maxPoolSize = 1
            executor.setThreadNamePrefix("${MicrometerServletSmokeTest::class.java.simpleName}-test-")
            executor.queueCapacity = 10
            executor.initialize()
            return executor
        }
    }

    class CustomDataFetchingExceptionHandler : DataFetcherExceptionHandler {
        private val defaultHandler: DefaultDataFetcherExceptionHandler = DefaultDataFetcherExceptionHandler()

        override fun handleException(
            handlerParameters: DataFetcherExceptionHandlerParameters,
        ): CompletableFuture<DataFetcherExceptionHandlerResult> =
            if (handlerParameters.exception is CustomException) {
                val exception = handlerParameters.exception
                val graphqlError: GraphQLError =
                    TypedGraphQLError
                        .newBuilder()
                        .errorDetail(ErrorDetail.Common.ENHANCE_YOUR_CALM)
                        .message(exception.message ?: "An error occurred: ${exception::class.simpleName}")
                        .path(handlerParameters.path)
                        .build()
                CompletableFuture.completedFuture(
                    DataFetcherExceptionHandlerResult
                        .newResult()
                        .error(graphqlError)
                        .build(),
                )
            } else {
                defaultHandler.handleException(handlerParameters)
            }
    }

    class CustomException(
        message: String,
    ) : DgsException(message = message, errorType = ErrorType.INTERNAL)
}
