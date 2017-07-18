def call(TIDB_OPERATOR_BRANCH, RELEASE_TAG) {
	
	def GITHASH
	env.PATH = "${env.GOROOT}/bin:/bin:${env.PATH}"
	def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"

	//define k8s pod template
	podTemplate(
		label: 'delivery',
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
				stage('delivery operator binary') {
					dir("${WORKSPACE}/operator"){
						container('build-env') {
							stage('Download tidb-operator binary'){
								GITHASH = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/operator/${TIDB_OPERATOR_BRANCH}/centos7/sha1").trim()
								sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/operator/${GITHASH}/centos7/tidb-operator.tar.gz | tar xz"
							}

							stage('Push Centos7 Binary'){
								def target = "tidb-operator-${RELEASE_TAG}-linux-amd64"
								
								sh """
								mkdir ${target}
								cp -R bin ./${target}
								tar czvf ${target}.tar.gz ${target}
								sha256sum ${target}.tar.gz > ${target}.sha256
								md5sum ${target}.tar.gz > ${target}.md5
								"""

								sh """
								export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
								upload.py ${target}.tar.gz ${target}.tar.gz
								upload.py ${target}.sha256 ${target}.sha256
								upload.py ${target}.md5 ${target}.md5
								"""
							}

							stage('Push tidb-operator Docker Image'){
								sh """
								mkdir -p tidb_operator_docker_build/bin
								cd tidb_operator_docker_build
								cp ../bin/* ./bin
								cat > Dockerfile << __EOF__
FROM alpine:3.5
RUN apk add --no-cache ca-certificates
ADD bin/tidb-operator /usr/local/bin/tidb-operator
ADD bin/tidb-volume-manager /usr/local/bin/tidb-volume-manager
ADD bin/tidb-scheduler /usr/local/bin/tidb-scheduler
CMD ["/bin/sh", "-c", "/usr/local/bin/tidb-operator"]
__EOF__
								docker build -t pingcap/tidb-operator:${RELEASE_TAG} .
								cp -R /tmp/.docker ~/
								docker push
								"""
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
			"tidb-operator Branch: `${TIDB_OPERATOR_BRANCH}`, Githash: `${GITHASH.take(7)}`"

			if(currentBuild.result != "SUCCESS"){
				slackSend channel: '#cloud_jenkins', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
			} else {
				slackmsg = "${slackmsg}" + "\n" +
				"tidb-operator Binary Download URL:" + "\n" +
				"http://download.pingcap.org/tidb-operator-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
				"tidb-operator Binary sha256   URL:" + "\n" +
				"http://download.pingcap.org/tidb-operator-${RELEASE_TAG}-linux-amd64.sha256" + "\n" +
				"tidb-operator Docker Image: `pingcap/tidb-operator:${RELEASE_TAG}`"
				slackSend channel: '#cloud_jenkins', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
			}
		}
	}
}

return this
