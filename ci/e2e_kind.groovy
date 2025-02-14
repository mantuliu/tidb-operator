//
// Jenkins pipeline for Kind e2e job.
//
// This script is written in declarative syntax. Refer to
// https://jenkins.io/doc/book/pipeline/syntax/ for more details.
//
// Note that parameters of the job is configured in this script.
//

podYAML = '''\
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: tidb-operator-e2e
spec:
  containers:
  - name: main
    image: hub-new.pingcap.net/tidb-operator/kubekins-e2e:v3
    command:
    - runner.sh
    # Clean containers on TERM signal in root process to avoid cgroup leaking.
    # https://github.com/pingcap/tidb-operator/issues/1603#issuecomment-582402196
    - exec
    - bash
    - -c
    - |
      sleep 1d & wait
    # we need privileged mode in order to do docker in docker
    securityContext:
      privileged: true
    env:
    - name: DOCKER_IN_DOCKER_ENABLED
      value: "true"
    resources:
      requests:
        memory: "4Gi"
        cpu: "4"
        ephemeral-storage: "70Gi"
      limits:
        memory: "8Gi"
        cpu: "8"
        ephemeral-storage: "70Gi"
    # kind needs /lib/modules and cgroups from the host
    volumeMounts:
    - mountPath: /lib/modules
      name: modules
      readOnly: true
    - mountPath: /sys/fs/cgroup
      name: cgroup
    # dind expects /var/lib/docker to be volume
    - name: docker-root
      mountPath: /var/lib/docker
    # legacy docker path for cr.io/k8s-testimages/kubekins-e2e
    - name: docker-graph
      mountPath: /docker-graph
  volumes:
  - name: modules
    hostPath:
      path: /lib/modules
      type: Directory
  - name: cgroup
    hostPath:
      path: /sys/fs/cgroup
      type: Directory
  - name: docker-root
    emptyDir: {}
  - name: docker-graph
    emptyDir: {}
  tolerations:
  - effect: NoSchedule
    key: tidb-operator
    operator: Exists
  affinity:
    # running on nodes for tidb-operator only
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: ci.pingcap.com
            operator: In
            values:
            - tidb-operator
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchExpressions:
            - key: app
              operator: In
              values:
              - tidb-operator-e2e
          topologyKey: kubernetes.io/hostname
'''

// Able to override default values in Jenkins job via environment variables.
if (!env.DEFAULT_GIT_REF) {
    env.DEFAULT_GIT_REF = "master"
}

if (!env.DEFAULT_GINKGO_NODES) {
    env.DEFAULT_GINKGO_NODES = "8"
}

if (!env.DEFAULT_E2E_ARGS) {
    env.DEFAULT_E2E_ARGS = ""
}

if (!env.DEFAULT_DOCKER_IO_MIRROR) {
    env.DEFAULT_DOCKER_IO_MIRROR = ""
}

if (!env.DEFAULT_QUAY_IO_MIRROR) {
    env.DEFAULT_QUAY_IO_MIRROR = ""
}

if (!env.DEFAULT_GCR_IO_MIRROR) {
    env.DEFAULT_GCR_IO_MIRROR = ""
}

pipeline {
    agent {
        kubernetes {
            cloud "kubernetes-ng"
            namespace "jenkins-tidb-operator"
            yaml podYAML
            defaultContainer "main"
            customWorkspace "/home/jenkins/agent/workspace/go/src/github.com/pingcap/tidb-operator"
        }
    }

    options {
        timeout(time: 3, unit: 'HOURS') 
    }

    parameters {
        string(name: 'GIT_URL', defaultValue: 'git@github.com:pingcap/tidb-operator.git', description: 'git repo url')
        string(name: 'GIT_REF', defaultValue: env.DEFAULT_GIT_REF, description: 'git ref spec to checkout, e.g. master, release-1.1')
        string(name: 'PR_ID', defaultValue: '', description: 'pull request ID, this will override GIT_REF if set, e.g. 1889')
        string(name: 'GINKGO_NODES', defaultValue: env.DEFAULT_GINKGO_NODES, description: 'the number of ginkgo nodes')
        string(name: 'E2E_ARGS', defaultValue: env.DEFAULT_E2E_ARGS, description: "e2e args, e.g. --ginkgo.focus='\\[Stability\\]'")
        string(name: 'DOCKER_IO_MIRROR', defaultValue: env.DEFAULT_DOCKER_IO_MIRROR, description: "docker mirror for docker.io")
        string(name: 'QUAY_IO_MIRROR', defaultValue: env.DEFAULT_QUAY_IO_MIRROR, description: "mirror for quay.io")
        string(name: 'GCR_IO_MIRROR', defaultValue: env.DEFAULT_GCR_IO_MIRROR, description: "mirror for gcr.io")
        booleanParam(name: 'DELETE_NAMESPACE_ON_FAILURE', defaultValue: true, description: 'delete namespace on failure or not')
    }

    environment {
        GIT_REF = ''
        ARTIFACTS = "${env.WORKSPACE}/artifacts"
    }

    stages {
        stage("Prepare") {
            steps {
                // The declarative model for Jenkins Pipelines has a restricted
                // subset of syntax that it allows in the stage blocks. We use
                // script step to bypass the restriction.
                // https://jenkins.io/doc/book/pipeline/syntax/#script
                script {
                    GIT_REF = params.GIT_REF
                    if (params.PR_ID != "") {
                        GIT_REF = "refs/remotes/origin/pr/${params.PR_ID}/head"
                    }
                }
                echo "env.NODE_NAME: ${env.NODE_NAME}"
                echo "env.WORKSPACE: ${env.WORKSPACE}"
                echo "GIT_REF: ${GIT_REF}"
                echo "ARTIFACTS: ${ARTIFACTS}"
            }
        }

        stage("Checkout") {
            steps {
                try {
                    checkout scm: [
                            $class: 'GitSCM',
                            branches: [[name: GIT_REF]],
                            userRemoteConfigs: [[
                                    credentialsId: 'github-sre-bot-ssh',
                                    refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*',
                                    url: "${params.GIT_URL}",
                            ]]
                    ]
                } catch (info) {
                    retry(3) {
                        echo "checkout failed, retry.."
                        sleep 10
                        checkout scm: [
                                $class: 'GitSCM',
                                branches: [[name: GIT_REF]],
                                userRemoteConfigs: [[
                                        credentialsId: 'github-sre-bot-ssh',
                                        refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*',
                                        url: "${params.GIT_URL}",
                                ]]
                        ]
                    }
                }
            }
        }

        stage("Run") {
            steps {
                sh """
                export GINKGO_NODES=${params.GINKGO_NODES}
                export DOCKER_IO_MIRROR=${params.DOCKER_IO_MIRROR}
                export QUAY_IO_MIRROR=${params.QUAY_IO_MIRROR}
                export GCR_IO_MIRROR=${params.GCR_IO_MIRROR}
                export DELETE_NAMESPACE_ON_FAILURE=${params.DELETE_NAMESPACE_ON_FAILURE}
                export ARTIFACTS=${ARTIFACTS}
                export GINKGO_NO_COLOR=y
                echo "info: begin to run e2e"
                ./hack/e2e.sh -- ${params.E2E_ARGS}
                """
            }
        }
    }

    post {
        always {
            dir(ARTIFACTS) {
                sh """#!/bin/bash
                echo "info: change ownerships for jenkins"
                chown -R 1000:1000 .
                echo "info: print total size of artifacts"
                du -sh .
                echo "info: list all files"
                find .
                """
                archiveArtifacts artifacts: "**", allowEmptyArchive: true
                junit testResults: "*.xml", allowEmptyResults: true
            }
        }
    }
}

// vim: et sw=4 ts=4
