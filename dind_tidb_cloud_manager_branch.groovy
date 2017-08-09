def call(TIDB_CLOUD_MANAGER_BRANCH) {
	
	def IMAGE_TAG
	def GITHASH
	env.GOROOT = "/usr/local/go"
	env.GOPATH = "/go"
	env.PATH = "${env.GOROOT}/bin:${env.GOPATH}/bin:/bin:${env.PATH}"
	def BUILD_URL = "git@github.com:pingcap/tidb-cloud-manager.git"
	def E2E_IMAGE = "localhost:5000/pingcap/tidb-cloud-manager-e2e-dind:latest"
	def KUBECTL_URL = "https://storage.googleapis.com/kubernetes-release/release/v1.7.2/bin/linux/amd64/kubectl"

	catchError {
		stage('Prepare') {
			node('k8s-dind') {
				def WORKSPACE = pwd()

				dir("${WORKSPACE}/go/src/github.com/pingcap/tidb-cloud-manager"){
						stage('build tidb-cloud-manager binary'){
							git credentialsId: "k8s", url: "${BUILD_URL}", branch: "${TIDB_CLOUD_MANAGER_BRANCH}"
							GITHASH = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
							sh """
							export GOPATH=${WORKSPACE}/go:$GOPATH
							make
							mkdir -p docker/bin
							cp bin/tidb-* docker/bin
							"""
						}

						stage('push tidb-cloud-manager images'){
							IMAGE_TAG = "localhost:5000/pingcap/tidb-cloud-manager_e2e:${GITHASH.take(7)}"
							sh """
							cd docker
							docker build -t ${IMAGE_TAG} .
							docker push ${IMAGE_TAG}
							"""
						}

						stage('build cloud-manager e2e binary'){
							sh """
							export GOPATH=${WORKSPACE}/go:$GOPATH
							echo ${env.PATH}
							ginkgo build test/e2e
							"""
						}

						stage('start prepare runtime environment'){
							def SRC_FILE_CONTENT = readFile file: "manifests/tidb-cloud-manager-rc.yaml"
							def DST_FILE_CONTENT = SRC_FILE_CONTENT.replaceAll('image: localhost:5000/pingcap/tidb-cloud-manager:latest', 'image: {{ .Image }}')
							writeFile file: 'tidb-cloud-manager-rc.yaml.tmpl', text: "${DST_FILE_CONTENT}"
							sh """
							mv tidb-cloud-manager.yaml.tmpl test/e2e/docker/tidb-cloud-manager.yaml.tmpl
							mkdir -p test/e2e/docker/bin
							mv test/e2e/e2e.test test/e2e/docker/bin
							cd test/e2e/docker
							docker build --tag ${E2E_IMAGE} .
							docker push ${E2E_IMAGE}
							"""
						}

						stage('start run cloud-manager e2e test'){
							def SRC_FILE_CONTENT = readFile file: "test/e2e/tidb-cloud-manager-e2e.yaml"
							def DST_FILE_CONTENT = SRC_FILE_CONTENT.replaceAll("image: localhost:5000/ping/tidb-cloud-manager-e2e:1a2e7a7-2017-07-24_01-30-46", "image: ${E2E_IMAGE}")
							writeFile file: 'tidb-cloud-manager-e2e-online.yaml', text: "${DST_FILE_CONTENT}"
							ansiColor('xterm') {
							sh """
							kubectl create -f tidb-cloud-manager-e2e-online.yaml
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
		def getChangeLogText = {
			def changeLogText = ""
			for (int i = 0; i < currentBuild.changeSets.size(); i++) {
				for (int j = 0; j < currentBuild.changeSets[i].items.length; j++) {
					def commitId = "${currentBuild.changeSets[i].items[j].commitId}"
						def commitMsg = "${currentBuild.changeSets[i].items[j].msg}"
						changeLogText += "\n" + "`${commitId.take(7)}` ${commitMsg}"
				}
			}
			return changeLogText
		}
		def CHANGELOG = getChangeLogText()
		def DURATION = (((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60) as double).round(2)
		def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
		"`e2e test`" + "\n" +
		"Elapsed Time: `${DURATION}` Mins" + "\n" +
		"Build Branch: `${TIDB_CLOUD_MANAGER_BRANCH}`, Githash: `${GITHASH.take(7)}`" + "\n" +
		"${CHANGELOG}" + "\n" +
		"Display URL:" + "\n" +
		"${env.RUN_DISPLAY_URL}"

		if(currentBuild.result != "SUCCESS"){
			slackSend channel: '#cloud_jenkins', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-toke', message: "${slackmsg}"
		} else {
			slackSend channel: '#cloud_jenkins', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-toke', message: "${slackmsg}"
		}
	}
}

return this
