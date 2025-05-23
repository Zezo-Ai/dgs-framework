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

package com.netflix.graphql.dgs.context

import com.netflix.graphql.dgs.internal.DgsRequestData
import graphql.GraphQLContext

/**
 * For each bean implementing this interface found, the framework will call the [contribute] method for every request.
 * The [contribute] method is then able to use the [GraphQLContext.Builder] to provide additional entries to place in the context.
 */
fun interface GraphQLContextContributor {
    fun contribute(
        builder: GraphQLContext.Builder,
        extensions: Map<String, Any>?,
        requestData: DgsRequestData?,
    )
}
