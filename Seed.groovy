package com.axis.maximus.jenkins.library

class Seed {
  def steps
  def artifactory
  def git
  def upstreamEnvironment
  def helm
  def currentEnvironment
  def util
  def domainName

  Seed(steps, artifactory, git, upstreamEnvironment, helm, currentEnvironment, util, domainName) {
    this.steps = steps
    this.artifactory = artifactory
    this.git = git
    this.upstreamEnvironment = upstreamEnvironment
    this.helm = helm
    this.currentEnvironment = currentEnvironment
    this.util = util
    this.domainName = domainName
  }

  def deploy(taskNamespace, forceUpdate, seedOverrideEnvironment) {
    GString path = "maximus-general/versions/id/$domainName/$upstreamEnvironment/seed_versions.md"
    artifactory.getArtifact(path)
    def versions = steps.readFile('seed_versions.md').readLines()
    for (serviceVersionEntry in versions) {
      def (String serviceName, String version) = util.getServiceNameAndVersion("$serviceVersionEntry")
      helm.deleteRelease(releaseName: "$currentEnvironment-$serviceName", namespace: taskNamespace)
      GString seedHelmChartPath = "$serviceName-seed-${version}.tgz"
      GString configHelmChartPath = "https://artifactory.axisb.com/artifactory/maximus-helm-local/dev-$domainName/$upstreamEnvironment/$serviceName-seed/$seedHelmChartPath"
      helm.fetch(configHelmChartPath)
      steps.sh("mkdir seed ; tar -xvf $seedHelmChartPath -C seed")
      def updatedCurrentEnvironment = currentEnvironment
      if (seedOverrideEnvironment != '') updatedCurrentEnvironment = seedOverrideEnvironment
      GString addExtra = "--set environment.name=$currentEnvironment  --set environment.forceUpdate=$forceUpdate --set environment.seedEnvironment=$updatedCurrentEnvironment --debug"
      helm.deploy([env      : "$currentEnvironment",
          namespace         : taskNamespace,
          releaseName       : "$currentEnvironment-$serviceName",
          chartLocation     : "seed/$serviceName-seed",
          valuesFileLocation: "seed/$serviceName-seed/values.yaml",
          addExtra          : addExtra])
      steps.sh("rm -r seed")
    }
  }

  def deployMaximusSeedValidatorSeed(taskNamespace, forceUpdate, seedOverrideEnvironment, helmSecret) {
    GString path = "maximus-general/versions/id/$domainName/$upstreamEnvironment/seed_versions.md"
    artifactory.getArtifact(path)
    def versions = steps.readFile('seed_versions.md').readLines()
    for (serviceVersionEntry in versions) {
      def (String serviceName, String version) = util.getServiceNameAndVersion("$serviceVersionEntry")
      helm.deleteRelease(releaseName: "$currentEnvironment-$serviceName", namespace: taskNamespace)
      GString seedHelmChartPath = "$serviceName-seed-${version}.tgz"
      GString configHelmChartPath = "https://artifactory.axisb.com/artifactory/maximus-helm-local/dev-$domainName/$upstreamEnvironment/$serviceName-seed/$seedHelmChartPath"
      helm.fetch(configHelmChartPath)
      steps.sh("mkdir seed ; tar -xvf $seedHelmChartPath -C seed")
      def updatedCurrentEnvironment = currentEnvironment
      if (seedOverrideEnvironment != '') updatedCurrentEnvironment = seedOverrideEnvironment
      GString addExtra = "--set environment.name=$currentEnvironment  --set environment.forceUpdate=$forceUpdate --set environment.seedEnvironment=$updatedCurrentEnvironment $helmSecret --debug"
      helm.deploy([env      : "$currentEnvironment",
                   namespace         : taskNamespace,
                   releaseName       : "$currentEnvironment-$serviceName",
                   chartLocation     : "seed/$serviceName-seed",
                   valuesFileLocation: "seed/$serviceName-seed/values.yaml",
                   addExtra          : addExtra])
      steps.sh("rm -r seed")
    }
  }



  def checkDeploymentStatus(taskNamespace) {
    GString path = "maximus-general/versions/id/$domainName/$upstreamEnvironment/seed_versions.md"
    artifactory.getArtifact(path)
    def versions = steps.readFile('seed_versions.md').readLines()
    for (serviceVersionEntry in versions) {
      def (String serviceName) = util.getServiceNameAndVersion("$serviceVersionEntry")
      steps.sh("kubectl wait --for=condition=complete --timeout=5m job/$serviceName-seed -n $taskNamespace")
      steps.sh("kubectl logs job/$serviceName-seed -n $taskNamespace")
    }
  }

  def tag(releaseWay) {
    steps.sh "echo \"Tagging services seed artifact\""
    artifactory.getArtifact("maximus-general/versions/id/$domainName/$upstreamEnvironment/seed_versions.md")
    artifactory.getArtifact("maximus-general/versions/$domainName/prod-versions/prod_seed_versions.md")
    steps.sh "echo \"#### Services that will be included in this release are, all others will be excluded ####\""
    steps.sh "cat services_included_in_release.m4 | cut -d ':' -f1"
    steps.sh "m4 seed_versions.md ./services_included_in_release.m4 | grep -v \"^\$\" > services_included_in_release.txt"
    def prodVersionsFileExists = steps.sh(returnStatus: true, script: "cat prod_seed_versions.md | grep errors")

    boolean isFirstRelease = true
    if (prodVersionsFileExists != 0) {
      isFirstRelease = false
      steps.sh "m4 prod_seed_versions.md ./services_included_in_release.m4 | grep -v \"^\$\" > services_in_prod.txt"
      steps.sh "cat services_in_prod.txt"
    }
    if (isFirstRelease) {
      releaseWay = "MAJOR"
    }

    if (upstreamEnvironment == "hotfix") {
      releaseWay = "PATCH"
    }
    steps.sh "echo RELEASE way: $releaseWay"
    def lines = steps.readFile('services_included_in_release.txt').readLines()
    def releaseTag
    for (line in lines) {
      def releaseVersion
      def lineText = line.split(':')
      def serviceName = lineText[0]
      GString seedName = "$serviceName-seed"
      def serviceVersion = lineText[1]
      if (serviceVersion.split("_").contains("VERSION") == false) {
        def commitSha = serviceVersion.split("-")[1]
        def (String version, String gitSha) = isFirstRelease ? [serviceVersion, commitSha] :
          steps.sh(returnStdout: true, script: "cat services_in_prod.txt | grep $serviceName | cut -d ':' -f2").tokenize("-")
        steps.sh "echo version - $version gitSha - $gitSha"
        if (releaseWay == "MAJOR" || commitSha != gitSha) {
          releaseTag = getReleaseTag(isFirstRelease, releaseWay, version)
          artifactory.tagManifest("docker/$domainName/$seedName", serviceVersion, releaseTag)
          git.tagRepoCommit(serviceName, commitSha, releaseTag)
          releaseVersion = "$releaseTag-$commitSha"
          helm.releaseChart(domainName, seedName, serviceVersion, releaseVersion, upstreamEnvironment)
        } else {
          steps.sh "echo 'no update in the $serviceName'"
          releaseVersion = prodVersionEntry
        }
        def serviceKey = steps.sh(returnStdout: true, script: "cat services_included_in_release.m4 | grep '$serviceName' | cut -d':' -f2").trim()
        steps.sh "echo 'define($serviceKey, $releaseVersion)' >> seed_release_versions.md"
      }
    }
    artifactory.getArtifact("maximus-general/versions/id/$domainName/$currentEnvironment/seed-tag.txt")

    releaseTag = isFirstRelease ? "" : steps.sh(script: "cat seed-tag.txt", returnStdout: true).trim()
    if (releaseWay == "MAJOR") {
      releaseTag = getReleaseTag(isFirstRelease, "MAJOR", releaseTag)
      steps.sh("echo '$releaseTag' > seed-tag.txt")
    }
    artifactory.upload("seed-tag.txt", "maximus-general/versions/id/$domainName/$currentEnvironment/seed-tag.txt")
    artifactory.upload("seed_release_versions.md", "maximus-general/versions/id/$domainName/$currentEnvironment/seed-versions-${releaseTag}.md")
  }

  def publishArtifacts() {
    GString path = "maximus-general/versions/id/$domainName/$upstreamEnvironment/seed_versions.md"
    artifactory.getArtifact(path)
    def versions = steps.readFile('seed_versions.md').readLines()
    for (serviceVersionEntry in versions) {
      def (String serviceName, String version) = util.getServiceNameAndVersion("$serviceVersionEntry")
      GString seedHelmChartPath = "$serviceName-seed-${version}.tgz"
      GString helmChartPath = "maximus-helm-local/dev-$domainName/$currentEnvironment/$serviceName-seed/$seedHelmChartPath"
      artifactory.upload(seedHelmChartPath, helmChartPath)
    }
    GString uploadVersionsFilePath = "maximus-general/versions/id/$domainName/$currentEnvironment/seed_versions.md"
    artifactory.upload("seed_versions.md", "$uploadVersionsFilePath")
  }

  private def incrementNumber(String number) {
    return Integer.parseInt(number) + 1
  }

  private String getReleaseTag(Boolean isFirstRelease, String releaseWay, String existingVersion) {
    if (isFirstRelease) {
      return "v1.0.0"
    }
    def (majorVersion, minorVersion, patchVersion) = existingVersion.split('v')[1].tokenize(".")
    if (releaseWay == "MAJOR") {
      def newMajorVersion = incrementNumber(majorVersion)
      return "v$newMajorVersion.0.0"
    }
    if (releaseWay == "MINOR") {
      int newMinorVersion = incrementNumber(minorVersion)
      return "v$majorVersion.$newMinorVersion.0"
    }
    def newPatchVersion = incrementNumber(patchVersion)
    return "v$majorVersion.$minorVersion.$newPatchVersion"
  }
}
