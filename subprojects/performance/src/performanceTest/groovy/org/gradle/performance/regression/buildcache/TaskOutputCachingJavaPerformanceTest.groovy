/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.performance.regression.buildcache

import org.gradle.performance.fixture.BuildExperimentInvocationInfo
import org.gradle.performance.fixture.BuildExperimentListener
import org.gradle.performance.fixture.BuildExperimentListenerAdapter
import org.gradle.performance.fixture.GradleInvocationSpec
import org.gradle.performance.fixture.InvocationCustomizer
import org.gradle.performance.fixture.InvocationSpec
import org.gradle.performance.generator.JavaTestProject
import org.gradle.performance.measure.MeasuredOperation
import org.gradle.performance.mutator.ApplyAbiChangeToJavaSourceFileMutator
import org.gradle.performance.mutator.ApplyNonAbiChangeToJavaSourceFileMutator
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.keystore.TestKeyStore
import org.gradle.test.fixtures.server.http.HttpBuildCacheServer
import org.junit.Rule
import spock.lang.Unroll

import static org.gradle.performance.generator.JavaTestProject.LARGE_JAVA_MULTI_PROJECT

@Unroll
class TaskOutputCachingJavaPerformanceTest extends AbstractTaskOutputCacheJavaPerformanceTest {

    private TestFile cacheDir
    private String protocol = "http"
    private boolean pushToRemote

    @Rule
    HttpBuildCacheServer buildCacheServer = new HttpBuildCacheServer(temporaryFolder)

    def setup() {
        buildCacheServer.logRequests = false
        cacheDir = temporaryFolder.file("local-cache")
        runner.addBuildExperimentListener(new BuildExperimentListenerAdapter() {
            @Override
            void beforeInvocation(BuildExperimentInvocationInfo invocationInfo) {
                if (isRunWithCache(invocationInfo)) {
                    if (!buildCacheServer.isRunning()) {
                        buildCacheServer.start()
                    }
                    def settings = new TestFile(invocationInfo.projectDir).file('settings.gradle')
                    if (isFirstRunWithCache(invocationInfo)) {
                        cacheDir.deleteDir().mkdirs()
                        buildCacheServer.cacheDir.deleteDir().mkdirs()
                        settings << remoteCacheSettingsScript
                    }
                    assert buildCacheServer.uri.toString().startsWith("${protocol}://")
                    assert settings.text.contains(buildCacheServer.uri.toString())
                }
            }

            @Override
            void afterInvocation(BuildExperimentInvocationInfo invocationInfo, MeasuredOperation operation, BuildExperimentListener.MeasurementCallback measurementCallback) {
                if (isLastRun(invocationInfo)) {
                    assert !(buildCacheServer.cacheDir.allDescendants().empty && cacheDir.allDescendants().isEmpty())
                    assert pushToRemote || buildCacheServer.cacheDir.allDescendants().empty
                }
            }
        })
    }

    def "clean #tasks on #testProject with remote http cache"() {
        setupTestProject(testProject, tasks)
        protocol = "http"
        pushToRemote = true
        runner.addBuildExperimentListener(cleanLocalCache())

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        [testProject, tasks] << scenarios
    }

    def "clean #tasks on #testProject with remote https cache"() {
        setupTestProject(testProject, tasks)
        firstWarmupWithCache = 2 // Do one run without the cache to populate the dependency cache from maven central
        protocol = "https"
        pushToRemote = true
        runner.addBuildExperimentListener(cleanLocalCache())

        def keyStore = TestKeyStore.init(temporaryFolder.file('ssl-keystore'))
        keyStore.enableSslWithServerCert(buildCacheServer)

        runner.addInvocationCustomizer(new InvocationCustomizer() {
            @Override
            <T extends InvocationSpec> T customize(BuildExperimentInvocationInfo invocationInfo, T invocationSpec) {
                GradleInvocationSpec gradleInvocation = invocationSpec as GradleInvocationSpec
                if (isRunWithCache(invocationInfo)) {
                    gradleInvocation.withBuilder().gradleOpts(*keyStore.serverAndClientCertArgs).build() as T
                } else {
                    gradleInvocation.withBuilder()
                    // We need a different daemon for the other runs because of the certificate Gradle JVM args
                    // so we disable the daemon completely in order not to confuse the performance test
                        .useDaemon(false)
                    // We run one iteration without the cache to download artifacts from Maven central.
                    // We can't download with the cache since we set the trust store and Maven central uses https.
                        .args("--no-build-cache")
                        .build() as T
                }
            }
        })

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        [testProject, tasks] << scenarios
    }

    def "clean #tasks on #testProject with empty local cache"() {
        given:
        setupTestProject(testProject, tasks)
        runner.warmUpRuns = 6
        runner.runs = 8
        pushToRemote = false
        runner.addBuildExperimentListener(cleanLocalCache())

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        [testProject, tasks] << scenarios
    }

    def "clean #tasks on #testProject with empty remote http cache"() {
        given:
        setupTestProject(testProject, tasks)
        runner.warmUpRuns = 6
        runner.runs = 8
        pushToRemote = true
        runner.addBuildExperimentListener(cleanLocalCache())
        runner.addBuildExperimentListener(cleanRemoteCache())

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        [testProject, tasks] << scenarios
    }

    def "clean #tasks on #testProject with local cache (parallel: #parallel)"() {
        given:
        if (!parallel) {
            runner.previousTestIds = ["clean $tasks on $testProject with local cache"]
        }
        setupTestProject(testProject, tasks)
        if (parallel) {
            runner.args += "--parallel"
        }
        pushToRemote = false

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testScenario << [scenarios, [true, false]].combinations()
        projectInfo = testScenario[0]
        parallel = testScenario[1]
        testProject = projectInfo[0]
        tasks = projectInfo[1]
    }

    def "clean #tasks for abi change on #testProject with local cache (parallel: true)"() {
        given:
        setupTestProject(testProject, tasks)
        runner.addBuildExperimentListener(new ApplyAbiChangeToJavaSourceFileMutator(testProject.config.fileToChangeByScenario['assemble']))
        runner.args += "--parallel"
        pushToRemote = false

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        // We only test the multiproject here since for the monolitic project we would have no cache hits.
        // This would mean we actually would test incremental compilation.
        [testProject, tasks] << [[LARGE_JAVA_MULTI_PROJECT, 'assemble']]
    }

    def "clean #tasks for non-abi change on #testProject with local cache (parallel: true)"() {
        given:
        setupTestProject(testProject, tasks)
        runner.addBuildExperimentListener(new ApplyNonAbiChangeToJavaSourceFileMutator(testProject.config.fileToChangeByScenario['assemble']))
        runner.args += "--parallel"
        pushToRemote = false

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        // We only test the multiproject here since for the monolitic project we would have no cache hits.
        // This would mean we actually would test incremental compilation.
        [testProject, tasks] << [[LARGE_JAVA_MULTI_PROJECT, 'assemble']]
    }

    private BuildExperimentListenerAdapter cleanLocalCache() {
        new BuildExperimentListenerAdapter() {
            @Override
            void beforeInvocation(BuildExperimentInvocationInfo invocationInfo) {
                cacheDir.deleteDir().mkdirs()
            }
        }
    }

    private BuildExperimentListenerAdapter cleanRemoteCache() {
        new BuildExperimentListenerAdapter() {
            @Override
            void beforeInvocation(BuildExperimentInvocationInfo invocationInfo) {
                buildCacheServer.cacheDir.deleteDir().mkdirs()
            }
        }
    }

    private String getRemoteCacheSettingsScript() {
        """
            def httpCacheClass = Class.forName('org.gradle.caching.http.HttpBuildCache')
            buildCache {
                local {
                    directory = '${cacheDir.absoluteFile.toURI()}'
                }
                remote(httpCacheClass) {
                    url = '${buildCacheServer.uri}/' 
                    push = ${pushToRemote}
                }
            }
        """.stripIndent()
    }

    private def setupTestProject(JavaTestProject testProject, String tasks) {
        runner.testProject = testProject
        runner.gradleOpts = ["-Xms${testProject.daemonMemory}", "-Xmx${testProject.daemonMemory}"]
        runner.tasksToRun = tasks.split(' ') as List
        runner.cleanTasks = ["clean"]
    }

}
