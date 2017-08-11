def call(TIDB_CLOUD_MANAGER_BRANCH, RELEASE_TAG) {
	
	def GITHASH
	env.PATH = "${env.GOROOT}/bin:/bin:${env.PATH}"
	def DOCKER_IP = "10.8.45.217"
	def DOCKER_PORT = 32376
	def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"

	catchError {
		node {
			def WORKSPACE = pwd()
			dir("${WORKSPACE}/cloud-manager"){
				stage('Download tidb-cloud-manager binary'){
					GITHASH = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/cloud-manager/${TIDB_CLOUD_MANAGER_BRANCH}/centos7/sha1").trim()
					sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/cloud-manager/${GITHASH}/centos7/tidb-cloud-manager.tar.gz | tar xz"
				}

				stage('Push tidb-cloud-manager Docker Image'){
					withDockerServer([uri: "tcp://${DOCKER_IP}:${DOCKER_PORT}"]) {
						docker.build("uhub.service.ucloud.cn/pingcap/tidb-cloud-manager:${RELEASE_TAG}", "docker").push()
						docker.build("pingcap/tidb-cloud-manager:${RELEASE_TAG}", "docker").push()
					}
				}
			}
		}
		currentBuild.result = "SUCCESS"
	}
	stage('Summary') {
		echo("echo summary info ########")
		def DURATION = (((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60) as double).round(2)
		def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
		"Elapsed Time: `${DURATION}` Mins" + "\n" +
		"tidb-cloud-manager Branch: `${TIDB_CLOUD_MANAGER_BRANCH}`, Githash: `${GITHASH.take(7)}`" + "\n" +
		"Display URL:" + "\n" +
		"${env.RUN_DISPLAY_URL}"

		if(currentBuild.result != "SUCCESS"){
			slackSend channel: '#cloud_jenkins', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
		} else {
			slackmsg = "${slackmsg}" + "\n" +
			"tidb-cloud-manager Docker Image: `pingcap/tidb-cloud-manager:${RELEASE_TAG}`" + "\n" +
			"tidb-cloud-manager Docker Image: `uhub.service.ucloud.cn/pingcap/tidb-cloud-manager:${RELEASE_TAG}`"
			slackSend channel: '#cloud_jenkins', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
		}
	}
}

return this
