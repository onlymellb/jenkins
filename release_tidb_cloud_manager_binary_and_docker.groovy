def call(TIDB_CLOUD_MANAGER_BRANCH, RELEASE_TAG) {
	
	def GITHASH
	env.PATH = "${env.GOROOT}/bin:/bin:${env.PATH}"
	def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"

	//define k8s pod template
	podTemplate(
		label: 'delivery',
		volumes: [
			hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
		],
		containers: [
			containerTemplate(
				name: 'build-env',
				image: 'localhost:5000/pingcap/build_env:latest',
				alwaysPullImage: true,
				ttyEnabled: true,
				command: 'cat')
		]){
		catchError {
			node('delivery') {
				def WORKSPACE = pwd()
				stage('delivery cloud-manager binary') {
					dir("${WORKSPACE}/cloud-manager"){
						container('build-env') {
							stage('Download tidb-cloud-manager binary'){
								GITHASH = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/cloud-manager/${TIDB_CLOUD_MANAGER_BRANCH}/centos7/sha1").trim()
								sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/cloud-manager/${GITHASH}/centos7/tidb-cloud-manager.tar.gz | tar xz"
							}

							stage('Push tidb-cloud-manager Docker Image'){
								sh """
								cp -R /tmp/.docker ~/
								"""
								withDockerServer([uri: "unix:///var/run/docker.sock"]) {
									def image = docker.build("pingcap/tidb-cloud-manager:${RELEASE_TAG}", "docker")
									//push to docker hub
									//image.push()
									//sh """
									//docker push pingcap/tidb-cloud-manager:${RELEASE_TAG}
									//"""
									//push to ucloud registry
									image.tag("uhub.service.ucloud.cn/pingcap/tidb-cloud-manager:${RELEASE_TAG}").push()
								}
							}
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
				slackSend channel: '#cloud_jenkin', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
			} else {
				slackmsg = "${slackmsg}" + "\n" +
				"tidb-cloud-manager Docker Image: `pingcap/tidb-cloud-manager:${RELEASE_TAG}`"
				slackSend channel: '#cloud_jenkin', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
			}
		}
	}
}

return this
