pipeline {
    //GIT_URL=${GIT_URL}
    //GIT_TAG=${GIT_TAG}

    agent any

    stages {
        stage('prepare') {
            steps {
                script {
                    if (!"${GIT_TAG}"?.trim()) {
                        currentBuild.result = 'ABORTED'
                        error('Tag to build is empty')
                    }
                    echo "Releasing tag ${BUILD_TAG}"
                    echo "Releasing from url ${GIT_URL}"

                    target_cluster_flags = "--server=$ocp_cluster_url --insecure-skip-tls-verify"
                }
            }
        }

        stage('Build') {
            stages {
                stage('Source checkout') {
                    steps {
                        checkout(
                                [$class                           : 'GitSCM', branches: [[name: "refs/tags/${GIT_TAG}"]],
                                doGenerateSubmoduleConfigurations: false,
                                extensions                       : [],
                                submoduleCfg                     : [],
                                //userRemoteConfigs                : [[credentialsId: "${GIT_CREDENTIAL_ID}", url: "${GIT_URL}"]]]
                                userRemoteConfigs                : [[url: "${GIT_URL}"]]]
                        )
                    }
                }

                stage('Maven setup') {
                    steps {
                        //withMaven() {
                        withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                            sh "mvn -f ${POM_FILE} versions:set -DnewVersion=${GIT_TAG}"
                        }
                    }

                }

                stage('Build and publish on nexus') {
                    steps {
                        withMaven(mavenSettingsFilePath: "${MVN_SETTINGS}") {
                            sh "mvn -f ${POM_FILE} clean package -Dappversion=${GIT_TAG} -Dmaven.javadoc.skip=true -DskipTests "
                        }
                        echo "mvn Build ${GIT_TAG} done"
                    }
                }
            }
        }



        stage('Bake') {
            stages {
  
                stage('Prepare') {
                    steps {
                        sh """
                        rm -rf ${WORKSPACE}/s2i-binary
                        mkdir -p ${WORKSPACE}/s2i-binary/configuration
                        cp web-app/target/ROOT.war ${WORKSPACE}/s2i-binary/ROOT.war                        
                        """
                        //cp ${WORKSPACE}/runtime-configuration/ocp/standalone-openshift.xml ${WORKSPACE}/s2i-binary/configuration/

                        script {
                            withCredentials([string(credentialsId: 'OCP_STAGE_TOKEN', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
                                def buildconfigUpdateResult =
                                        sh(script: "oc --server=${OCP_CLUSTER_URL} patch bc acmeapp -p '{\"spec\":{\"output\":{\"to\":{\"kind\":\"ImageStreamTag\",\"name\":\"acmeapp:${GIT_TAG}\"}}}}' --namespace=acme-app-stage-${NAMESPACE_PREFIX} -o json --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags |oc replace acmeapp --namespace=acme-app-stage-${NAMESPACE_PREFIX} $target_cluster_flags --token=$SECRET_OCP_SERVICE_TOKEN -f -",
                                                returnStdout: true)
                                if (!buildconfigUpdateResult?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('BuildConfig update finished with errors')
                                }
                                echo "Patch BuildConfig result: $buildconfigUpdateResult"
                            }
                        }

                    }
                }

                stage('s2i binary deploy') {
                    steps {
                        script {
                            withCredentials([string(credentialsId: 'OCP_STAGE_TOKEN', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
                                def binaryDeployResult = sh(script: "oc --server=${OCP_CLUSTER_URL} start-build acmeapp --from-dir=${WORKSPACE}/s2i-binary/ --namespace=acme-app-stage-${NAMESPACE_PREFIX} --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags --follow",
                                        returnStdout: true)
                                if (!binaryDeployResult?.trim()) {
                                    currentBuild.result = 'ERROR'
                                    error('Binary deploy finished with errors')
                                }
                                echo "s2i binary deploy result: $binaryDeployResult"
                            }
                        }
                    }
                }

            }

        }   

        stage('deploy') {
            steps {
                script {
                    withCredentials([string(credentialsId: 'OCP_STAGE_TOKEN', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
                        def patchIamgeStream = sh(script: "oc set image dc/acmeapp acmeapp=image-registry.openshift-image-registry.svc:5000/acme-app-stage-${NAMESPACE_PREFIX}/acmeapp:${GIT_TAG} --namespace=acme-app-stage-${NAMESPACE_PREFIX} --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags",
                                returnStdout: true)
                        //If the output is true the image was the same, so we check if current image is really the desired version
                        if (!patchIamgeStream?.trim()) {

                            def currentImageStreamVersion = sh(script: "oc get dc  acmeapp -o jsonpath='{.spec.template.spec.containers[0].image}' --namespace=acme-app-stage-${NAMESPACE_PREFIX} --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags",
                                    returnStdout: true)

                            //if current DeploymentConfig image tag version it's different form BUIL_TAG we end the pipeline with an error
                            if (!currentImageStreamVersion.equalsIgnoreCase("image-registry.openshift-image-registry.svc:5000/acme-app-stage-${NAMESPACE_PREFIX}/acmeapp:${GIT_TAG}")) {
                                echo "DeploymentConfig image tag version is: $currentImageStreamVersion but expected is: [image-registry.openshift-image-registry.svc:5000/acme-app-stage-${NAMESPACE_PREFIX}/acmeapp:${GIT_TAG}]"
                                currentBuild.result = 'ERROR'
                                error('Rollout finished with errors: DeploymentConfig image tag version is wrong')
                            }

                        }

                        echo "Patch imageStream result: $patchIamgeStream"
                        
                    }

                    withCredentials([string(credentialsId: 'OCP_STAGE_TOKEN', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
                        def rollout = sh(script: "oc rollout latest acmeapp --namespace=acme-app-stage-${NAMESPACE_PREFIX} --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags",
                                returnStdout: true)
                        if (!rollout?.trim()) {
                            currentBuild.result = 'ERROR'
                            error('Rollout finished with errors')
                        }

                        echo "Rollout result: $rollout"
                    }
                }

            }

        }             

    }
}