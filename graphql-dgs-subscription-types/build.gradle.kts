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

dependencies {
    api("com.graphql-java:graphql-java")
    api("com.fasterxml.jackson.core:jackson-annotations")

    implementation("org.springframework:spring-websocket")

    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--patch-module", "com.netflix.graphql.dgs.subscriptiontypes=${sourceSets["main"].output.asPath}"))
}
