package com.axis.maximus.jenkins.library

class Artifactory {
    def steps
    def artifactoryUsername
    def artifactoryPassword
    def artifactoryHost

    Artifactory(steps) {
        this.artifactoryHost = "https://artifactory.axisb.com"
        this.artifactoryUsername = steps.env.ARTIFACTORY_USERNAME
        this.artifactoryPassword = steps.env.ARTIFACTORY_PASSWORD
        this.steps = steps
    }

    def getArtifact(String downloadPath) {
        steps.sh "echo 'Downloading $downloadPath ...' "
        steps.sh "curl -O -u${artifactoryUsername}:${artifactoryPassword} ${artifactoryHost}/artifactory/${downloadPath}"
    }

    def upload(String filename, String uploadPath) {
        steps.sh "echo 'Uploading $filename at location $uploadPath ...' "
        steps.sh "curl -f -u ${artifactoryUsername}:${artifactoryPassword} -T $filename ${artifactoryHost}/artifactory/${uploadPath}"
    }

    def pushEvent(String service, String buildNumber) {
        def doesFileExists = steps.fileExists("build/resources/events.json")
        if (doesFileExists) {
            steps.sh "git log -n 1 --pretty=format:'%h' | cut -c1-5> tag.txt"
            def tag = steps.sh(returnStdout: true, script: "cat tag.txt").trim()
            def imageTag = "$buildNumber-$tag"
            upload("build/resources/events.json", "maximus-general/contract/events/${service}-${imageTag}.json")
        } else {
            steps.sh "echo 'events.json file not found ...' "
        }
    }

    def tagManifest(String path, String oldTag, String newTag) {
        String contentType = "application/vnd.docker.distribution.manifest.v2+json"
        steps.sh("curl -vk -u${artifactoryUsername}:${artifactoryPassword} -H \"Accept: ${contentType}\" \"${artifactoryHost}/v2/${path}/manifests/${oldTag}\" -o manifest.json")
        steps.sh("curl -vk -u${artifactoryUsername}:${artifactoryPassword}  -X PUT -H \"Content-Type: ${contentType}\" -T manifest.json \"${artifactoryHost}/v2/${path}/manifests/${newTag}\"")
    }
}
