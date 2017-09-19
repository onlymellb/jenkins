def call(BUILD_BRANCH) {

	env.GOPATH = "/go"
	env.GOROOT = "/usr/local/go"
	env.PATH = "${env.GOROOT}/bin:${env.GOPATH}/bin:/bin:${env.PATH}"

	def GITHASH
	def BUILD_URL = "git@github.com:pingcap/tidb-cloud-manager.git"
	def PROJECT_DIR = "go/src/github.com/pingcap/tidb-cloud-manager"

	catchError {
		node('k8s_centos7_build') {
			def WORKSPACE = pwd()

			dir("${PROJECT_DIR}"){
				stage('build managerctl binary'){
					git credentialsId: "k8s", url: "${BUILD_URL}", branch: "${BUILD_BRANCH}"
					GITHASH = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
					sh """
					export GOPATH=${WORKSPACE}/go:$GOPATH
					CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o bin/managerctl-linux-amd64 cmd/managerctl/managerctl.go
					CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -o bin/managerctl-darwin-amd64 cmd/managerctl/managerctl.go

					"""
				}
				stage('upload managerctl binary to qiniu'){
					sh """
					upload.py bin/managerctl-linux-amd64 managerctl-linux-amd64
					sha256sum bin/managerctl-linux-amd64 > managerctl-linux-amd64.sha256
					upload.py managerctl-linux-amd64.sha256 managerctl-linux-amd64.sha256

					upload.py bin/managerctl-darwin-amd64 managerctl-darwin-amd64
					sha256sum bin/managerctl-darwin-amd64 > managerctl-darwin-amd64.sha256
					upload.py managerctl-darwin-amd64.sha256 managerctl-darwin-amd64.sha256
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
		"tidb-cloud-manager Branch: `${BUILD_BRANCH}`, Githash: `${GITHASH.take(7)}`" + "\n" +
		"Display URL:" + "\n" +
		"${env.RUN_DISPLAY_URL}"

		if(currentBuild.result != "SUCCESS"){
			slackSend channel: '#cloud_jenkins', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
		} else {
			slackmsg = "${slackmsg}" + "\n" +
			"managerctl Linux Binary Download URL: http://download.pingcap.org/managerctl-linux-amd64" + "\n" +
			"managerctl Linux Binary sha256: http://download.pingcap.org/managerctl-linux-amd64.sha256" + "\n" +
			"managerctl Darwin Binary Download URL: http://download.pingcap.org/managerctl-darwin-amd64" + "\n" +
			"managerctl Darwin Binary sha256: http://download.pingcap.org/managerctl-darwin-amd64.sha256"
			slackSend channel: '#cloud_jenkins', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
		}
	}
}

return this
