def call(TIDB_CLOUD_MANAGER_BRANCH, RELEASE_TAG) {

    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.PATH = "/home/jenkins/bin:/bin:${env.PATH}"
    def tidb_sha1

    catchError {
        node('delivery') {
            def nodename = "${env.NODE_NAME}"
            def HOSTIP = nodename.getAt(7..(nodename.lastIndexOf('-') - 1))

            stage('Prepare') {
                dir ('centos7') {
                    tidb_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/cloud-manager/${TIDB_CLOUD_MANAGER_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/cloud-manager/${tidb_sha1}/centos7/tidb-cloud-manager.tar.gz| tar xz"
					withDockerServer([uri: "tcp://${HOSTIP}:32376"]) {
						docker.build("pingcap/tidb-cloud-manager:${RELEASE_TAG}", "docker").push()
						docker.build("uhub.service.ucloud.cn/pingcap/tidb-cloud-manager:${RELEASE_TAG}", "docker").push()
					}
                }
            }
        }
        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
        "Elapsed Time: `${duration}` Mins" + "\n" +
        "tikv Docker Image: `pingcap/tikv:${RELEASE_TAG}`"
//        "tikv Docker Image: `pingcap/tikv:${RELEASE_TAG}`" + "\n" +
//        "tikv Unportable Docker Image: `pingcap/tikv:${RELEASE_TAG}-unportable`"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#binary_publis', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        } else {
            slackSend channel: '#binary_publis', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}

return this
