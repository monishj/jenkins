package com.axis.maximus.jenkins.library

class Helm {
    def steps
    def artifactoryUsername
    def artifactoryPassword

    Helm(steps) {
        this.artifactoryUsername = steps.env.ARTIFACTORY_USERNAME
        this.artifactoryPassword = steps.env.ARTIFACTORY_PASSWORD
        this.steps = steps
    }

    def addRepo() {
        steps.sh "helm repo add --pass-credentials stable https://artifactory.axisb.com/artifactory/maximus-helm-virtual --username $artifactoryUsername --password $artifactoryPassword"
        steps.sh "helm repo update"
    }

    def addCustomRepo(String repoName) {
        steps.sh "helm repo add --pass-credentials stable https://artifactory.axisb.com/artifactory/$repoName  --username $artifactoryUsername --password $artifactoryPassword"
        steps.sh "helm repo update"
    }

    def updateHelmDependency(String chartLocation) {
        steps.sh "cd $chartLocation && helm dependency update"
    }

    def deploy(Map config = [:]) {
        if(config.version){
            steps.sh "set -x;helm upgrade $config.releaseName $config.chartLocation --version $config.version --install --namespace $config.namespace $config.addExtra"
        }
        else {
            steps.sh "set -x;helm upgrade $config.releaseName $config.chartLocation -f $config.valuesFileLocation --install --namespace $config.namespace $config.addExtra"
        }
    }

    def fetch(String chartPath) {
        steps.sh "helm fetch $chartPath --username $artifactoryUsername --password $artifactoryPassword"
    }

    def packageChart(String serviceName, String version){
        steps.sh "helm package ${serviceName} --version ${version} --app-version ${version}"
    }

    def uploadChart(String helmReleasePackage, String artifactoryHost, String packageLocation) {
        steps.sh "curl -k -u $artifactoryUsername:$artifactoryPassword -T $helmReleasePackage \"$artifactoryHost/$packageLocation\""
    }

    def releaseChart(String domainName, String packageName, String serviceVersion, String releaseVersion,String upstreamEnvironment) {
        String artifactoryHost = "https://artifactory.axisb.com"

        String helmPackage = "$packageName-${serviceVersion}.tgz"
        String helmReleasePackage = "$packageName-${releaseVersion}.tgz"
        steps.sh "echo -e '#### Helm package ${helmPackage} will be re uploaded with name ${helmReleasePackage} ####'"
        steps.sh "echo -e '#### Service version in helm values.yaml will be changed from ${serviceVersion} to $releaseVersion ####'"
        fetch("$artifactoryHost/artifactory/maximus-helm-virtual/dev-$domainName/$upstreamEnvironment/$packageName/$helmPackage")
        steps.sh "tar -xvf $helmPackage"
          steps.sh "sed -i \$\"s/tag:.*/tag: $releaseVersion/\" $packageName/values.yaml"

        packageChart(packageName, releaseVersion)
        String packageLocation = "artifactory/maximus-helm-local/dev-$domainName/$domainName/$packageName/$helmReleasePackage"
        uploadChart(helmReleasePackage, artifactoryHost, packageLocation)
    }

    def deleteRelease(Map config = [:]){
        def releaseExists = steps.sh(script: "helm list -n $config.namespace | grep -E '^$config.releaseName' ", returnStatus: true)
        if(releaseExists == 0){
            steps.sh "helm delete $config.releaseName -n $config.namespace"
        }
    }
}
