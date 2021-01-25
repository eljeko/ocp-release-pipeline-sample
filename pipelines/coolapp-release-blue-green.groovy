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
                    echo "Releasing tag ${GIT_TAG}"
                    //echo "Releasing from url ${GIT_URL}"

                    target_cluster_flags = "--server=$ocp_cluster_url --insecure-skip-tls-verify"
                }
            }
        }

        stage('release'){
            steps {
                script {
                    withCredentials([string(credentialsId: 'OCP_PROD_TOKEN', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
                        def current_route = sh(script: "oc get route acmeapp -o jsonpath='{ .spec.to.name }' --namespace=acme-app-prod-${NAMESPACE_PREFIX} --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags",
                                        returnStdout: true)
                        echo "Current route: ${current_route}"
                        
                        if(current_route.equalsIgnoreCase("coolapp-blue")){    
                            sh(script: " oc set image dc/acmeapp-green acmeapp-green=image-registry.openshift-image-registry.svc:5000/acme-app-stage-${NAMESPACE_PREFIX}/acmeapp:${GIT_TAG} --namespace=acme-app-prod-${NAMESPACE_PREFIX} --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags",
                                        returnStdout: true)              
                        }else{
                            sh(script: " oc set image dc/acmeapp-blue acmeapp-blue=image-registry.openshift-image-registry.svc:5000/acme-app-stage-${NAMESPACE_PREFIX}/acmeapp:${GIT_TAG} --namespace=acme-app-prod-${NAMESPACE_PREFIX} --token=$SECRET_OCP_SERVICE_TOKEN $target_cluster_flags",
                                        returnStdout: true)                     
                        }
                    }
                }
            }
        }
    }
}    