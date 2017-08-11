def call(TIDB_CLOUD_MANAGER_BRANCH, RELEASE_TAG) {
	
	env.GOPATH = "/go"
	env.GOROOT = "/usr/local/go"
	env.PATH = "${env.GOROOT}/bin:${env.GOPATH}/bin:/bin:${env.PATH}"

	def GITHASH
	def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"

	catchError {
		node('k8s-dind') {
			def WORKSPACE = pwd()
			dir("${WORKSPACE}/cloud-manager"){
				stage('Download tidb-cloud-manager binary'){
					GITHASH = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/cloud-manager/${TIDB_CLOUD_MANAGER_BRANCH}/centos7/sha1").trim()
					sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/cloud-manager/${GITHASH}/centos7/tidb-cloud-manager.tar.gz | tar xz"
				}

				stage('Push tidb-cloud-manager Docker Image'){
					sh """
					cd docker
					docker build -t pingcap/tidb-cloud-manager:${RELEASE_TAG} .
					docker tag pingcap/tidb-cloud-manager:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/tidb-cloud-manager:${RELEASE_TAG}
					docker push uhub.service.ucloud.cn/pingcap/tidb-cloud-manager:${RELEASE_TAG}
					docker push pingcap/tidb-cloud-manager:${RELEASE_TAG}
					"""
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
