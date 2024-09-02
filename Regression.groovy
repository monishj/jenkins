package com.axis.maximus.jenkins.library

class Regression implements Serializable {
    def steps
    def git
    def currentEnvironment
    def artifactoryPassword
    def artifactoryUsername
    Regression(steps, currentEnvironment) {
      this.steps = steps
      git = new Git(steps)
      this.artifactoryUsername = steps.env.ARTIFACTORY_USERNAME
      this.artifactoryPassword = steps.env.ARTIFACTORY_PASSWORD
      this.currentEnvironment = currentEnvironment
    }

    def runMsilTests(String HELM_RELEASE_NAME, String MSIL_TEST_VERSION, String TAG, String CHART_NAME = 'msil-tests'){
        steps.sh "echo 'Deleting old helm chart'"
        def HELM_PACKAGE_EXISTS = steps.sh(script:"helm list -n msil-$currentEnvironment-task | grep $HELM_RELEASE_NAME-$currentEnvironment", returnStatus: true)
        if(HELM_PACKAGE_EXISTS == 0 ){
          steps.sh "echo '#### Purging helm package $HELM_RELEASE_NAME-$currentEnvironment#### ' "
          steps.sh "helm delete $HELM_RELEASE_NAME-$currentEnvironment -n msil-$currentEnvironment-task"
          steps.sh "sleep 30"
        }
        def nameSpace = "msil-${currentEnvironment}-task"
        steps.sh "echo '#### MSIL_TEST_VERSION: $MSIL_TEST_VERSION####'"
        steps.sh "helm repo add --pass-credentials stable https://artifactory.axisb.com/artifactory/maximus-helm-virtual --username ${artifactoryUsername} --password ${artifactoryPassword}"
        steps.sh "helm repo update"
        steps.sh "helm upgrade --install $HELM_RELEASE_NAME-$currentEnvironment stable/$CHART_NAME --namespace=msil-$currentEnvironment-task --set environment.name=$currentEnvironment --set environment.tag=${TAG} --set job.name=${HELM_RELEASE_NAME} --version $MSIL_TEST_VERSION"
        // steps.sh "sleep 20"
        verifyPodStatus(nameSpace,HELM_RELEASE_NAME)
    }

  def runRegressionApi(String regressionApi, String branch, String product, String testGroup, String reportDir){
    git.checkout([serviceName: regressionApi, gitBranch: branch])
    steps.sh "cd maximus-api-test; ./gradlew clean build -PmavenUser=${artifactoryUsername} -PmavenPassword=${artifactoryPassword} -PENV=${currentEnvironment} -PPRODUCT=${product} -PGROUP=${testGroup} -Dorg.gradle.workers.max=1 --stacktrace "
    steps.sh "cd maximus-api-test; allure generate build/reports/result/ -o build/reports/report/"
    steps.sh "cd maximus-api-test; mkdir -p $reportDir; cp -R ./build/reports/report $reportDir/ ; cp -R ./build/logs  $reportDir/"
  }
  
    // CHeck the pod status
    def verifyPodStatus(String namespace, String release){
        steps.sh "kubectl get pods -n ${namespace} --show-labels"
        def podName = steps.sh(script : "kubectl get pods -n $namespace -l=app=$release -o name | cut -f 2 -d '/'", returnStdout: true ).trim()
        while(podName == ""){
          podName = steps.sh(script : "kubectl get pods -n $namespace -l=app=$release -o name | cut -f 2 -d '/'", returnStdout: true ).trim()
          steps.sh "echo '#### Waiting for pod creation. ####'"
          steps.sh "sleep 2"
        }
        steps.sh "echo '$podName is created' "
        def timeOutCount=1
        def podStatus =  steps.sh(script: "kubectl get pod $podName -n $namespace -o jsonpath='{.status.containerStatuses[0].ready}'", returnStdout: true).trim()
        while(podStatus == "false" && timeOutCount<6) {
          podStatus = steps.sh(script: "kubectl get pod $podName -n $namespace -o jsonpath='{.status.containerStatuses[0].ready}'", returnStdout: true).trim()
          steps.sh "echo '#### Waiting for $podName to reach Ready state ####'"
          steps.sh "sleep 60s"
          timeOutCount++
        }
        steps.sh "echo '$podName is in ready state'"
    
        def currentPodStatus = steps.sh(script: "kubectl get pod $podName -n $namespace -o jsonpath='{.status.phase}'", returnStdout: true).trim()
        steps.sh "echo 'podStatus is >>>> $currentPodStatus'"
        while(currentPodStatus == "Running" || currentPodStatus =="Pending") {
          currentPodStatus = steps.sh(script: "kubectl get pod $podName -n $namespace -o jsonpath='{.status.phase}'", returnStdout: true).trim()
          if (currentPodStatus == "Running") {
            steps.sh "kubectl logs --tail=-1 $podName -n $namespace > ${podName}.log"
            def latestLogSize = steps.sh(script: "du -sh ${podName}.log", returnStdout: true).trim()
            steps.sh "echo '#### $podName is $currentPodStatus, Logs are at size $latestLogSize #### '"
          }
          sleep 15
        }
        def finalPodStatus = steps.sh(script: "kubectl get pod $podName -n $namespace -o jsonpath='{.status.phase}'", returnStdout: true).trim()
        steps.sh "echo '#### $podName has now finished with status $finalPodStatus####'"
    
        podName= steps.sh(script: "kubectl get pods -n $namespace -l=app=$release -o name | cut -f 2 -d '/' ", returnStdout: true).trim()
        steps.sh "echo '#### Dumping Logs for $podName in file ${podName}.log ####' "
        steps.sh "kubectl logs --tail=-1 $podName -n $namespace > ${podName}.log"
        steps.sh "echo '#### Logs for $podName are as below ####' "
        steps.sh "cat ${podName}.log; echo;"
        steps.sh "echo '#### Verifying test success/failure status ####'"
        def testState = steps.sh(script: "grep 'Failures' ${podName}.log || grep 'Run crashed' ${podName}.log", returnStatus: true)
        if (testState == 0){
          steps.sh "echo Tests Failed"
          steps.sh "exit 1"
        }
        else{
          steps.sh "echo '#### Tests passed ####'"
          steps.sh "exit 0"
        }

    }


}
