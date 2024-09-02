import com.axis.maximus.jenkins.library.Artifactory
import com.axis.maximus.jenkins.library.Build
import com.axis.maximus.jenkins.library.Git
import com.axis.maximus.jenkins.library.Helm

def call(Map config = [:]) {
    pipeline {
        agent {
            kubernetes {
                yaml libraryResource('deploy-agent.yaml')
            }
        }

        options {
            skipStagesAfterUnstable()
            disableConcurrentBuilds()
        }

        environment {
            ARTIFACTORY_CREDS = credentials("ARTIFACTORY_CREDENTIALS")
            GIT_AUTH = credentials("GIT_USER")
        }

        stages {
            stage("Initial Setup") {
                steps {
                    script {
                        env.DOMAIN_NAME = config.domainName
                        env.ENVIRONMENT_LIST = config.environments as ArrayList

                        env.SERVICE = "$DOMAIN_NAME-sandbox"
                        env.BRANCH = "master"
                        env.PROJECT = "max"
                        git = new Git(this)
                    }
                }
            }

            stage('Checkout') {
                steps {
                    script {
                        git.checkout([serviceName: env.SERVICE, gitBranch: env.BRANCH], env.PROJECT)
                    }
                }
            }

            stage('Deploy on SandboxNew') {
              when {
                expression { ENVIRONMENT_LIST.contains("sandboxnew") }
              }
              steps {
                build job: "SANDBOXNEW/$DOMAIN_NAME-sandboxnew-deploy",
                  parameters: [string(name: 'branch', value: 'master')]
              }
            }

            stage('Deploy on Sandbox') {
                when {
                    expression { ENVIRONMENT_LIST.contains("sandbox") }
                }
                steps {
                    build job: "SANDBOX/$DOMAIN_NAME-sandbox-deploy",
                            parameters: [string(name: 'upstream_environment', value: 'sandboxnew'), string(name: 'branch', value: 'master')]
                }
            }

            stage("Deploy on UAT?") {
                steps {
                    script {

                        input(id: 'Proceed', message: 'Deploy on UAT?',
                                parameters: [[$class      : 'BooleanParameterDefinition',
                                              defaultValue: true,
                                              description : '',
                                              name        : 'Deploy on UAT?']
                                ],submitter: 'tw-axis-manager,tw-axis-infra-rep')
                    }
                }
            }

            stage('Deploy on UAT') {
                when {
                    expression { ENVIRONMENT_LIST.contains("uat") }
                }
                steps {
                    build job: "UAT/$DOMAIN_NAME-uat-deploy",
                            parameters: [string(name: 'upstream_environment', value: 'sandbox'), string(name: 'branch', value: 'master')]
                }
            }

            stage('Deploy on PRE PROD') {
                when {
                    expression { ENVIRONMENT_LIST.contains("preprod") }
                }
                steps {
                    build job: "PROD/$DOMAIN_NAME-prod-deploy",
                            parameters: [string(name: 'upstream_environment', value: 'uat'), string(name: 'branch', value: 'master')]
                }
            }

        }
    }
}
