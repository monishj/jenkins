import com.axis.maximus.jenkins.library.*

def call(Map config = [:]) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource(config.buildAgent)
        podRetention onFailure()
        activeDeadlineSeconds 18000
        // idleMinutes 180
      }
    }

    options {
      skipStagesAfterUnstable()
      disableConcurrentBuilds()

    }

    environment {
      CHECKMARX_CREDS = credentials("CHECKMARX_CREDENTIAL")
      ARTIFACTORY_CREDS = credentials("ARTIFACTORY_CREDENTIALS")
      GIT_AUTH = credentials("GIT_USER")
      BUILD_TYPE = "gradle"
      TWISTLOCK_CREDS = credentials("TWISTLOCK_CREDENTIALS")
      PACT_BROKER_USERNAME = credentials("PACT_BROKER_USERNAME")
      PACT_BROKER_PASSWORD = credentials("PACT_BROKER_PASSWORD")
    }

    stages {
      stage("Initial Setup") {
        steps {
          script {
            env.BUILD_NUMBER = "$BUILD_NUMBER"
            env.ARTIFACTORY_USERNAME = "$ARTIFACTORY_CREDS_USR"
            env.ARTIFACTORY_PASSWORD = "$ARTIFACTORY_CREDS_PSW"
            env.TWISTLOCK_USERNAME = "$TWISTLOCK_CREDS_USR"
            env.TWISTLOCK_PASSWORD = "$TWISTLOCK_CREDS_PSW"
            env.CHECKMARX_PASSWORD = "$CHECKMARX_CREDS_PSW"
            env.ARTIFACTORY_URL = "artifactory.axisb.com"
            env.SERVICE = config.serviceName
            env.BRANCH = config.gitBranch
            env.PROJECT = config.repositoryName
            env.DOMAIN_NAME = config.domainName
            env.CURRENT_ENVIRONMENT = "build"
            env.enableSeedPerService = config.enableSeedPerService

            for (elem in config.envVariables) {
              env."${elem.key}" = elem.value
            }
            build = new Build(this)
            git = new Git(this)
            artifactory = new Artifactory(this)
            helm = new Helm(this)
            util = new Util(this)
          }
        }
      }

      stage('Checkout') {
        steps {
          script {
            bitbucketPush()
            git.checkout([serviceName: env.SERVICE, gitBranch: env.BRANCH], config.projectName)
            dir("$SERVICE") {
              env.GIT_SHA = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
          }
        }
      }

      stage("Seed validate") {
        when {
          expression {
            env.enableSeedPerService == 'true'
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              artifactory.getArtifact("maximus-general/seeder-cli/seeder-linux-amd64")
              sh "chmod +x seeder-linux-amd64"
              sh "./seeder-linux-amd64 validate ."
            }
          }
        }
      }

      stage("Create Image Tag") {
        steps {
          dir("$SERVICE") {
            script {
              sh "git log -n 1 --pretty=format:'%h' | cut -c1-5> tag.txt"
              tag = sh(returnStdout: true, script: "cat tag.txt").trim()
              env.IMAGE_TAG = "$BUILD_NUMBER-$tag"
              // env.IMAGE_TAG = "$env.BRANCH-$env.BUILD_NUMBER-$tag-jenkins"
            }
          }
        }
      }

      stage("Build") {
        when {
          expression {
            return config.buildMultiArchImages == 'false'
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              env.runProviderVerificationTestExists = build.providerVerificationTaskExists()
              if (env.runProviderVerificationTestExists == 'true') {
                build.backendBuildWithoutContractTestKaniko(env.GIT_SHA, config.serviceName)
              } else {
                build.backendBuildKaniko()
              }
              docker = new Docker(this, "$CURRENT_ENVIRONMENT-$IMAGE_TAG", config.serviceName, config.docker_path)
              imageRepo = docker.tagImageKaniko(true, config.deployedOnProd)
            }
            container('kaniko') {
              script {
                sh "/kaniko/executor --dockerfile Dockerfile --destination=$imageRepo:$CURRENT_ENVIRONMENT-$IMAGE_TAG --destination=$imageRepo:latest --context `pwd` --build-arg DEPENDENCY=/build/dependency"
              }
            }
          }
        }
      }

      stage("Build Multiarchitecture Image") {
        when {
          expression {
            return config.buildMultiArchImages == 'true'
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              env.runProviderVerificationTestExists = build.providerVerificationTaskExists()
              if (env.runProviderVerificationTestExists == 'true') {
                build.backendBuildWithoutContractTestMultiArchKaniko(env.GIT_SHA, config.serviceName)
              } else {
                build.backendBuildMultiArchKaniko()
              }
              docker = new Docker(this, "$CURRENT_ENVIRONMENT-$IMAGE_TAG", config.serviceName, config.docker_path)
              imageRepo = docker.tagImageKaniko(true, config.deployedOnProd)
            }
            container('kaniko') {
              script {
                  sh "/kaniko/executor --dockerfile Dockerfile --destination=$imageRepo:$CURRENT_ENVIRONMENT-$IMAGE_TAG --destination=$imageRepo:latest --context `pwd` --build-arg DEPENDENCY=/build/dependency"
                  if (env.enableSeedPerService == 'false'){
                    try{
                      timeout(time: 20, unit: 'SECONDS'){
                        sh "/kaniko/executor --dockerfile DockerfileWithArm --destination=$imageRepo:$CURRENT_ENVIRONMENT-$IMAGE_TAG-arm --destination=$imageRepo:latest-arm --context `pwd` --build-arg DEPENDENCY=/build/dependency --custom-platform=linux/arm64"
                      }
                    }
                    catch(Exception e){
                      echo('It is timeout!')  
                    }
                  }
              }
            }
          }
        }
      }

      stage("Run Provider Contract Tests") {
        when {
          expression {
            return env.runProviderVerificationTestExists == 'true'
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                build.runBackEndProviderVerificationTestForSIT(env.GIT_SHA, env.BRANCH)
              }
              catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                build.runBackEndProviderVerificationTest(env.GIT_SHA, env.BRANCH)
              }
            }
          }
        }
      }

      stage("Checkmarx scan") {
        steps {
          script {
            build.runCheckmarx(env.BRANCH, env.SERVICE, false)
          }
        }
      }

      stage('SonarQube analysis') {
        steps {
          script {
            dir("$SERVICE") {
              withSonarQubeEnv("SonarQube") { // Will pick the global server connection you have configured
                build.runSonarqube("$SERVICE")
              }
              timeout(time: 300, unit: 'SECONDS') {
                // Just in case something goes wrong, pipeline will be killed after a timeout
                def qg = waitForQualityGate() // Reuse taskId previously collected by withSonarQubeEnv
                build.qualityGateCheck(qg.status)
              }
            }
          }
        }
      }

      stage("Publish Pacts") {
        when {
          expression {
            return util.folderExistsAndNonEmpty("$SERVICE/build/pacts")
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              sh "pact-broker publish build/pacts --consumer-app-version=$env.GIT_SHA --branch $env.BRANCH  --broker-username=$PACT_BROKER_USERNAME --broker-password=$PACT_BROKER_PASSWORD"
            }
          }
        }
      }

      stage("Publish pact-events") {
        when {
          expression {
            return steps.fileExists("$SERVICE/build/resources/pact-events.json")
          }
        }
        steps {
          dir("$SERVICE/build/resources") {
            script {
              artifactory.upload("pact-events.json", "maximus-general/contract/events/$SERVICE/pact-events-${env.GIT_SHA}.json")
            }
          }
        }
      }

      stage("Upload Pact Version to Artifactory") {
        when {
          expression {
            return (config.gitBranch == 'master' || config.gitBranch == null)
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              sh "echo ${SERVICE}=${env.GIT_SHA} >> pact-version.txt"
              sh "echo 'pact version being uploaded is-'"
              sh "cat pact-version.txt"
              artifactory.upload("pact-version.txt", "maximus-general/services-build-versions/id/${config.domainName}/$SERVICE/pact-version.txt")
            }
          }
        }
      }

      stage("Scan and push service-seed Docker Image") {
        when {
          expression {
            env.enableSeedPerService == 'true'
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              docker = new Docker(this, "$IMAGE_TAG", "$config.serviceName-seed", config.docker_path)
            }
            container('kaniko') {
              script {
                sh "/kaniko/executor --dockerfile Dockerfile.serviceSeed --destination=$imageRepo-seed:$IMAGE_TAG --destination=$imageRepo-seed:latest --context `pwd`"
                if (config.buildMultiArchImages == 'true') {
                  try{
                      timeout(time: 20, unit: 'SECONDS'){
                        sh "/kaniko/executor --dockerfile DockerfileWithArm --destination=$imageRepo:$CURRENT_ENVIRONMENT-$IMAGE_TAG-arm --destination=$imageRepo:latest-arm --context `pwd` --build-arg DEPENDENCY=/build/dependency --custom-platform=linux/arm64"
                      }
                    }
                    catch(Exception e){
                      echo('It is timeout!')  
                    }
                }
              }
            }
            container('jnlp') {
              script {
                docker.login()
                sh "docker pull $imageRepo-seed:$IMAGE_TAG"
                docker.scanImageKaniko("$imageRepo-seed", "$IMAGE_TAG")
                sh "docker rmi $imageRepo-seed:$IMAGE_TAG"
              }
            }
          }
        }
      }
      

      stage("Scan and Push Docker Image") {
        when {
          expression {
            return config.buildMultiArchImages == 'false'
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              docker.login()
              sh "docker pull $imageRepo:$CURRENT_ENVIRONMENT-$IMAGE_TAG"
              docker.scanImageKaniko("$imageRepo", "$CURRENT_ENVIRONMENT-$IMAGE_TAG")
              sh "docker rmi $imageRepo:$CURRENT_ENVIRONMENT-$IMAGE_TAG"
            }
          }
        }
      }

      stage("Scan and Push Multiarchitecture Docker Image") {
        when {
          expression {
            return config.buildMultiArchImages == 'true'
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              docker.login()
              sh "docker pull $imageRepo:$CURRENT_ENVIRONMENT-$IMAGE_TAG"
              docker.scanImageKaniko("$imageRepo", "$CURRENT_ENVIRONMENT-$IMAGE_TAG")
              sh "docker rmi $imageRepo:$CURRENT_ENVIRONMENT-$IMAGE_TAG"
            }
          }
        }
      }

      stage("Update Helm chart for seed") {
        when {
          expression {
            env.enableSeedPerService == 'true'
          }
        }
        steps {
          dir("$SERVICE") {
            script {
              sh "sed -ri \"s/tag: latest/tag: ${IMAGE_TAG}/\" seed-helm/${SERVICE}/values.yaml"
              sh "helm lint seed-helm/$SERVICE"
              path = "seed-helm/$SERVICE"
              helm.packageChart("$path", "$IMAGE_TAG")
              chartName = sh(returnStdout: true, script: "cat seed-helm/$SERVICE/Chart.yaml  | grep name | cut -d ' ' -f 2").trim()
              artifactory.upload("$chartName-${IMAGE_TAG}.tgz", "maximus-helm-local/$config.helm_folder/build/$SERVICE-seed/$chartName-${IMAGE_TAG}.tgz")
            }
          }
        }
      }

      stage("Update Helm chart") {
        steps {
          dir("$SERVICE") {
            script {
              sh "sed -ri \"s/tag: latest/tag: ${IMAGE_TAG}/\" id-helm/${SERVICE}/values.yaml"
              sh "helm lint id-helm/$SERVICE"
              path = "id-helm/$SERVICE"
              helm.packageChart("$path", "$IMAGE_TAG")
              chartName = sh(returnStdout: true, script: "cat id-helm/$SERVICE/Chart.yaml  | grep name | cut -d ' ' -f 2").trim()
              artifactory.upload("$chartName-${IMAGE_TAG}.tgz", "maximus-helm-local/$config.helm_folder/${config.domainName}/$SERVICE/$chartName-${IMAGE_TAG}.tgz")
            }
          }
        }
      }

      stage("Print Built Image and Helm Tag") {
        when {
          expression {
            return (config.gitBranch == 'master' || config.gitBranch == null)
          }
        }
        steps {
          script {
            sh "echo 'Built Image Tag and helm chart tag is $IMAGE_TAG'"
            sh "echo ${SERVICE}=${IMAGE_TAG} >> version.txt"
            sh "echo 'version being uploaded is-'"
            sh "cat version.txt"
            artifactory.upload("version.txt", "maximus-general/services-build-versions/id/${config.domainName}/$SERVICE/version.txt")
          }
        }
      }

      stage("Generate Version Manifest") {
        when {
          expression {
            return ((config.gitBranch == 'master' || config.gitBranch == null))
          }
        }
        steps {
          build wait: false, job: "$config.domainName-generate-version-manifest"
        }
      }
    
      stage("Generate Version Manifest Replica") {
        when {
          expression {
            return ((config.gitBranch == /^feature.*/))
          }
        }
        steps {
          build wait: false, job: "$config.domainName-generate-version-manifest-replica"
        }
      }
    }

    post {
      always {
        dir("$SERVICE") {
          script {
            publishHTML target: [
              allowMissing         : false,
              alwaysLinkToLastBuild: false,
              keepAll              : true,
              reportDir            : 'build/reports/tests/test',
              reportFiles          : 'index.html',
              reportName           : "Tests Report"
            ]

            publishHTML target: [
              allowMissing         : true,
              alwaysLinkToLastBuild: false,
              keepAll              : true,
              reportDir            : 'build/reports/tests/runProviderVerificationTests',
              reportFiles          : 'index.html',
              reportName           : "Provider Test Report"
            ]

            archiveArtifacts artifacts: 'build/reports/**', fingerprint: true, allowEmptyArchive: true
            archiveArtifacts(artifacts: 'scan-results.json', fingerprint: true, allowEmptyArchive: false)
          }
        }
      }
    }
  }
}
