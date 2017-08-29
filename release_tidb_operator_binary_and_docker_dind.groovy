def call(TIDB_OPERATOR_BRANCH, RELEASE_TAG) {

	env.GOPATH = "/go"
	env.GOROOT = "/usr/local/go"
	env.PATH = "${env.GOROOT}/bin:${env.GOPATH}/bin:/bin:${env.PATH}"

	def GITHASH
	def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"

	catchError {
		node('k8s_centos7_build') {
			def WORKSPACE = pwd()
			def HOSTIP = env.NODE_NAME.getAt(8..(env.NODE_NAME.lastIndexOf('-') - 1))

			dir("${WORKSPACE}/operator"){
				stage('Download tidb-operator binary'){
					GITHASH = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/operator/${TIDB_OPERATOR_BRANCH}/centos7/sha1").trim()
					sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/operator/${GITHASH}/centos7/tidb-operator.tar.gz | tar xz"
				}

				stage('Push tidb-operator Docker Image'){
					sh """
					mkdir -p tidb_operator_docker_build/bin
					cd tidb_operator_docker_build
					cp ../docker/bin/* ./bin
					cat > Dockerfile << __EOF__
FROM alpine:3.5
ADD bin/tidb-controller-manager /usr/local/bin/tidb-controller-manager
ADD bin/tidb-volume-manager /usr/local/bin/tidb-volume-manager
ADD bin/tidb-scheduler /usr/local/bin/tidb-scheduler
CMD ["/bin/sh", "-c", "/usr/local/bin/tidb-operator"]
__EOF__
					"""
					withDockerServer([uri: "tcp://${HOSTIP}:32376"]) {
						docker.build("uhub.service.ucloud.cn/pingcap/tidb-operator:${RELEASE_TAG}", "tidb_operator_docker_build").push()
						docker.build("pingcap/tidb-operator:${RELEASE_TAG}", "tidb_operator_docker_build").push()
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
		"tidb-operator Branch: `${TIDB_OPERATOR_BRANCH}`, Githash: `${GITHASH.take(7)}`" + "\n" +
		"Display URL:" + "\n" +
		"${env.RUN_DISPLAY_URL}"

		if(currentBuild.result != "SUCCESS"){
			slackSend channel: '#cloud_jenkins', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
		} else {
			slackmsg = "${slackmsg}" + "\n" +
			"tidb-operator Docker Image: `pingcap/tidb-operator:${RELEASE_TAG}`" + "\n" +
			"tidb-operator Docker Image: `uhub.service.ucloud.cn/pingcap/tidb-operator:${RELEASE_TAG}`"
			slackSend channel: '#cloud_jenkins', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
		}
	}
}

return this
