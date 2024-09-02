package com.axis.maximus.jenkins.library

class Docker implements Serializable {

    // Library classes cannot directly call steps such as sh or git.
    // So a set of steps can be passed explicitly using "this" to a library class, in a constructor.
    def steps
    def imageTag
    def serviceName
    def artifactoryHost
    def artifactoryUsername
    def artifactoryPassword
    def twistlockUsername
    def twistlockPassword
    def repositoryName
    def imageRepo
    def image

    Docker(steps, imageTag, serviceName, repositoryName = "maximus") {
        this.artifactoryHost = "artifactory.axisb.com/docker"
        this.artifactoryUsername = steps.env.ARTIFACTORY_USERNAME
        this.artifactoryPassword = steps.env.ARTIFACTORY_PASSWORD
        this.twistlockUsername = steps.env.TWISTLOCK_USERNAME
        this.twistlockPassword = steps.env.TWISTLOCK_PASSWORD
        this.steps = steps
        this.imageTag = imageTag
        this.serviceName = serviceName
        this.repositoryName = repositoryName
        this.imageRepo = "$artifactoryHost/$serviceName"
        this.image = "$imageRepo:$imageTag"
    }

    Docker(steps, serviceName) {
        this.artifactoryHost = "artifactory.axisb.com/docker"
        this.artifactoryUsername = steps.env.ARTIFACTORY_USERNAME
        this.artifactoryPassword = steps.env.ARTIFACTORY_PASSWORD
        this.steps = steps
        this.imageTag = "latest"
        this.serviceName = serviceName
        this.repositoryName = "maximus"
        this.imageRepo = "$artifactoryHost/$serviceName"
        this.image = "$imageRepo:$imageTag"
    }

    private def push(String imageName, Boolean debugMode = false) {
        if (debugMode) {
            steps.sh "docker -D push $imageName 2>&1 | tee logs/$serviceName-push-image.log"
        } else {
            steps.sh "docker push $imageName"
        }
        steps.sh "docker rmi -f $imageName"
    }

    def login() {
        steps.sh "docker login -u=$artifactoryUsername -p=$artifactoryPassword $artifactoryHost"
    }

    def tagImage(Boolean pushToDomainRepo = false, String deployedOnProd = 'false') {
        if (deployedOnProd.toBoolean() && pushToDomainRepo) {
            imageRepo = "$artifactoryHost/$repositoryName/$serviceName"
            image = "$imageRepo:$imageTag"
            steps.sh "docker tag $imageRepo:latest $image "
        } else {
            if (repositoryName == 'gpay' ||
                    repositoryName == 'observability' ||
                    repositoryName == 'authcap' ||
                    repositoryName == 'forex' || pushToDomainRepo) {
                image = "$artifactoryHost/$repositoryName/$serviceName:$imageTag"
                steps.sh "docker tag $imageRepo:latest $artifactoryHost/$repositoryName/$serviceName:latest"
                steps.sh "docker tag $imageRepo:latest $image"
                imageRepo = "$artifactoryHost/$repositoryName/$serviceName"
            } else {
                steps.sh "docker tag $imageRepo:latest $image "
            }
        }

    }

    def tagImageKaniko(Boolean pushToDomainRepo = false, String deployedOnProd = 'false') {
        if (deployedOnProd.toBoolean() && pushToDomainRepo) {
            imageRepo = "$artifactoryHost/$repositoryName/$serviceName"
            image = "$imageRepo:$imageTag"
            return imageRepo
        } else {
            if (repositoryName == 'gpay' ||
                    repositoryName == 'observability' ||
                    repositoryName == 'authcap' ||
                    repositoryName == 'forex' || pushToDomainRepo) {
                image = "$artifactoryHost/$repositoryName/$serviceName:$imageTag"
                imageRepo = "$artifactoryHost/$repositoryName/$serviceName"
                return imageRepo
            } else {
                return imageRepo
            }
        }

    }

    def tagImage(String tag) {
        steps.sh "docker tag $image $imageRepo:$tag"

    }

    def pushImage(Boolean pushLatest = true) {
        push(image)
        pushLatest ? push("$imageRepo:latest") : {}
    }

    def pull() {
        steps.sh "docker pull $image"
    }

    def build(Boolean isMaximusSeedMulti = false) {
        steps.sh "docker build -t $imageRepo ."
        if (isMaximusSeedMulti) {
            steps.sh "docker build -t artifactory.axisb.com/docker/seed:latest-arm -f DockerfileWithArm . --platform linux/arm64"
        }
    }

    def buildWithTag() {
        steps.sh "docker build -t $image ."
    }

    def buildServiceSeed() {
      imageRepo = "$artifactoryHost/$repositoryName/$serviceName"
      steps.sh "docker build . -f Dockerfile.serviceSeed -t $imageRepo"
    }

    def scanImage() {
        steps.sh "twistcli images scan --address https://twistlock.axisb.com:8083 -u $twistlockUsername -p $twistlockPassword --output-file scan-results.json --details $image"
    }

    def scanImageKaniko(String imageRepo, String imageTag) {
        steps.sh "twistcli images scan --address https://twistlock.axisb.com:8083 -u $twistlockUsername -p $twistlockPassword --output-file scan-results.json --details $imageRepo:$imageTag"
    }

    def downloadScanResult() {

        downloadScanScript = libraryResource("downloadScanScript.sh")
        writeFile(file: "downloadScanScript.sh", text: "${downloadScanScript}")
        steps.sh "bash downloadScanScript.sh $twistlockUsername $twistlockPassword"
    }

    def run(String containerName) {
        steps.sh "docker run --name $containerName --net=host --add-host=artifactory.axisb.com:10.22.162.87 $imageRepo"
    }

    def copy(String containerName, String sourcePath, String targetPath) {
        steps.sh "docker cp $containerName:$sourcePath $targetPath"
    }

    def rm(String containerName) {
        steps.sh "docker rm -f $containerName"
    }

    def buildWithArgs() {
        steps.sh "docker build -t $imageRepo . --build-arg ARTIFACTORY_USERNAME=$artifactoryUsername --build-arg ARTIFACTORY_PASSWORD=$artifactoryPassword"
    }

    def tagAmdImage(){
        steps.sh "docker tag $imageRepo $imageRepo:$imageTag"
    }

    def tagArmImage(Boolean pushToDomainRepo = false, String deployedOnProd = 'false'){
        if (deployedOnProd.toBoolean() && pushToDomainRepo) {
            // steps.sh "docker tag $artifactoryHost/$repositoryName/$serviceName:linux_arm64 $artifactoryHost/$repositoryName/$serviceName:latest-arm"
            steps.sh "docker tag $artifactoryHost/$repositoryName/$serviceName:latest-arm $artifactoryHost/$repositoryName/$serviceName:$imageTag-arm"
        }
        else {
            if (repositoryName == 'gpay' ||
                    repositoryName == 'observability' ||
                    repositoryName == 'authcap' ||
                    repositoryName == 'forex' || pushToDomainRepo) {
                image = "$artifactoryHost/$repositoryName/$serviceName:$imageTag"
                steps.sh "docker tag $imageRepo:latest-arm $artifactoryHost/$repositoryName/$serviceName:latest-arm"
                steps.sh "docker tag $imageRepo:latest-arm $artifactoryHost/$repositoryName/$serviceName:$imageTag-arm"
                
            }
            else {
                //steps.sh "docker tag $imageRepo:linux_arm64 $imageRepo:latest-arm"
                steps.sh "docker tag $imageRepo:latest-arm $imageRepo:$imageTag-arm"
            }
        }
    }

    def pushArmImage() {
        steps.sh "docker push $imageRepo:latest-arm"
        steps.sh "docker push $imageRepo:$imageTag-arm"
        steps.sh "docker rmi -f $imageRepo:latest-arm $imageRepo:$imageTag-arm"

    }
}
