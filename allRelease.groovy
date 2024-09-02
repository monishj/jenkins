import com.axis.maximus.jenkins.library.Git
import com.axis.maximus.jenkins.library.Pact

def call(Map config = [:]) {

  pipeline {
    agent {
      kubernetes {
        yaml libraryResource('release-agent.yaml')
        podRetention onFailure()
      }
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
            env.CONFIG_CHANGE = CONFIG_CHANGE
            env.RELEASE = RELEASE
            env.BRANCH = BRANCH
            env.OVERRIDE_VERSIONS = OVERRIDE_VERSIONS
            env.PACT_ENV = "PROD"
            env.BITBUCKET_URL = "bitbucket.axisb.com"
            env.service = "prod"
            env.releaseTagRepo = "prod-hotfix-dummy"
            env.VERSIONING = config.VERSIONING
            env.ARTIFACTORY_USERNAME = "$ARTIFACTORY_CREDS_USR"
            env.ARTIFACTORY_PASSWORD = "$ARTIFACTORY_CREDS_PSW"
          }
        }
      }
      stage('config') {
        when {
          expression {
            return params.CONFIG_CHANGE == "true"
          }
        }
        steps {
          script {
            env.ARTIFACTORY_USER = "$ARTIFACTORY_CREDS_USR"
            env.ARTIFACTORY_PASSWORD = "$ARTIFACTORY_CREDS_PSW"
            echo "building config tar"
            git branch: params.BRANCH, credentialsId: 'bpriyanka', url: 'https://bitbucket.axisb.com/scm/max/maximus-dlp-helm.git'
            sh '''
                  cd maximus-config
                  env="qa sandbox hotfix uat perf prod"
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
        }
      }
      stage('uat') {
        options {
          retry(2)
        }
        steps {
          echo "deploying on uat"
          build job: 'UAT/maximus-uat-deploy',
            parameters: [string(name: 'upstream_environment', value: params.RELEASE),
                        string(name: 'branch', value: params.BRANCH),
                        string(name: 'release_repo_branch', value: params.BRANCH),
                        string(name: 'config_change', value: params.CONFIG_CHANGE),
                        string(name: 'override_versions', value: params.OVERRIDE_VERSIONS)]
        }
      }


      stage('missing topics validation') {
        steps {
          script {
            env.ARTIFACTORY_USER = "$ARTIFACTORY_CREDS_USR"
            env.ARTIFACTORY_PASSWORD = "$ARTIFACTORY_CREDS_PSW"
            checkout([
              $class                           : 'GitSCM',
              branches                         : [[name: 'refs/heads/master']],
              doGenerateSubmoduleConfigurations: false,
              extensions                       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'infra']],
              submoduleCfg                     : [],
              userRemoteConfigs                : [[credentialsId: 'GIT_USER', url: 'https://bitbucket.axisb.com/scm/inf/infra.git']]
            ])
            sh '''
                    cd infra/scripts/kafka-utils
                    ./fetch_kafka_topics.sh --host=z-1.axawsmaximusuatmsk.vsdefg.c2.kafka.ap-south-1.amazonaws.com:2181 --env=uat --project=maximus
                    cd ../../../
                    '''
            def encodedPass = URLEncoder.encode(env.GIT_AUTH_PSW, "UTF-8")
            env.encodedPass = encodedPass
            def proceed;
            proceed = true
            // proceed = input(id: 'push_to_bitbucket', message: 'check and click proceed to append topics diff to maximus-dlp-helm/kafka_topics/maximus/topics.txt file (or) uncheck and click proceed to continue without appending?',
            //         parameters: [
            //             booleanParam(defaultValue: false, description: 'check and click proceed to append topics diff to maximus-dlp-helm/kafka_topics/maximus/topics.txt file (or) uncheck and click proceed to continue without check and click proceed to append topics diff to maximus-dlp-helm/kafka_topics/maximus/topics.txt file (or) uncheck and click proceed to continue without appending??', name: 'push_to_dlp_helm_repo')
            //     ])
            wrap([$class: "MaskPasswordsBuildWrapper", varPasswordPairs: [[password: encodedPass]]]) {
              if (proceed) {
                sh '''
                            git clone https://${GIT_AUTH_USR}:${encodedPass}@bitbucket.axisb.com/scm/max/maximus-dlp-helm.git
                            cd maximus-dlp-helm
                            git config --global user.name "go-user"
                            git config user.name "go-user"
                            git config --global user.email "go-user@axisb.com"
                            git config user.email "go-user@axisb.com"
                            echo "topic_name partitions replication_factor" > kafka_topics/maximus/topics.txt
                            if [ -f "../infra/scripts/kafka-utils/maximus_prod_topics_diff_topics.txt" ]; then
                            cat ../infra/scripts/kafka-utils/maximus_prod_topics_diff_topics.txt >> kafka_topics/maximus/topics.txt
                            fi
                            git diff --quiet && git diff --staged --quiet || git commit -am "Adding missing topics found in kafka validation step"
                            git status
                            git push https://${GIT_AUTH_USR}:${encodedPass}@bitbucket.axisb.com/scm/max/maximus-dlp-helm.git HEAD:master
                            echo "Topics diff pushed verify topics to be created in prod in maximus-dlp-helm/kafka_topics/maximus/topics.txt before proceeding with next stage"
                        '''
              } else {
                sh '''
                        git clone https://${GIT_AUTH_USR}:${encodedPass}@bitbucket.axisb.com/scm/max/maximus-dlp-helm.git
                        '''
              }
            }
          }
        }
      }

      stage('missing secrets validation') {
        when {
          expression {
            return env.OVERRIDE_VERSIONS == ''
          }
        }
        steps {
          script {
            sh '''  
                    set +x 
                    cd maximus-dlp-helm
                    release_branch=${BRANCH}
                    echo ${release_branch} > release_branch.txt
                    current_release_version=$(echo ${release_branch}| cut -d "v" -f 2 | cut -d "." -f 1 )
                    previous_release_version=$(expr $current_release_version - 1)
                    sed  -iE  "s|${current_release_version}|${previous_release_version}|g" release_branch.txt
                    previous_release_branch=$(cat release_branch.txt)
                    echo "Following secrets should be added in GOCD as part of release ${current_release_version}"
                    git diff --ignore-all-space origin/${previous_release_branch} origin/${release_branch} maximus-config/templates/secret* |  grep -i '+  '

                    '''
          }
        }
      }

      stage('pre-prod') {
        options {
          retry(5)
        }
        steps {
          input(id: 'Proceed', message: 'Deploy to Pre-Prod?',
            parameters: [[$class      : 'BooleanParameterDefinition',
                          defaultValue: true,
                          description : '',
                          name        : 'Proceed with deployment?']
            ],submitter: 'tw-axis-manager,tw-axis-infra-rep')

          echo "deploying on pre-prod"
          build job: 'PROD/maximus-prod-deploy',
            parameters: [string(name: 'upstream_environment', value: 'uat'),
                        string(name: 'branch', value: params.BRANCH)]
        }
      }

      stage("Can I Deploy - Production") {
        steps {
          script {
            env.ARTIFACTORY_USER = "$ARTIFACTORY_CREDS_USR"
            env.ARTIFACTORY_PASSWORD = "$ARTIFACTORY_CREDS_PSW"
            env.PACT_ENV = "PROD"
            env.enforce_pact_integration = config.enforce_pact_integration
            isPactVersionsPresent = sh (returnStatus: true, script: "curl -k --fail -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} https://artifactory.axisb.com/artifactory/maximus-general/versions/uat/pact-versions.md --output pact-versions.md")
            if (isPactVersionsPresent != 0) {
              steps.sh "echo 'FAIL : pact-versions.md is not present'"
              steps.sh "exit 1"
            }
            steps.sh "curl -O -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} https://artifactory.axisb.com/artifactory/libs-release-local/com/axis/lending/can-i-deploy/latest/can-i-deploy-latest.jar"
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

      stage('prod') {
        steps {
          input(id: 'Proceed', message: 'Deploy to Prod?',
            parameters: [[$class      : 'BooleanParameterDefinition',
                          defaultValue: true,
                          description : '',
                          name        : 'Proceed with deployment?']
            ],submitter: 'tw-axis-manager,tw-axis-infra-rep')
          script {
            env.ARTIFACTORY_USER = "$ARTIFACTORY_CREDS_USR"
            env.ARTIFACTORY_PASSWORD = "$ARTIFACTORY_CREDS_PSW"
            env.releaseTagRepo = "prod-hotfix-dummy"
            env.service = "prod"
            git_helm_repo = new Git(this)
            git_helm_repo.checkout([serviceName: "maximus-dlp-helm", gitBranch: "master", gitprojectName: "MAX"])
            sh '''
                curl -O -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} "https://artifactory.axisb.com/artifactory/maximus-general/versions/uat/tag.txt"
                TAG=$(cat tag.txt)
                curl -O -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} "https://artifactory.axisb.com/artifactory/maximus-general/versions/uat/versions-$TAG.txt"
                curl -O -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} "https://artifactory.axisb.com/artifactory/maximus-general/versions/uat/uat-config.txt"
                echo "config tag is- "
                cat uat-config.txt
                echo "versions considered for PRODUCTION"
                cat versions-$TAG.txt
                curl -f -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} -T uat-config.txt "https://artifactory.axisb.com/artifactory/maximus-general/uat-release/uat-config.txt"
                curl -f -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} -T tag.txt "https://artifactory.axisb.com/artifactory/maximus-general/prod-release/tag.txt"
                curl -f -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} -T versions-$TAG.txt "https://artifactory.axisb.com/artifactory/maximus-general/prod-release/versions-$TAG.txt"
                curl -f -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} -T maximus-dlp-helm/kafka_topics/maximus/topics.txt https://artifactory.axisb.com/artifactory/maximus-general/msk-topics/maximus_prod_topics_diff.txt
                '''
            git = new Git(this)
            updateReleaseTag("$service", "$releaseTagRepo", git)
          }
        }
      }
    }
  }
}


String updateReleaseTag(String serviceName, String tagRepo, Git git) {
  def service = "${serviceName}"  //prod
  def releaseTagRepo = "${tagRepo}"  // prod-hotfix-dummy
  git.checkout([serviceName: "$releaseTagRepo", gitBranch: "master", gitprojectName: "$service"])
  dir("$releaseTagRepo") {
    withCredentials([usernamePassword(credentialsId: 'GIT_USER', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
      script {
        def encodedPass = URLEncoder.encode(GIT_PASSWORD, "UTF-8")
        wrap([$class          : "MaskPasswordsBuildWrapper",
              varPasswordPairs: [[password: encodedPass]]])

          {
            sh '''
                    curl -O -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} "https://artifactory.axisb.com/artifactory/maximus-general/versions/uat/tag.txt"
                    TAG=$(cat tag.txt)
                    curl -O -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} "https://artifactory.axisb.com/artifactory/maximus-general/versions/uat/versions-$TAG.txt"
                    cp versions-$TAG.txt version.txt
                    echo "Commiting below versions in bitbucket"
                    cat version.txt
                    git status
                    git remote -vvv
                    git add version.txt
                    git config --global user.name "go-user"
                    git config --global user.email "go-user@axisb.com"
                    git commit -m "Increment release version" --allow-empty
                    git push https://${GIT_USERNAME}:${encodedPass}@${BITBUCKET_URL}/scm/${service}/${releaseTagRepo}.git HEAD:master
                    '''
          }
      }
    }

  }
}
