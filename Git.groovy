package com.axis.maximus.jenkins.library

//TODO: Change the name from Git to Version
class Git implements Serializable {

    // Library classes cannot directly call steps such as sh or git.
    // So a set of steps can be passed explicitly using 'this' to a library class, in a constructor.
    def steps
    def credentialsId
    def url
    def branch
    def gitUsername
    def gitPassword
    def relativeTargetDir

    Git(steps) {
        this.steps = steps
        this.credentialsId = 'GIT_USER'
        this.gitUsername = steps.GIT_AUTH_USR
        this.gitPassword = steps.GIT_AUTH_PSW
        this.url = "https://bitbucket.axisb.com/scm"
        this.branch = "master"
    }

    def checkout(Map config = [:], String projectName = "max") {
        if (config.githubCredentialsId) {
            credentialsId = config.githubCredentialsId
        }

        if (config.gitBranch) {
            branch = config.gitBranch
        }

        if (config.gitBranch == 'null' || config.gitBranch == null) {
            branch = 'master'
        }

        if (config.gitUrl) {
            url = config.gitUrl
        }

        if (config.gitprojectName) {
            projectName = config.gitprojectName
        }

        if (config.relativeTargetDir) {
            relativeTargetDir= config.relativeTargetDir
        }
        else {
            relativeTargetDir = config.serviceName
        }

        steps.checkout([$class           : 'GitSCM',
                        branches         : [[name: "$branch"]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false], [$class: 'RelativeTargetDirectory', relativeTargetDir: "$relativeTargetDir"]],
                        userRemoteConfigs: [[credentialsId: "$credentialsId",
                                            url : "$url/$projectName/${config.serviceName}.git"]]]
        )
    }

  def tagRepoCommit(String serviceName, String commitSha, String releaseTag) {
    steps.sh "echo -e ' #### Commit $commitSha of repo $serviceName will be tagged with $releaseTag ####'"

    String projectSlug = (serviceName == "login-service") ? "cos" : "max"

    GString tag="seed-$releaseTag"
    GString tagsUrl="https://bitbucket.axisb.com/rest/git/1.0/projects/$projectSlug/repos/$serviceName/tags"

    steps.sh "echo '#### Deleting Tag if it already exists in Bitbucket ####'"
    steps.sh "curl -k -u ${gitUsername}:${gitPassword} -X DELETE -H \"Content-Type: application/json\" $tagsUrl/$tag"

    steps.sh "echo '#### Re applying Tag to given commit ####'"
    steps.sh "curl -k -u $gitUsername:$gitPassword -X POST -H 'Content-Type: application/json' $tagsUrl -d '{ \"name\": \"$tag\", \"startPoint\": \"$commitSha\", \"message\": \"Tagging for release $tag\" }'"
  }
}
