package com.axis.maximus.jenkins.library

class Build implements Serializable {

  def steps
  def artifactoryUsername
  def artifactoryPassword
  def checkmarxPassword
    def pactBrokerUser
    def pactBrokerPassword

  Build(steps) {
    this.artifactoryUsername = steps.env.ARTIFACTORY_USERNAME
    this.artifactoryPassword = steps.env.ARTIFACTORY_PASSWORD
    this.pactBrokerUser = steps.env.PACT_BROKER_USERNAME
    this.pactBrokerPassword = steps.env.PACT_BROKER_PASSWORD
    this.checkmarxPassword = steps.env.CHECKMARX_PASSWORD
    this.steps = steps
  }

  def backendBuild() {
    steps.sh "echo $artifactoryUsername"
    steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh "./gradlew build docker --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true"
  }

  def backendBuildKaniko(String serviceName) {
    steps.sh "echo $artifactoryUsername"
    // steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh "./gradlew build --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true"
    steps.sh "./gradlew unpack -x test --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true"
    steps.sh "rm -f .dockerignore"
    // steps.sh "docker build -t artifactory.axisb.com/docker/$serviceName -f Dockerfile --build-arg DEPENDENCY=/build/dependency ."
  }

  def backendBuildMultiArch() {
    steps.sh "echo $artifactoryUsername"
    steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh "./gradlew build docker maximusArmDocker --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true"
  }

  def backendBuildMultiArchKaniko(String serviceName) {
    steps.sh "echo $artifactoryUsername"
    // steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh "./gradlew build --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true"
    steps.sh "./gradlew unpack -x test --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true"
    steps.sh "rm -f .dockerignore"
    // steps.sh "docker build -t artifactory.axisb.com/docker/$serviceName:latest-arm -f DockerfileWithArm --build-arg DEPENDENCY=/build/dependency . --platform=linux/arm64"
    // steps.sh "docker build -t artifactory.axisb.com/docker/$serviceName -f Dockerfile --build-arg DEPENDENCY=/build/dependency ."
    }

  def runSonarqube(String serviceName) {
    steps.sh "./gradlew sonarqube -Dsonar.projectKey=$serviceName -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword"
  }

  def qualityGateCheck(String qg) {
    if (qg != 'OK') {
      error "Pipeline aborted due to quality gate failure: ${qg}"
    } else {
      steps.sh "echo 'Quality gate has been checked and it is fine'"
    }
  }

  Boolean providerVerificationTaskExists() {
    steps.sh "./gradlew tasks --all -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword > tasks.txt"
    def task = steps.sh(returnStdout: true, script: "cat tasks.txt | grep runProviderVerificationTests", returnStatus: true)
    if (task == 0) return true else return false

  }

  def backendBuildWithoutContractTest(String gitSha) {
    steps.sh "echo $artifactoryUsername"
    steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh "./gradlew build docker -x runProviderVerificationTests --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true -PcommitHash=$gitSha"
  }

  def backendBuildWithoutContractTestKaniko(String gitSha, String serviceName) {
    steps.sh "echo $artifactoryUsername"
    // steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh "./gradlew build -x runProviderVerificationTests --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true -PcommitHash=$gitSha"
    steps.sh "./gradlew unpack -x test -x runProviderVerificationTests --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true"
    steps.sh "rm -f .dockerignore"
    // steps.sh "docker build -t artifactory.axisb.com/docker/$serviceName -f Dockerfile --build-arg DEPENDENCY=/build/dependency ."
  }

  def backendBuildWithoutContractTestMultiArchKaniko(String gitSha, String serviceName) {
  steps.sh "echo $artifactoryUsername"
  // steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
  steps.sh "./gradlew build -x runProviderVerificationTests --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true -PcommitHash=$gitSha"
  steps.sh "./gradlew unpack -x test -x runProviderVerificationTests --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true"
  steps.sh "rm -f .dockerignore"
  // steps.sh "docker build -t artifactory.axisb.com/docker/$serviceName:latest-arm -f DockerfileWithArm --build-arg DEPENDENCY=/build/dependency . --platform=linux/arm64"
  // steps.sh "docker build -t artifactory.axisb.com/docker/$serviceName -f Dockerfile --build-arg DEPENDENCY=/build/dependency ."
  }

  def backendBuildWithoutContractTestMultiArch(String gitSha) {
    steps.sh "echo $artifactoryUsername"
    steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh "./gradlew build docker maximusArmDocker -x runProviderVerificationTests --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true -PcommitHash=$gitSha"
  }

  def backEndBuildWithContractTest(String providerVersion) {
    steps.sh "echo $artifactoryUsername"
    steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh "./gradlew build docker --stacktrace -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -Ppact.verifier.publishResults=true -Ppact.provider.version=$providerVersion"
  }

  def runBackEndProviderVerificationTest(String providerVersion, String branch) {
    steps.sh "./gradlew runProviderVerificationTests " +
      "-PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword " +
          "-Ppact.verifier.publishResults=true -Ppact.provider.version=$providerVersion " +
          "-Ppactbroker.auth.username=$pactBrokerUser -Ppactbroker.auth.password=$pactBrokerPassword " +
          "-Ppact.provider.branch=$branch -Ppactbroker.enablePending=true -Ppactbroker.providerBranch=$branch -Ppactbroker.consumerversionselectors.rawjson='[{\"mainBranch\": true}, {\"deployed\": true}]'"
  }

  def runBackEndProviderVerificationTestForSIT(String providerVersion, String branch) {
    steps.sh "./gradlew runProviderVerificationTests " +
      "-PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword " +
      "-Ppact.verifier.publishResults=true -Ppact.provider.version=$providerVersion " +
      "-Ppactbroker.auth.username=$pactBrokerUser -Ppactbroker.auth.password=$pactBrokerPassword " +
      "-Ppact.provider.branch=$branch -Ppactbroker.enablePending=true -Ppactbroker.providerBranch=$branch -Ppactbroker.consumerversionselectors.rawjson='[{\"mainBranch\": true}, {\"environment\": \"SIT\"}]'"
  }

  def runWebhookProviderVerificationTest(String providerVersion, String consumerService, String pactUrl) {
    steps.sh "./gradlew runProviderVerificationTests " +
      "-PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword " +
      "-Ppact.verifier.publishResults=true -Ppact.provider.version=$providerVersion " +
                "-Ppact.filter.consumers=$consumerService " +
                "-Ppactbroker.auth.username=$pactBrokerUser -Ppactbroker.auth.password=$pactBrokerPassword " +
                "-Ppact.filter.pacturl=$pactUrl -Ppact.provider.branch=master"
  }

  def frontendLintCheck(){
    steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh '''#!/bin/bash
        set -e
        node -v
        yarn -v
        npm config set registry https://artifactory.axisb.com/artifactory/api/npm/npm/
        yarn config set registry https://artifactory.axisb.com/artifactory/api/npm/npm/
        npm config set strict-ssl false
        yarn config set strict-ssl false
        echo "_auth='${NPM_ARTIFACTORY_TOKEN}'" >> ~/.npmrc
        echo "always-auth=true" >> ~/.npmrc
        yarn install
        yarn lint:check
        '''
  }

  // TODO: Use methods for uploading artifact and building docker images
  def frontendBuild() {
    steps.sh '''
        yarn test
        yarn build
        set -e
        NODE_MODULE_DIR="./node_modules/node-sass/vendor/linux_musl-x64-67/"
        mkdir -p "${NODE_MODULE_DIR}" 
        curl -ko "${NODE_MODULE_DIR}linux_musl-x64-67_binding.node"  https://artifactory.axisb.com/artifactory/node-sass-bindings/v4.10.0/linux_musl-x64-67_binding.node
        '''
  }

  def maximusSeedValidatorBuild(imageTag) {
    steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh '''#!/bin/bash
        set -e
        cd ./validator
        node -v
        yarn -v
        npm config set registry https://artifactory.axisb.com/artifactory/api/npm/npm/
        yarn config set registry https://artifactory.axisb.com/artifactory/api/npm/npm/
        npm config set strict-ssl false
        yarn config set strict-ssl false
        echo "_auth='${NPM_ARTIFACTORY_TOKEN}'" >> ~/.npmrc
        echo "always-auth=true" >> ~/.npmrc
        yarn install
        yarn build
        set -e
        NODE_MODULE_DIR="./node_modules/node-sass/vendor/linux_musl-x64-67/"
        mkdir -p "${NODE_MODULE_DIR}" 
        curl -ko "${NODE_MODULE_DIR}linux_musl-x64-67_binding.node"  https://artifactory.axisb.com/artifactory/node-sass-bindings/v4.10.0/linux_musl-x64-67_binding.node
        docker build . --tag artifactory.axisb.com/docker/maximus-seed-validator:latest
        docker push artifactory.axisb.com/docker/maximus-seed-validator:latest 
        docker tag artifactory.axisb.com/docker/maximus-seed-validator:latest artifactory.axisb.com/docker/maximus-seed-validator:${imageTag}
        docker push artifactory.axisb.com/docker/maximus-seed-validator:${imageTag}
        '''
  }

  // TODO: Use methods for uploading artifact and building docker images
  def maximusDeveloperPortalBuild() {
    steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    steps.sh '''#!/bin/bash
        set -e
        node -v
        yarn -v
        npm config set registry https://artifactory.axisb.com/artifactory/api/npm/npm/
        yarn config set registry https://artifactory.axisb.com/artifactory/api/npm/npm/
        npm config set strict-ssl false
        yarn config set strict-ssl false
        echo "_auth='${NPM_ARTIFACTORY_TOKEN}'" >> ~/.npmrc
        echo "always-auth=true" >> ~/.npmrc
        echo "Building Maximus Developer Portal"
        yarn install --frozen-lockfile
        yarn tsc
        yarn build
        set -e
        NODE_MODULE_DIR="./node_modules/node-sass/vendor/linux_musl-x64-67/"
        mkdir -p "${NODE_MODULE_DIR}"
        curl -ko "${NODE_MODULE_DIR}linux_musl-x64-67_binding.node"  https://artifactory.axisb.com/artifactory/node-sass-bindings/v4.10.0/linux_musl-x64-67_binding.node
        docker build --build-arg ARTIFACTORY_USERNAME=${ARTIFACTORY_USERNAME} --build-arg  ARTIFACTORY_PASSWORD=${ARTIFACTORY_PASSWORD} . -f packages/backend/Dockerfile --network host -t artifactory.axisb.com/docker/maximus-developer-portal:latest
        '''
  }

  def frontendDomainBuild() {
    steps.sh '''#!/bin/bash
        set -e
        node -v
        yarn -v
        npm config set registry https://artifactory.axisb.com/artifactory/api/npm/npm/
        yarn config set registry https://artifactory.axisb.com/artifactory/api/npm/npm/
        npm config set strict-ssl false
        yarn config set strict-ssl false
        echo "_auth='${NPM_ARTIFACTORY_TOKEN}'" >> ~/.npmrc
        echo "always-auth=true" >> ~/.npmrc
        yarn install
        yarn test
        set -e
        NODE_MODULE_DIR="./node_modules/node-sass/vendor/linux_musl-x64-67/"
        mkdir -p "${NODE_MODULE_DIR}"
        curl -ko "${NODE_MODULE_DIR}linux_musl-x64-67_binding.node"  https://artifactory.axisb.com/artifactory/node-sass-bindings/v4.10.0/linux_musl-x64-67_binding.node
        yarn build
        '''
  }

  def commonBuild() {
    if (steps.fileExists("gradlew")) {
      steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
      steps.sh "./gradlew build --info -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword"
    } else {
      steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
      steps.sh "gradle build --info -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword"
    }
  }

  def commonBuildKaniko() {
    if (steps.fileExists("gradlew")) {
      // steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
      steps.sh "./gradlew build --info -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword"
    } else {
      // steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
      steps.sh "gradle build --info -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword"
    }
  }

  def starterBuild() {
    steps.sh "./gradlew clean build artifactoryPublish -Puser=$artifactoryUsername -Ppassword=$artifactoryPassword -PmavenUser=$artifactoryUsername   -PmavenPassword=$artifactoryPassword -PpublishProducerTopics=true --stacktrace"
  }

  def smoke(String serviceName) {
    steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword artifactory.axisb.com"
    if (serviceName == "maximus-smoke-test" || serviceName == "personal-loan-smoke-test" || serviceName == "home-loan-smoke-test" || serviceName == "kisan-credit-card-smoke-tests") {
      steps.sh '''#!/bin/bash
          curl -k -u${artifactoryUsername}:${artifactoryPassword} -o cypress-13.6.4.zip  https://artifactory.axisb.com/artifactory/maximus-general/cypress/cypress-13.6.4.zip
          docker build --no-cache . --tag artifactory.axisb.com/docker/${SERVICE}:latest --tag artifactory.axisb.com/docker/${SERVICE}:${IMAGE_TAG}  --network="host" --build-arg NPM_ARTIFACTORY_TOKEN=${NPM_ARTIFACTORY_TOKEN}
          '''
    }
    else {
      steps.sh '''#!/bin/bash
          curl -k -u${artifactoryUsername}:${artifactoryPassword} -o cypress-7.2.0-linux-x64.zip  https://artifactory.axisb.com/artifactory/maximus-general/cypress/cypress-7.2.0-linux-x64.zip
          docker build --no-cache . --tag artifactory.axisb.com/docker/${SERVICE}:latest --tag artifactory.axisb.com/docker/${SERVICE}:${IMAGE_TAG}  --network="host" --build-arg NPM_ARTIFACTORY_TOKEN=${NPM_ARTIFACTORY_TOKEN}
          '''
    }
  }

  def runCheckmarx(String branchName, String serviceName, Boolean checkmarxEnabled = true) {
    if (checkmarxEnabled) {
      steps.step([$class: 'CxScanBuilder', comment: "branch:$branchName,$serviceName", configAsCode: true, credentialsId: 'CHECKMARX_CREDENTIAL', customFields: '', dependencyScanConfig: [dependencyScanExcludeFolders: '', dependencyScanPatterns: '''!**/node_modules/**/*''', dependencyScannerType: 'OSA', enableScaResolver: 'MANIFEST', fsaVariables: '', osaArchiveIncludePatterns: '.zip, *.war, *.ear, *.tgz', osaInstallBeforeScan: true, overrideGlobalConfig: true, pathToScaResolver: '', sastCredentialsId: '', scaAccessControlUrl: 'https://platform.checkmarx.net', scaConfigFile: '', scaCredentialsId: '', scaEnvVariables: '', scaResolverAddParameters: '', scaSASTProjectFullPath: '', scaSASTProjectID: '', scaSastServerUrl: '', scaServerUrl: 'https://api-sca.checkmarx.net', scaTeamPath: '', scaTenant: '', scaWebAppUrl: 'https://sca.checkmarx.net'], excludeFolders: '', exclusionsSetting: 'job', failBuildOnNewResults: false, failBuildOnNewSeverity: 'HIGH', filterPattern: '''!**/_cvs/**/, !**/.svn/**/, !**/.hg/**/, !**/.git/**/, !**/.bzr/**/, !**/*.spec.js, !**/*.spec.jsx, !**/*.spec.js.snap, !**/*.spec.jsx.snap, !**/*.test.js, !**/*.test.js.snap, !**/test/,
        !**/.gitgnore/**/, !**/.gradle/**/, !**/.checkstyle/**/, !**/.classpath/**/, !**/bin/**/*,
        !**/obj/**/, !**/backup/**/, !**/.idea/**/, !**/.DS_Store, !**/.ipr, !**/.iws,
        !**/.bak, !**/.tmp, !**/.aac, !**/.aif, !**/.iff, !**/.m3u, !**/.mid, !**/.mp3,
        !**/.mpa, !**/.ra, !**/.wav, !**/.wma, !**/.3g2, !**/.3gp, !**/.asf, !**/.asx,
        !**/.avi, !**/.flv, !**/.mov, !**/.mp4, !**/.mpg, !**/.rm, !**/.swf, !**/.vob,
        !**/.wmv, !**/.bmp, !**/.gif, !**/.jpg, !**/.png, !**/.psd, !**/.tif, !**/.swf,
        !**/.jar, !**/.zip, !**/.rar, !**/.exe, !**/.dll, !**/.pdb, !**/.7z, !**/.gz,
        !**/.tar.gz, !**/.tar, !**/.gz, !**/.ahtm, !**/.ahtml, !**/.fhtml, !**/*.hdm,
        !**/.hdml, !**/.hsql, !**/.ht, !**/.hta, !**/.htc, !**/.htd, !**/.war, !**/.ear,
        !**/.htmls, !**/.ihtml, !**/.mht, !**/.mhtm, !**/.mhtml, !**/.ssi, !**/*.stm,
        !**/.bin,!**/.lock,!**/.svg,!**/.obj,
        !**/.stml, !**/.ttml, !**/.txn, !**/.xhtm, !**/.xhtml, !**/.class, !**/.iml, !Checkmarx/Reports/.*,
        !OSADependencies.json, !**/node_modules/**/*''', fullScanCycle: 10, generatePdfReport: true, groupId: '25', password: "$checkmarxPassword", preset: '36', projectName: 'Maximus_Gocd', sastEnabled: true, serverUrl: 'https://checkmarkx_vs.axisb.com', sourceEncoding: '6', useOwnServerCredentials: true, username: '', vulnerabilityThresholdResult: 'FAILURE'])
    } else {
      steps.sh "echo 'Checkmarx is disabled'"
    }
  }

  def validateConfigSchema() {
    steps.sh '''
            cd maximus-config
            env="qa sandbox hotfix uat perf prod prodsandbox"
            for en in $env;
            do
                echo $en
                    echo "Validate $en schema with SIT..."
                    differz show values-sit-new.yaml values-$en-new.yaml
                RSP=`differz show values-sit-new.yaml values-$en-new.yaml`
                if [ -z "$RSP" ]
                then
                    echo "Schema for $en-new is successfully validated."
                else
                    echo $RSP
                    echo "Failed to validate schema for $en-new."
                exit 1
                fi
            done
        '''
  }

  def publishConfig(String imageTag, String branch, String publishEnv) {
    steps.sh "helm package maximus-config --version=0.0.$imageTag --app-version=$imageTag"
    if (publishEnv == "build") {
      steps.sh "curl -f -u$artifactoryUsername:$artifactoryPassword -T maximus-config-0.0.${imageTag}.tgz  'https://artifactory.axisb.com/artifactory/maximus-helm-local/dev-maximus/config/maximus-config-0.0.${imageTag}.tgz'"
    }
    steps.sh "curl -f -u$artifactoryUsername:$artifactoryPassword -T maximus-config-0.0.${imageTag}.tgz  'https://artifactory.axisb.com/artifactory/maximus-helm-local/dev-maximus/config/maximus-config-$publishEnv-0.0.${imageTag}.tgz'"
    steps.sh "echo $imageTag > $publishEnv-config.txt"
    if (publishEnv != "build" || branch == "master")
      steps.sh "curl -f -u$artifactoryUsername:$artifactoryPassword -T $publishEnv-config.txt https://artifactory.axisb.com/artifactory/maximus-general/versions/$publishEnv/$publishEnv-config.txt"
  }

  def validateDomainConfigSchema() {
    steps.sh '''
            env="qa sandbox hotfix uat perf prod sandboxnew prodsandbox"
            for en in $env;
            do
                echo $en
                    echo "Validate $en schema with SIT..."
                    differz show values-sit.yaml values-$en.yaml
                RSP=`differz show values-sit.yaml values-$en.yaml`
                if [ -z "$RSP" ]
                then
                    echo "Schema for $en is successfully validated."
                else
                    echo $RSP
                    echo "Failed to validate schema for $en."
                exit 1
                fi
            done
        '''
  }

  def publishDomainConfig(String domainName, String publishEnv, String imageTag) {
    steps.sh "helm package $domainName-config --version=0.0.$imageTag --app-version=$imageTag"
    if (publishEnv == "build")
      steps.sh "curl -f -u$artifactoryUsername:$artifactoryPassword -T $domainName-config-0.0.${imageTag}.tgz  'https://artifactory.axisb.com/artifactory/maximus-helm-local/dev-$domainName/config/$domainName-config-0.0.${imageTag}.tgz'"
    steps.sh "curl -f -u$artifactoryUsername:$artifactoryPassword -T $domainName-config-0.0.${imageTag}.tgz  'https://artifactory.axisb.com/artifactory/maximus-helm-local/dev-$domainName/config/$domainName-config-$publishEnv-0.0.${imageTag}.tgz'"
    steps.sh "echo $imageTag > $publishEnv-config.txt"
    steps.sh "curl -f -u$artifactoryUsername:$artifactoryPassword -T $publishEnv-config.txt https://artifactory.axisb.com/artifactory/maximus-general/versions/id/$domainName/$publishEnv/$publishEnv-config.txt"
  }

  String getLibraryVersion() {
    return steps.sh(returnStdout: true, script: "./gradlew properties -PmavenUser=$artifactoryUsername -PmavenPassword=$artifactoryPassword -q | grep version: | awk '{print \$2}'").trim()

  }

}
