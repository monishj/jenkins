import com.axis.maximus.jenkins.library.Build
import com.axis.maximus.jenkins.library.Docker
import com.axis.maximus.jenkins.library.Git
import com.axis.maximus.jenkins.library.Helm
import com.axis.maximus.jenkins.library.Artifactory


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
      NPM_ARTIFACTORY_TOKEN = credentials("NPM_ARTIFACTORY_TOKEN")
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
            for (elem in config.envVariables) {
              env."${elem.key}" = elem.value
            }
            build = new Build(this)
            git = new Git(this)
            artifactory = new Artifactory(this)
            helm = new Helm(this)
          }
        }
      }

      stage('Checkout') {
        steps {
          script {
            bitbucketPush()
            git.checkout([serviceName: env.SERVICE, gitBranch: env.BRANCH], config.projectName)
            git.checkout([serviceName: 'gpay-infra-deployment', gitBranch: 'master'], 'max')
            dir("$SERVICE") {
              env.GIT_SHA = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
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

      stage("Check linting") {
        steps {
          dir("$SERVICE") {
            script {
              def exists = fileExists 'package.json'
              if (exists) {
                build.frontendLintCheck();
              }
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
              def exists = fileExists 'package.json'
              if (exists) {
                build.frontendBuild()
                docker = new Docker(this, "$IMAGE_TAG", config.serviceName, config.docker_path)
                imageRepo = docker.tagImageKaniko()
              } else {
                docker = new Docker(this, "$IMAGE_TAG", config.serviceName, config.docker_path)
                imageRepo = docker.tagImageKaniko()
                env.runProviderVerificationTestExists = build.providerVerificationTaskExists()
                if (env.runProviderVerificationTestExists == 'true') {
                  build.backendBuildWithoutContractTestKaniko(env.GIT_SHA, config.serviceName)
                } else {
                  build.backendBuildKaniko(config.serviceName)
                }
              }
              container('kaniko'){
                script {
                  sh "/kaniko/executor --dockerfile Dockerfile --destination=$imageRepo:$IMAGE_TAG --destination=$imageRepo:latest --context `pwd`"  
                }
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
              def exists = fileExists 'package.json'
              if (exists) {
                build.frontendBuild()
              } else {
                docker = new Docker(this, "$IMAGE_TAG", config.serviceName, config.docker_path)
                imageRepo = docker.tagImageKaniko(true, , config.deployedOnProd)
                env.runProviderVerificationTestExists = build.providerVerificationTaskExists()
                if (env.runProviderVerificationTestExists == 'true') {
                  build.backendBuildWithoutContractTestMultiArchKaniko(env.GIT_SHA, config.serviceName)
                } else {
                  build.backendBuildMultiArchKaniko(config.serviceName)
                }
              }
            }
            container('kaniko') {
              script {
                  sh "/kaniko/executor --dockerfile Dockerfile --destination=$imageRepo:$IMAGE_TAG --destination=$imageRepo:latest --context `pwd` --build-arg DEPENDENCY=/build/dependency"
                  try{
                    timeout(time: 20, unit: 'SECONDS'){
                      sh "/kaniko/executor --dockerfile DockerfileWithArm --destination=$imageRepo:$IMAGE_TAG-arm --destination=$imageRepo:latest-arm --context `pwd` --build-arg DEPENDENCY=/build/dependency --custom-platform=linux/arm64"
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

      stage("Checkmarx scan") {
        steps {
          script {
            build.runCheckmarx(env.BRANCH, env.SERVICE, false)
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
              def exists = fileExists 'package.json'
              if (exists) {
                docker.login()
                sh "docker pull $imageRepo:$IMAGE_TAG"
                docker.scanImageKaniko("$imageRepo", "$IMAGE_TAG")
                sh "docker rmi $imageRepo:$IMAGE_TAG"
              }
              else {
                docker.login()
                sh "docker pull $imageRepo:$IMAGE_TAG"
                docker.scanImageKaniko("$imageRepo", "$IMAGE_TAG")
                sh "docker rmi $imageRepo:$IMAGE_TAG"
              }
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
              sh "docker pull $imageRepo:$IMAGE_TAG"
              docker.scanImageKaniko("$imageRepo", "$IMAGE_TAG")
              sh "docker rmi $imageRepo:$IMAGE_TAG"
            }
          }
        }
      }

      stage("Update Helm chart") {
        steps {
          dir("gpay-infra-deployment") {
            script {
              sh '''
                                curl -u${ARTIFACTORY_USERNAME}:${ARTIFACTORY_PASSWORD} -o build-versions.txt https://artifactory.axisb.com/artifactory/gpay-general/versions/base/base-versions.txt
                                ls
                                base_chart_version=$(cat build-versions.txt)
                                ls -al
                                cd ${SERVICE}-chart
                                sed "s/0.0.1/0.0.${base_chart_version}/g" Chart.yaml > new_chart.yaml
                                mv new_chart.yaml Chart.yaml
                                cat Chart.yaml 
                                helm repo add --pass-credentials stable https://artifactory.axisb.com/artifactory/maximus-helm-virtual --username ${ARTIFACTORY_USERNAME} --password ${ARTIFACTORY_PASSWORD}
                                helm dependency update
                                if [  ${SERVICE} == gpay-gateway ]
                                then
                                    curl -u${ARTIFACTORY_USERNAME}:${ARTIFACTORY_PASSWORD} https://artifactory.axisb.com/artifactory/gpay-general/versions/build/pgp-cipher-side-car-build-versions.txt
                                    sed -ri "s/^(\\s*)(version\\s*:\\s*latest\\s*$)/\\1version: ${GO_DEPENDENCY_LABEL_PGP_CIPHER_SIDE_CAR}/" values.yaml
                                fi
                                helm package . --version=0.0.${IMAGE_TAG} --app-version=${IMAGE_TAG}
                                curl -u ${ARTIFACTORY_USERNAME}:${ARTIFACTORY_PASSWORD} -T ${SERVICE}-0.0.${IMAGE_TAG}.tgz  "https://artifactory.axisb.com/artifactory/maximus-helm-local/dev-gpay/${SERVICE}/${SERVICE}-0.0.${IMAGE_TAG}.tgz"
                                '''
            }
          }
        }
      }

      stage("Print Built Image and Helm Tag") {
        steps {
          script {
            sh "echo 'Built Image Tag and helm chart tag is $IMAGE_TAG'"
            sh "echo ${SERVICE}=${IMAGE_TAG} >> version.txt"
            sh "echo 'version being uploaded is-'"
            sh "cat version.txt"
            artifactory.upload("version.txt", "gpay-general/services-build-versions/$SERVICE/version.txt")
          }
        }
      }

      stage("Generate Version Manifest") {
        steps {
          build wait: false, job: 'gpay-generate-version-manifest'
        }
      }


    }
    post {
      always {
        dir("$SERVICE") {
          script {
            def exists = fileExists 'package.json'
            if (!exists) {
              publishHTML target: [
                allowMissing         : false,
                alwaysLinkToLastBuild: false,
                keepAll              : true,
                reportDir            : 'build/reports/tests/test',
                reportFiles          : 'index.html',
                reportName           : "Tests Report"
              ]
              archiveArtifacts artifacts: 'build/reports/**', fingerprint: true, allowEmptyArchive: true
              archiveArtifacts(artifacts: 'scan-results.json', fingerprint: true, allowEmptyArchive: false)
            }
          }
        }
      }
    }
  }
}
