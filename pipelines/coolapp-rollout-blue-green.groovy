node(){
    def current_route
    def rolloutNewVersion
    def approve_message
    def namespace
    def target_cluster_flags

    stage("prepare") {

        target_cluster_flags = "--server=$ocp_cluster_url --insecure-skip-tls-verify"


        withCredentials([string(credentialsId: 'OCP_PROD_TOKEN', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
            current_route = sh (
                script: "oc get route acmeapp -o jsonpath='{ .spec.to.name }' --server=${ocp_cluster_url} --token=${SECRET_OCP_SERVICE_TOKEN} $target_cluster_flags --namespace=acme-app-prod-${NAMESPACE_PREFIX}",
                returnStdout: true
            ).trim()
            echo "Rotta: " + current_route
        
            def next_version_oc_command = ""

            //create the oc command to call to get the next image version that will be put online
            if(current_route.equalsIgnoreCase("acmeapp-blue")){    
                next_version_oc_command = "oc get dc/acmeapp-green --token=${SECRET_OCP_SERVICE_TOKEN} -o jsonpath={.spec.template.spec.containers[0].image} $target_cluster_flags --namespace=acme-app-prod-${NAMESPACE_PREFIX}"
            }else{
                next_version_oc_command = "oc get dc/acmeapp-blue --token=${SECRET_OCP_SERVICE_TOKEN} -o jsonpath={.spec.template.spec.containers[0].image} $target_cluster_flags --namespace=acme-app-prod-${NAMESPACE_PREFIX}"
            }

            //Found the next version to be deloyed
            next_version = sh (
                script: next_version_oc_command,
                returnStdout: true
            ).trim()

            next_version = next_version.tokenize(':').last()

            //prepare the message for pipeline input
            if(current_route.equalsIgnoreCase("acmeapp-blue")){
                approve_message = "Release workflow in production of the new version on group green [${next_version}]"
            }else{
                approve_message = "Release workflow in production of the new version on group blue [${next_version}]"           
            }

        
        }
        echo "Rotta: " + current_route
    }

    stage("input") {          
        rolloutNewVersion = input(message: approve_message, ok: 'Release it', 
                                parameters: [booleanParam(defaultValue: true, 
                                description: 'Confirm the release of the new acmeapp version',name: 'Tick checkbox to confirm and then submit')])

        echo "Release?: " + rolloutNewVersion
    }

    stage("rollout") {  
        withCredentials([string(credentialsId: 'OCP_PROD_TOKEN', variable: 'SECRET_OCP_SERVICE_TOKEN')]) {
            if(rolloutNewVersion){
                if(current_route.equalsIgnoreCase("acmeapp-blue")){
                    echo "oc patch route/acmeapp -p '{\"spec\":{\"to\":{\"name\":\"acmeapp-green\"}}}'  --namespace=acme-app-prod-${NAMESPACE_PREFIX}"             

                    def patch_result = sh (
                        script: "oc patch route/acmeapp -p '{\"spec\":{\"to\":{\"name\":\"acmeapp-green\"}}}' --token=${SECRET_OCP_SERVICE_TOKEN} $target_cluster_flags  --namespace=acme-app-prod-${NAMESPACE_PREFIX}",
                        returnStdout: true
                    ).trim()
                    echo "Patch result: " + patch_result

                }else{
                    echo "oc patch route/acmeapp -p '{\"spec\":{\"to\":{\"name\":\"acmeapp-blue\"}}}'  --namespace=acme-app-prod-${NAMESPACE_PREFIX}"
                    def patch_result = sh (
                        script: "oc patch route/acmeapp -p '{\"spec\":{\"to\":{\"name\":\"acmeapp-blue\"}}}' --token=${SECRET_OCP_SERVICE_TOKEN} $target_cluster_flags  --namespace=acme-app-prod-${NAMESPACE_PREFIX}",
                        returnStdout: true
                    ).trim()
                    echo "Patch result: " + patch_result

                }
            
            
            }          
        }
    }

}