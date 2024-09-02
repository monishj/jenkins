package com.axis.maximus.jenkins.library

import com.axis.maximus.jenkins.library.Artifactory
import com.axis.maximus.jenkins.library.Helm

class Smoke {
    def steps;
    def jenkinsUsername
    def jenkinsPassword

    Smoke(steps) {
        this.steps = steps
        this.jenkinsUsername = steps.JENKINS_CREDS_USR
        this.jenkinsPassword = steps.JENKINS_CREDS_PSW

    }

    def run(Map config = [:]) {
        def group = "$config.group"
        def env = "$config.currentEnvironment"
        def PATH_OF_ARTIFACTS = steps.sh(returnStdout: true, script: "pwd").trim()
        def verifySmoke = steps.libraryResource("verify-smoke.sh")
        steps.writeFile(file: "verify-smoke.sh", text: "${verifySmoke}")
        steps.sh "bash verify-smoke.sh $group $PATH_OF_ARTIFACTS $env"
    }

    def runMsil(Map config = [:]) {

        def path = "maximus-general/versions/smoke/msil-test-${config.currentEnvironment}-version.txt"
        def releaseName = "$config.release-$config.currentEnvironment"
        def tag = "$config.tag"
        def namespace = "msil-$config.currentEnvironment-task"
        def artifactory = new Artifactory(steps)
        def helm = new Helm(steps)
        artifactory.getArtifact(path)
        def MSIL_TEST_VERSION = steps.sh(script: "cat msil-test-${config.currentEnvironment}-version.txt", returnStdout: true).trim()
        helm.deleteRelease(releaseName: "$releaseName", namespace: "$namespace")
        helm.addRepo()
        if (config.currentEnvironment == "perf") {

            helm.deploy([namespace    : "$namespace",
                        releaseName  : "$releaseName",
                        chartLocation: "stable/msil-tests-performance",
                        version      : "$MSIL_TEST_VERSION",
                        addExtra     : "--set environment.name=$config.currentEnvironment \
                                                --set goServer.password=$jenkinsPassword \
                                                --set goServer.username=$jenkinsUsername \
                                                --set goServer.uploadURL=jenkins.ci-cd.svc.cluster.local:8080 \
                                                --set config.CONSTANT_USERS='test' \
                                                --set config.CONSTANT_USERS_DURATION=10 \
                                                --set config.ENCRYPTION_ENABLED=false"])

        }

        if (config.currentEnvironment == "sit") {

            helm.deploy([namespace    : "$namespace",
                        releaseName  : "$releaseName",
                        chartLocation: "stable/msil-tests",
                        version      : "$MSIL_TEST_VERSION",
                        addExtra     : "--set environment.name=$config.currentEnvironment \
                                                --set environment.tag=$tag \
                                                --set goServer.password=$jenkinsPassword \
                                                --set goServer.username=$jenkinsUsername \
                                                --set goServer.uploadURL=jenkins.ci-cd.svc.cluster.local:8080 \
                                                --set job.name=$releaseName"])
        }

        def verifySmoke = steps.libraryResource("verify-msil-run.sh")
        steps.writeFile(file: "verify-msil-run.sh", text: "${verifySmoke}")
        steps.sh "bash verify-msil-run.sh $namespace $releaseName $config.currentEnvironment"
        helm.deleteRelease(releaseName: "$releaseName", namespace: "$namespace")

    }


}
