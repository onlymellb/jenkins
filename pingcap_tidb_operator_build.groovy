def call(BUILD_BRANCH) {
	
	def GITHASH
	env.GOPATH = "/go"
	env.GOROOT = "/usr/local/go"
	env.PATH = "${env.GOROOT}/bin:/bin:${env.PATH}"
	def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
	def BUILD_URL = "git@github.com:pingcap/tidb-operator.git"

	//define k8s pod template
	podTemplate(
		label: 'centos7_build',
		containers: [
			containerTemplate(
				name: 'build-env',
				image: 'localhost:5000/pingcap/build_env:latest',
				alwaysPullImage: true,
				ttyEnabled: true,
				command: 'cat')
		]){
		catchError {
			node('centos7_build') {
				def WORKSPACE = pwd()
				stage('Build') {
					dir("${WORKSPACE}/go/src/github.com/pingcap/tidb-operator"){
						container('build-env') {
							stage('build tidb-operator binary'){
								// checkout source code
								git credentialsId: "k8s", url: "${BUILD_URL}", branch: "${BUILD_BRANCH}"
								GITHASH = sh(returnStdout: true, script: "git rev-parse HEAD").trim()

								// build
								sh "export GOPATH=${WORKSPACE}/go:$GOPATH && make"
							}

							stage('upload tidb-operator binary'){

								//upload binary
								sh """
								cp /usr/local/bin/config.cfg ./
								tar zcvf tidb-operator.tar.gz bin/*
								filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key builds/pingcap/operator/${GITHASH}/centos7/tidb-operator.tar.gz --file tidb-operator.tar.gz
								"""

								//update refs
								writeFile file: 'sha1', text: "${GITHASH}"
								sh "filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key refs/pingcap/operator/${BUILD_BRANCH}/centos7/sha1 --file sha1"
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
			"Build Branch: `${BUILD_BRANCH}`, Githash: `${GITHASH.take(7)}`" + "\n" +
			"Display URL:" + "\n" +
			"${env.RUN_DISPLAY_URL}"
			if(currentBuild.result != "SUCCESS"){
				slackSend channel: '#cloud_jenkins', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
			} else {
				slackmsg = "${slackmsg}" + "\n" +
				"Binary Download URL:" + "\n" +
				"${UCLOUD_OSS_URL}/builds/pingcap/operator/${GITHASH}/centos7/tidb-operator.tar.gz"
				slackSend channel: '#cloud_jenkins', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
			}
		}
	}
}

return this
