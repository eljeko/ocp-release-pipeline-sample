pipeline {
    agent any

    stages {
        stage('prepare') {
            steps {
                script {                    
                    echo "Releasing Acme Cool App"
                }
            }
        }
    }
}