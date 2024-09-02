package com.axis.maximus.jenkins.library

class Pact implements Serializable {

  def steps
  def artifactoryUsername
  def artifactoryPassword
  def checkmarxPassword
  def pactBrokerUser
  def pactBrokerPassword
  def domainName
  def upstreamEnvironment
  def PACT_ENV

  Pact(steps) {
    this.artifactoryUsername = steps.env.ARTIFACTORY_USERNAME
    this.artifactoryPassword = steps.env.ARTIFACTORY_PASSWORD
    this.pactBrokerUser = steps.env.PACT_BROKER_USERNAME
    this.pactBrokerPassword = steps.env.PACT_BROKER_PASSWORD
    this.checkmarxPassword = steps.env.CHECKMARX_PASSWORD
    this.steps = steps
    this.PACT_ENV = steps.env.PACT_ENV
  }

  def canIDeploy(int retryCount) {

    def parallelSteps = [:]
    steps.readFile('pact-versions.md').readLines()
      .each { service ->
        def pacticipant = service.split(':')[0]
        def version = service.split(':')[1]
        parallelSteps[pacticipant] = { ->
          steps.sh "pact-broker can-i-deploy --pacticipant=$pacticipant --version=$version --to-environment=$PACT_ENV --retry-while-unknown=$retryCount"
        }
      }
    steps.parallel parallelSteps

  }

  def recordDeployment() {
    steps.sh '''
      if [ ! -e pact-versions.md ] 
      then 
        echo "FAIL :: pact-version.md is not present"
        exit 1
      fi
    '''
    def versions = steps.readFile('pact-versions.md').readLines()
    def hasFailure = false
    for (service in versions) {
      def pacticipant = service.split(':')[0]
      def version = service.split(':')[1]
      def statusCode = steps.sh returnStatus: true, script: "pact-broker record-deployment --pacticipant=$pacticipant --version=$version --environment=$PACT_ENV"
      if (statusCode != 0) hasFailure = true
    }
    if (hasFailure) steps.sh 'exit 1'
  }
}
