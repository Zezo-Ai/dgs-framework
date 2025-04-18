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

package com.netflix.graphql.dgs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a method to be a data fetcher.
 * A data fetcher can receive the DataFetchingEnvironment.
 * The "parentType" property is the type that contains this field.
 * For root queries that is "Query", and for root mutations "Mutation".
 * The field is the name of the field this data fetcher is responsible for.
 * See https://netflix.github.io/dgs/getting-started/
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(DgsData.List.class)
@Inherited
public @interface DgsData {
    String parentType();

    String field() default "";

    /**
     * Indicates whether the generated DataFetcher for this method should be considered trivial.
     * For instance, if a method is simply pulling data from an object and not doing any I/O,
     * there may be some performance benefits to marking it as trivial.
     *
     * @see graphql.TrivialDataFetcher
     */
    boolean trivial() default false;

    /**
     * Container annotation that aggregates several {@link DgsData @DgsData} annotations.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @Documented
    @interface List {

        /**
         * Return the contained {@link DgsData} associated with this method.
         */
        DgsData[] value();
    }
}
