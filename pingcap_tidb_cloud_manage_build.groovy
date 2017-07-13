def call(TIDB_CLOUD_MANAGE_BRANCH) {
	
	def IMAGE_TAG
	def GITHASH
	env.GOROOT = "/usr/local/go"
	env.GOPATH = "/go"
	env.PATH = "${env.GOROOT}/bin:/bin:${env.PATH}"
	def BUILD_URL = "git@github.com:pingcap/tidb-cloud-manager.git"

	//define k8s pod template
	podTemplate(
		label: 'jenkins-slave',
		volumes: [
			hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
		],
		containers: [
			containerTemplate(
				name: 'build-env',
				image: 'localhost:5000/pingcap/build_env:latest',
				ttyEnabled: true,
				command: 'cat')
		]){
		catchError {
			node('jenkins-slave') {
				def WORKSPACE = pwd()
				stage('build process') {
					dir("${WORKSPACE}/go/src/github.com/pingcap/tidb-cloud-manager"){
						container('build-env') {
							stage('build tidb-cloud-manager binary'){
									git credentialsId: "k8s", url: "${BUILD_URL}", branch: "${TIDB_CLOUD_MANAGE_BRANCH}"
									GITHASH = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
									sh """
									export GOPATH=${WORKSPACE}/go:$GOPATH
									make
									mkdir -p docker/bin
									cp bin/tidb-cloud-manager docker/bin/tidb-cloud-manager
									"""
							}
							stage('push tidb-cloud-manager images'){
									IMAGE_TAG = "localhost:5000/pingcap/tidb-cloud-manager_k8s:${GITHASH.take(7)}"
									sh """
									docker build -t ${IMAGE_TAG} docker
									docker push ${IMAGE_TAG}
									"""
							}
						}
					}
				}
			}
			currentBuild.result = "SUCCESS"
		}
		stage('Summary') {
			echo("echo summary info #########")
			def DURATION = (((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60) as double).round(2)
			def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
			"Elapsed Time: `${DURATION}` Mins" + "\n" +
			"Build Branch: `${TIDB_CLOUD_MANAGE_BRANCH}`, Githash: `${GITHASH.take(7)}`" + "\n" +
			"Build images:  ${IMAGE_TAG}"
			if(currentBuild.result != "SUCCESS"){
				echo(slackmsg + "currentBuild.result")
				slackSend channel: '#iamgroot', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
			} else {
				echo(slackmsg + "currentBuild.result")
				slackSend channel: '#iamgroot', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
			}
		}
	}
}

return this
