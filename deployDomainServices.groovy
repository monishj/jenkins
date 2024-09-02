import com.axis.maximus.jenkins.library.*

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
      timeout(time: 8, unit: 'HOURS')
      buildDiscarder(logRotator(numToKeepStr: '150', artifactNumToKeepStr: '150'))
    }

    environment {
      ARTIFACTORY_CREDS = credentials("ARTIFACTORY_CREDENTIALS")
      GIT_AUTH = credentials("GIT_USER")
      PACT_BROKER_USERNAME = credentials("PACT_BROKER_USERNAME")
      PACT_BROKER_PASSWORD = credentials("PACT_BROKER_PASSWORD")
    }

    stages {
      stage('Prerequisites') {
        steps {
          script {
            env.ARTIFACTORY_USERNAME = "$ARTIFACTORY_CREDS_USR"
            env.ARTIFACTORY_PASSWORD = "$ARTIFACTORY_CREDS_PSW"
            env.BUILD_NUMBER = "$BUILD_NUMBER"
            env.helmRepo = 'https://artifactory.axisb.com/artifactory/maximus-helm-virtual'
            env.currentEnvironment = config.currentEnvironment
            env.upstreamEnvironment = config.upstreamEnvironment
            env.domainName = config.domainName
            env.BRANCH = config.releaseBranch
            env.deployRepositoryName = config.deployRepositoryName
            env.enableRegressionTests = config.enableRegressionTests
            env.RELEASE_WAY = config.versioning
            env.overrideVersions = config.override_versions
            env.PACT_ENV = config.currentEnvironment.toUpperCase()
            env.enableSeedPerService = config.enableSeedPerService
            env.enforce_pact_integration = config.enforce_pact_integration
            env.CONFIG_CHANGE = config.configChange ? config.configChange : false
            env.RATELIMIT_ENABLED = config.ratelimitEnabled ? config.ratelimitEnabled : false
            artifactory = new Artifactory(this)
            helm = new Helm(this)
            git = new Git(this)
            util = new Util(this)
            buildObject = new Build(this)
            pactObject = new Pact(this)
            env.serviceNamespace = util.getNamespaceForDomainsServices(domainName, currentEnvironment)
            env.uiNamespace = util.getNamespaceForDomainsWeb(domainName, currentEnvironment)
            env.taskNamespace = util.getNamespaceForDomainsTask(domainName, currentEnvironment)
            env.SKIP_ALL_TESTS = config.SKIP_ALL_TESTS
          }
        }
      }

      stage('Checkout') {
        steps {
          script {
            git.checkout([serviceName: config.deployRepositoryName, gitBranch: env.BRANCH])
            sh "echo current upstream env ${env.upstreamEnvironment}"
            isPactVersionsPresent = sh (returnStatus: true, script: "curl -k --fail -u ${ARTIFACTORY_USERNAME}:${ARTIFACTORY_PASSWORD} https://artifactory.axisb.com/artifactory/maximus-general/versions/id/$domainName/$upstreamEnvironment/pact-versions.md --output pact-versions.md")
            if (isPactVersionsPresent != 0) {
              sh "echo 'FAIL : pact-versions.md is not present'"
            }
          }
        }
      }

      stage("Can I Deploy") {
        when {
          expression {
            return (env.currentEnvironment != 'prod' && env.upstreamEnvironment != 'prod')
          }
        }
       steps {
          script {
            artifactory.getArtifact("libs-release-local/com/axis/lending/can-i-deploy/latest/can-i-deploy-latest.jar")
            if (env.enforce_pact_integration == 'true') {
              steps.sh "java -jar can-i-deploy-latest.jar -b https://pactbroker.newuat.maximus.axisb.com -u $PACT_BROKER_USERNAME -p $PACT_BROKER_PASSWORD -e ${env.PACT_ENV} -vf pact-versions.md --retry-count-while-unknown 120"
            } else {
              catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                steps.sh "java -jar can-i-deploy-latest.jar -b https://pactbroker.newuat.maximus.axisb.com -u $PACT_BROKER_USERNAME -p $PACT_BROKER_PASSWORD -e ${env.PACT_ENV} -vf pact-versions.md --retry-count-while-unknown 0"
              }
            }
          }
        }
      }

      stage('config') {
        when {
          expression {
            return env.CONFIG_CHANGE == "true" && (currentEnvironment == "prodsandbox" || currentEnvironment == "perf") &&  (env.upstreamEnvironment == "sandbox" || env.upstreamEnvironment == "prod")
          }
        }
        steps {
          script {
            dir("$deployRepositoryName") {
              env.ARTIFACTORY_USER = "$ARTIFACTORY_CREDS_USR"
              env.ARTIFACTORY_PASSWORD = "$ARTIFACTORY_CREDS_PSW"
              dir("$domainName-config") {
                script {
                  buildObject.validateDomainConfigSchema()
                }
              }
              if (currentEnvironment == "perf" && isTriggeredByBackwardCompatibility(env.domainName)){
                echo "publishing config for backward compatibility"
                buildObject.publishDomainConfig(env.domainName, "backward-compatibility", env.BUILD_NUMBER)
              } else {
                buildObject.publishDomainConfig(env.domainName, env.upstreamEnvironment, env.BUILD_NUMBER)
              }
            }
          }
        }
      }

      stage('Deploy config') {
        steps {
          script {
            if (currentEnvironment == "perf" && isTriggeredByBackwardCompatibility(env.domainName) && env.CONFIG_CHANGE == "true"){
                getConfigHelmChartForBackwardCompatibility(artifactory,helm,env.domainName)
            }
            else {
              configPath = "maximus-general/versions/id/$domainName/$upstreamEnvironment/$upstreamEnvironment-config.txt"
              artifactory.getArtifact(configPath)
              configArtifactVersion = sh(returnStdout: true, script: "cat ${upstreamEnvironment}-config.txt").trim()
              configHelmChartPath = "https://artifactory.axisb.com/artifactory/maximus-helm-local/dev-$domainName/config/$domainName-config-$upstreamEnvironment-0.0.${configArtifactVersion}.tgz"
              helm.fetch(configHelmChartPath)
              sh "mkdir config ; tar -xvf $domainName-config-$upstreamEnvironment-0.0.${configArtifactVersion}.tgz -C config; cd config"
              env.valuesFileLocation = "$domainName-config/values-${currentEnvironment}.yaml"
              if ("$currentEnvironment" == "prod") {
                dir("config") {
                  updateProdValues = libraryResource("updateDomainProdValues.sh")
                  writeFile(file: "updateDomainProdValues.sh", text: "${updateProdValues}")
                  sh "bash updateDomainProdValues.sh $domainName"
                  env.valuesFileLocation = "values-${currentEnvironment}.yaml"
                }
              }
            }

            dir("config") {
              withCredentials(config.string_secret_ids) {
                helm.deploy([
                  env               : "$currentEnvironment",
                  namespace         : serviceNamespace,
                  chartLocation     : '$domainName-config',
                  releaseName       : "$domainName-config-$currentEnvironment",
                  valuesFileLocation: "$valuesFileLocation",
                  addExtra          : "$config.helmSecrets"
                ])
              }
            }
          }
        }
      }

      stage('Get versions file ') {
        when {
          expression {
            return env.currentEnvironment != 'uat'
          }
        }
        steps {
          script {
            dir("$deployRepositoryName") {
              path = "maximus-general/versions/id/$domainName/$upstreamEnvironment/versions.md"
              artifactory.getArtifact(path)
              if ("$currentEnvironment" == "hotfix") {
                updateVersionScript = libraryResource("updateHotfixVersion.sh")
                writeFile(file: "updateHotfixVersion.sh", text: "${updateVersionScript}")
                sh "bash updateHotfixVersion.sh '$overrideVersions' '$currentEnvironment'"
              }
              sh "m4 versions.md $domainName/requirements.yaml > requirements-with-versions.yaml"
              sh "mv requirements-with-versions.yaml $domainName/requirements.yaml"
              sh "cat $domainName/requirements.yaml"
            }
          }
        }
      }

      stage('Apply env tag'){
        when{
          expression{
            return env.currentEnvironment != 'uat' && env.currentEnvironment != 'prod'
          }
        }
        steps{
          script{
            dir("$deployRepositoryName"){
              echo "Applying env tag"
              tagScript = libraryResource("envTag.sh")
              writeFile(file: "envTag.sh", text: "${tagScript}")
              sh "bash envTag.sh $ARTIFACTORY_USERNAME $ARTIFACTORY_PASSWORD $GIT_AUTH_USR $GIT_AUTH_PSW $domainName $BUILD_NUMBER $currentEnvironment $upstreamEnvironment"
            }
          }
        }
      }

      stage('tag versions file') {
        when {
          expression {
            return env.currentEnvironment == 'uat'
          }
        }
        steps {
          script {
            dir("$deployRepositoryName") {
              echo "Tagging services"
              tagScript = libraryResource("domainTag.sh")
              writeFile(file: "domainTag.sh", text: "${tagScript}")
              sh "echo $RELEASE_WAY"
              sh "bash domainTag.sh $ARTIFACTORY_USERNAME $ARTIFACTORY_PASSWORD $GIT_AUTH_USR $GIT_AUTH_PSW $domainName $RELEASE_WAY $upstreamEnvironment"
            }
          }
        }
      }

      stage('Deploy upstream seed Data') {
        when {
          expression {
            env.enableSeedPerService == 'true' && currentEnvironment == 'prodsandbox'
          }
        }
        steps {
          script {
            def seedOverrideEnvironment = "qa"
            if (env.SKIP_ALL_TESTS == "true") seedOverrideEnvironment = "prod"
            buildjob = "${currentEnvironment.toUpperCase()}/$currentEnvironment-domain-seed-feeder"
            build job: buildjob,
              parameters: [
                string(name: 'DOMAIN_NAME', value: env.domainName),
                string(name: 'FORCE_UPDATE', value: "false"),
                string(name: 'currentEnvironment', value: currentEnvironment),
                string(name: 'branch', value: BRANCH),
                string(name: 'upstreamEnvironment', value: upstreamEnvironment),
                string(name: 'enableSeedPerService', value: env.enableSeedPerService),
                string(name: 'seedOverrideEnvironment', value: seedOverrideEnvironment)
              ]
          }
        }
      }

      stage('Deploy seed Data') {
        when {
          expression {
            env.enableSeedPerService == 'true'
          }
        }
        steps {
          script {
            buildjob = "${currentEnvironment.toUpperCase()}/$currentEnvironment-domain-seed-feeder"
            build job: buildjob,
              parameters: [
                string(name: 'DOMAIN_NAME', value: env.domainName),
                string(name: 'FORCE_UPDATE', value: "false"),
                string(name: 'currentEnvironment', value: currentEnvironment),
                string(name: 'branch', value: BRANCH),
                string(name: 'upstreamEnvironment', value: upstreamEnvironment),
                string(name: 'enableSeedPerService', value: env.enableSeedPerService),
                string(name: 'seedOverrideEnvironment', value: currentEnvironment)
              ]
          }
        }
      }

      stage('Deploy services') {
        steps {
          script {
            dir("$deployRepositoryName") {
              if (upstreamEnvironment == "build" && currentEnvironment == "sit")
                rateLimiterPath = "maximus-general/versions/build/build-ratelimit.txt"
              else
                rateLimiterPath = "maximus-general/versions/id/$domainName/$upstreamEnvironment/$upstreamEnvironment-ratelimit.txt"
              artifactory.getArtifact(rateLimiterPath)
              env.rateLimitExists = steps.sh(returnStdout: true, script: "cat ${upstreamEnvironment}-ratelimit.txt | grep errors", returnStatus: true)
              if(env.rateLimitExists == "0")
                addExtra = "--set environment.name=$currentEnvironment --debug"
              else {
                rateLimitVersion = sh(returnStdout: true, script: "cat ${upstreamEnvironment}-ratelimit.txt").trim()
                addExtra = "--set environment.name=$currentEnvironment --set global.rateLimiterTag=$rateLimitVersion --debug"
              }
              helm.addRepo()
              helm.updateHelmDependency("$domainName")
              if (currentEnvironment == "prod") {
                sh "sed 's/artifactory.maximus.axisb.com/artifactory.axisb.com/g' $domainName/values-prod.yaml > $domainName/values-prod-modified.yaml"
                sh "mv $domainName/values-prod-modified.yaml $domainName/values-prod.yaml"
              }
              helm.deploy([env               : "$currentEnvironment",
                           namespace         : serviceNamespace,
                           releaseName       : "$domainName-$currentEnvironment",
                           chartLocation     : "$domainName",
                           valuesFileLocation: "$domainName/values-${currentEnvironment}.yaml",
                           addExtra          : addExtra])
            }
          }
        }
      }

      stage('Scale out Services') {
        steps {
          script {
            // condition to check as identity backend service runs in env-lending namespace and ui service runs in independant namespace
            if (currentEnvironment == "prod" && domainName != "identity") {
              sh "kubectl scale deploy -l domain=$domainName --replicas=1 -n $serviceNamespace"
            }
            // condition to check as discount and cards does not have ui services
            if (currentEnvironment == "prod" && domainName != 'cards' && domainName != 'discount') {
                sh "kubectl scale deploy -l domain=$domainName --replicas=1 -n $uiNamespace"
            }
          }
        }
      }

      stage('Check deployment status') {
        options {
          retry(2)
        }
        steps {
          script {
            verifyPodStatus = libraryResource("verify-domain-pod-status.sh")
            writeFile(file: "verify-domain-pod-status.sh", text: "${verifyPodStatus}")
            if (domainName != "identity") {
              sh "bash verify-domain-pod-status.sh $serviceNamespace $domainName"
            }
            if (domainName != 'cards' && domainName != 'discount') {             
              sh "bash verify-domain-pod-status.sh $uiNamespace $domainName"
            }
          }
        }
      }

      stage("Record Deployment in Pact") {
        when {
          expression {
            return (env.currentEnvironment != 'prod')
          }
        }
        steps {
          script {
            if (env.enforce_pact_integration == 'true') {
              pactObject.recordDeployment()
            } else {
              catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                pactObject.recordDeployment()
              }
            }
          }
        }
      }

      stage("Run env tests") {
        steps {
          script {
            def shouldRunTest = true
            if (currentEnvironment == "prodsandbox") {
              shouldRunTest = input(id: 'Test', message: 'Should run tests?',
                parameters: [[$class      : 'BooleanParameterDefinition',
                              defaultValue: true,
                              description : 'should run tests?',
                              name        : 'shouldRunTest']
                ],submitter: 'tw-axis-manager,tw-axis-infra-rep,fc-infra-rep,tw-axis-build-cop,fc-axis-build-cop')
            }
            if (shouldRunTest) {
              runDomainTests(domainName,currentEnvironment, enableRegressionTests)
            } else {
              echo "Skipping the test run"
            }
          }
        }
      }

      stage("Publish artifacts?") {
        steps {
          script {
            if ((currentEnvironment == "hotfix" && (domainName == 'authcap' || domainName == 'account' || domainName == 'cards')) || currentEnvironment == "prodsandbox") {
              input(id: 'Artifact', message: 'Publish artifacts?',
                parameters: [[$class      : 'BooleanParameterDefinition',
                              defaultValue: true,
                              description : 'should Publish artifacts?',
                              name        : 'shouldPublish']
                ],submitter: 'tw-axis-manager,tw-axis-infra-rep,fc-axis-infra-rep,tw-axis-build-cop,fc-axis-build-cop')
            } else {
              echo "proceeding without asking as it not required for this env"
            }
          }
        }
      }

      stage('Publish Artifacts') {
        steps {
          parallel(
            "Publish config": {
              script {
                writeFile(file: "$currentEnvironment-config.txt", text: BUILD_NUMBER)
                sh "cd config; tar -cvf ../$domainName-config-${currentEnvironment}-0.0.${BUILD_NUMBER}.tgz $domainName-config"
                artifactory.upload("$currentEnvironment-config.txt", "maximus-general/versions/id/$domainName/${currentEnvironment}/${currentEnvironment}-config.txt")
                artifactory.upload("$domainName-config-${currentEnvironment}-0.0.${BUILD_NUMBER}.tgz", "maximus-helm-local/dev-$domainName/config/$domainName-config-$currentEnvironment-0.0.${BUILD_NUMBER}.tgz")
                if (env.currentEnvironment != 'prod' && fileExists('pact-versions.md')) {
                  artifactory.upload("pact-versions.md", "maximus-general/versions/id/$domainName/$currentEnvironment/pact-versions.md")
                }
              }
            },

            "Publish deploy helm chart": {
              dir("$deployRepositoryName") {
                script {
                  def releaseTag
                  releaseTag = env.BUILD_NUMBER
                  writeFile(file: "$currentEnvironment-versions.txt", text: releaseTag)
                  artifactory.upload("$currentEnvironment-versions.txt", "maximus-general/versions/id/$domainName/$currentEnvironment/$currentEnvironment-versions.txt")
                  sh "mv $domainName $domainName-$currentEnvironment; helm package $domainName-${currentEnvironment} --version=$releaseTag --app-version=$releaseTag"
                  artifactory.upload("$domainName-${releaseTag}.tgz", "maximus-helm-local/dev-$domainName/$currentEnvironment/$domainName-${currentEnvironment}-0.0.${releaseTag}.tgz")
                  uploadVersionsFilePath = "maximus-general/versions/id/$domainName/$currentEnvironment/versions.md"
                  artifactory.upload("versions.md", "$uploadVersionsFilePath")
                }
              }
            },
            "Publish Ratelimit Version": {
              script {
                if( env.rateLimitExists != "0" ) {
                writeFile(file: "$currentEnvironment-ratelimit.txt", text: rateLimitVersion)
                artifactory.upload("$currentEnvironment-ratelimit.txt", "maximus-general/versions/id/$domainName/${currentEnvironment}/${currentEnvironment}-ratelimit.txt")
                }
              }
            }
          )
        }
      }
    }

    post {
      always {
        script {
          if (config.currentEnvironment == "sit") {
            build job: "QA/$domainName-qa-deploy", wait: false
          }
          if (currentEnvironment == "prod" && domainName != "identity") {
              sh "kubectl scale deploy -l domain=$domainName --replicas=0 -n $serviceNamespace"
            }

          if (currentEnvironment == "prod" && domainName != 'cards' && domainName != 'discount') {
            sh "kubectl scale deploy -l domain=$domainName --replicas=0 -n $uiNamespace"
          }
          archiveArtifacts artifacts: "$deployRepositoryName/versions.md", fingerprint: true, allowEmptyArchive: true
          archiveArtifacts artifacts: "pact-versions.md", fingerprint: true, allowEmptyArchive: true
          archiveArtifacts artifacts: "$config.currentEnvironment-config.txt", fingerprint: true, allowEmptyArchive: true
          archiveArtifacts artifacts: "$config.upstreamEnvironment-config.txt", fingerprint: true, allowEmptyArchive: true
        }
      }
    }

  }
}


def runDomainTests(String domainName, String currentEnvironment, String enableRegressionTests) {
  if (currentEnvironment == 'sit') {
    build job: "SIT/$domainName-sit-run-smoke"
  }
  if (currentEnvironment == 'qa' && enableRegressionTests == 'true') {
    build job: "QA/$domainName-qa-regression"
  }  else if (currentEnvironment == 'prodsandbox') {
    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
      build job: "PRODSANDBOX/$domainName-prodsandbox-run-smoke", propagate: false
    }
    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
      build job: "PRODSANDBOX/$domainName-prodsandbox-regression", propagate: false
    }
  } else {
    echo "No tests added for this env $domainName"
  }
}

def isTriggeredByBackwardCompatibility(String domainName){
  echo "getting upstream cause"
  def upstreamPipeline
  def lastBuildCause = Jenkins.instance.getItemByFullName("PERF/$domainName-perf-deploy").getLastBuild().getCause(hudson.model.Cause$UpstreamCause)
  if (lastBuildCause != null)
    upstreamPipeline = lastBuildCause.getUpstreamProject()
  else 
    upstreamPipeline = "Not triggered by backward compatibility pipeline" 
  echo "$upstreamPipeline"
  return upstreamPipeline == "PERF/maximus-perf-backward-compatibility"
}

def getConfigHelmChartForBackwardCompatibility(Artifactory artifactory, Helm helm , String domainName){
  configPath = "maximus-general/versions/id/$domainName/backward-compatibility/backward-compatibility-config.txt"
  artifactory.getArtifact(configPath)
  configArtifactVersion = sh(returnStdout: true, script: "cat backward-compatibility-config.txt").trim()
  configHelmChartPath = "https://artifactory.axisb.com/artifactory/maximus-helm-local/dev-$domainName/config/$domainName-config-backward-compatibility-0.0.${configArtifactVersion}.tgz"
  helm.fetch(configHelmChartPath)
  sh "mkdir config ; tar -xvf $domainName-config-backward-compatibility-0.0.${configArtifactVersion}.tgz -C config; cd config"
}
