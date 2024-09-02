package com.axis.maximus.jenkins.library

class Util implements Serializable {

  def steps

  Util(steps) {
    this.steps = steps
  }

  String getNamespacePrefix(String projectName) {
    if (projectName == 'forex') {
      return 'env-forex-'
    }
    if (projectName == 'maximus' || projectName == 'observability' || projectName == 'account' || projectName == 'discount') {
      return 'env-lending-'
    }
  }

  String getNamespace(String projectName, String currentEnvironment) {
    if (projectName == 'gpay') {
      return 'gpay-' + currentEnvironment.toLowerCase()
    }
    return getNamespacePrefix(projectName) + currentEnvironment + "-service"
  }

  String getNamespaceForDomains(String domainName, String currentEnvironment) {
    return domainName + "-" + currentEnvironment
  }

  String getNamespaceForDomainsServices(String domainName, String currentEnvironment) {
    switch (domainName) {
      case ['account', 'discount']:
        return getNamespace(domainName, currentEnvironment)
      default: return getNamespaceForDomains(domainName, currentEnvironment) + "-service"
    }
  }

  String getNamespaceForDomainsTask(String domainName, String currentEnvironment) {
    switch (domainName) {
      case ['account', 'discount']:
        return getNamespacePrefix(domainName) + currentEnvironment + "-task"
      default: return getNamespaceForDomains(domainName, currentEnvironment) + "-task"
    }
  }

  String getNamespaceForDomainsWeb(String domainName, String currentEnvironment) {
    switch (domainName) {
      case ['account', 'discount']:
        return "env-lending-" + currentEnvironment + "-web"
      default: return getNamespaceForDomains(domainName, currentEnvironment) + "-web"
    }
  }

  String getTestType(String upstreamEnvironment) {
    // only for gpay
    String testType
    switch (upstreamEnvironment) {
      case 'sit':
        testType = 'smoke'
        break
      case 'qa':
        testType = 'regression'
        break
      case 'perf':
        testType = 'performance'
        break
    }
    return testType
  }

  Boolean isFrontendService() {
    return steps.fileExists("package.json")
  }

  Boolean folderExistsAndNonEmpty(String path) {
    def pactExists = steps.sh(returnStdout: true, script: "[ -d $path ] && [ -n \"\$(ls -A $path)\" ] && echo '0' || echo '1'").trim()
    if (pactExists == '0') return true else return false
  }

  ArrayList getServiceNameAndVersion(String serviceVersionEntry) {
    def data = serviceVersionEntry.replace("define(", "").replace(")", "").replace("_VERSION", "").replace("_", "-").split(', ')
    def serviceName = data[0]
    def version = data[1]
    def lowerCaseServiceName = serviceName.toLowerCase().trim()
    return [lowerCaseServiceName, version]
  }
}
