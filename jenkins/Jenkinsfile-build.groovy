/*
    To learn how to use this sample pipeline, follow the guide below and enter the
    corresponding values for your environment and for this repository:
    - https://github.com/fabiogomezdiaz/refarch-cloudnative-devops-kubernetes
*/

// Environment
def clusterURL = env.CLUSTER_URL
def clusterAccountId = env.CLUSTER_ACCOUNT_ID
def clusterCredentialId = env.CLUSTER_CREDENTIAL_ID ?: "cluster-credentials"

// Pod Template
def podLabel = "customer"
def cloud = env.CLOUD ?: "kubernetes"
def registryCredsID = env.REGISTRY_CREDENTIALS ?: "registry-credentials-id"
def serviceAccount = env.SERVICE_ACCOUNT ?: "jenkins"

// Pod Environment Variables
def namespace = env.NAMESPACE ?: "default"
def registry = env.REGISTRY ?: "docker.io"
def imageName = env.IMAGE_NAME ?: "fabiogomezdiaz/bluecompute-customer"
def serviceLabels = env.SERVICE_LABELS ?: "app=customer,tier=backend" //,version=v1"
def microServiceName = env.MICROSERVICE_NAME ?: "customer"
def servicePort = env.MICROSERVICE_PORT ?: "8082"
def managementPort = env.MANAGEMENT_PORT ?: "8092"

// External Test Database Parameters
// For username and passwords, set COUCHDB_USER (as string parameter) and COUCHDB_PASSWORD (as password parameter)
//     - These variables get picked up by the Java application automatically
//     - There were issues with Jenkins credentials plugin interfering with setting up the password directly

def couchDBProtocol = env.COUCHDB_PROTOCOL ?: "http"
def couchDBHost = env.COUCHDB_HOST
def couchDBPort = env.COUCHDB_PORT ?: "5985"
def couchDBDatabase = env.COUCHDB_DATABASE ?: "customers"

// Test User Creation
def createUser = env.CREATE_USER ?: "true"
def testUser = env.TEST_USER ?: "testuser"
def testPassword = env.TEST_PASSWORD ?: "passw0rd"

// HS256_KEY Secret
def hs256Key = env.HS256_KEY

/*
  Optional Pod Environment Variables
 */
def helmHome = env.HELM_HOME ?: env.JENKINS_HOME + "/.helm"

podTemplate(label: podLabel, cloud: cloud, serviceAccount: serviceAccount, envVars: [
        envVar(key: 'CLUSTER_URL', value: clusterURL),
        envVar(key: 'CLUSTER_ACCOUNT_ID', value: clusterAccountId),
        envVar(key: 'NAMESPACE', value: namespace),
        envVar(key: 'REGISTRY', value: registry),
        envVar(key: 'IMAGE_NAME', value: imageName),
        envVar(key: 'SERVICE_LABELS', value: serviceLabels),
        envVar(key: 'MICROSERVICE_NAME', value: microServiceName),
        envVar(key: 'MICROSERVICE_PORT', value: servicePort),
        envVar(key: 'MANAGEMENT_PORT', value: managementPort),
        envVar(key: 'COUCHDB_PROTOCOL', value: couchDBProtocol),
        envVar(key: 'COUCHDB_HOST', value: couchDBHost),
        envVar(key: 'COUCHDB_PORT', value: couchDBPort),
        envVar(key: 'COUCHDB_DATABASE', value: couchDBDatabase),
        envVar(key: 'CREATE_USER', value: createUser),
        envVar(key: 'TEST_USER', value: testUser),
        envVar(key: 'TEST_PASSWORD', value: testPassword),
        envVar(key: 'HS256_KEY', value: hs256Key),
        envVar(key: 'HELM_HOME', value: helmHome)
    ],
    volumes: [
        hostPathVolume(mountPath: '/home/gradle/.gradle', hostPath: '/tmp/jenkins/.gradle'),
        emptyDirVolume(mountPath: '/var/lib/docker', memory: false)
    ],
    containers: [
        containerTemplate(name: 'jdk', image: 'fabiogomezdiaz/openjdk-bash:alpine', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'docker', image: 'fabiogomezdiaz/docker:18.09-dind', privileged: true)
  ]) {

    node(podLabel) {
        checkout scm

        // Local
        container(name:'jdk', shell:'/bin/bash') {
            stage('Local - Build and Unit Test') {
                sh """
                #!/bin/bash
                ./gradlew build
                """
            }
            stage('Local - Run and Test') {
                sh """
                #!/bin/bash

                JAVA_OPTS="-Dspring.application.cloudant.protocol=${COUCHDB_PROTOCOL}"
                JAVA_OPTS="\${JAVA_OPTS} -Dspring.application.cloudant.host=${COUCHDB_HOST}"
                JAVA_OPTS="\${JAVA_OPTS} -Dspring.application.cloudant.port=${COUCHDB_PORT}"
                JAVA_OPTS="\${JAVA_OPTS} -Dspring.application.cloudant.database=${COUCHDB_DATABASE}"
                JAVA_OPTS="\${JAVA_OPTS} -Dserver.port=${MICROSERVICE_PORT}"

                java \${JAVA_OPTS} -jar build/libs/micro-customer-0.0.1.jar &

                PID=`echo \$!`

                # Let the application start
                bash scripts/health_check.sh "http://127.0.0.1:${MANAGEMENT_PORT}"

                # Run tests
                bash scripts/api_tests.sh 127.0.0.1 ${MICROSERVICE_PORT} ${HS256_KEY} ${TEST_USER} ${TEST_PASSWORD}

                # Kill process
                kill \${PID}
                """
            }
        }

        // Docker
        container(name:'docker', shell:'/bin/bash') {
            stage('Docker - Build Image') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" == "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                docker build -t \${IMAGE} .
                """
            }
            stage('Docker - Run and Test') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" == "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                # Kill Container if it already exists
                docker kill ${MICROSERVICE_NAME} || true
                docker rm ${MICROSERVICE_NAME} || true

                # Start Container
                echo "Starting ${MICROSERVICE_NAME} container"
                set +x
                docker run --name ${MICROSERVICE_NAME} -d \
                    -p ${MICROSERVICE_PORT}:${MICROSERVICE_PORT} \
                    -p ${MANAGEMENT_PORT}:${MANAGEMENT_PORT} \
                    -e SERVICE_PORT=${MICROSERVICE_PORT} \
                    -e COUCHDB_PROTOCOL=${COUCHDB_PROTOCOL} \
                    -e COUCHDB_HOST=${COUCHDB_HOST} \
                    -e COUCHDB_PORT=${COUCHDB_PORT} \
                    -e COUCHDB_USER=${COUCHDB_USER} \
                    -e COUCHDB_PASSWORD=${COUCHDB_PASSWORD} \
                    -e COUCHDB_DATABASE=${COUCHDB_DATABASE} \
                    -e HS256_KEY=${HS256_KEY} \
                    \${IMAGE}
                set -x

                # Check that application started successfully
                docker ps

                # Check the logs
                docker logs -f ${MICROSERVICE_NAME} &
                PID=`echo \$!`

                # Get the container IP Address
                CONTAINER_IP=`docker inspect ${MICROSERVICE_NAME} | jq -r '.[0].NetworkSettings.IPAddress'`

                # Let the application start
                bash scripts/health_check.sh "http://\${CONTAINER_IP}:${MANAGEMENT_PORT}"

                # Run tests
                bash scripts/api_tests.sh \${CONTAINER_IP} ${MICROSERVICE_PORT} ${HS256_KEY} ${TEST_USER} ${TEST_PASSWORD}

                # Kill process
                kill \${PID}

                # Kill Container
                docker kill ${MICROSERVICE_NAME} || true
                docker rm ${MICROSERVICE_NAME} || true
                """
            }
            stage('Docker - Push Image to Registry') {
                withCredentials([usernamePassword(credentialsId: registryCredsID,
                                               usernameVariable: 'USERNAME',
                                               passwordVariable: 'PASSWORD')]) {
                    sh """
                    #!/bin/bash

                    # Get image
                    if [ "${REGISTRY}" == "docker.io" ]; then
                        IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                    else
                        IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                    fi

                    docker login -u ${USERNAME} -p ${PASSWORD} ${REGISTRY}

                    docker push \${IMAGE}
                    """
                }
            }
        }
    }
}
