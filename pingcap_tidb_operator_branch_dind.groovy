def call(TIDB_OPERATOR_BRANCH) {
	
	env.GOROOT = "/usr/local/go"
	env.GOPATH = "/go"
	env.PATH = "${env.GOROOT}/bin:${env.GOPATH}/bin:/bin:${env.PATH}:/home/jenkins/bin"

	def IMAGE_TAG
	def GITHASH
	def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
	def BUILD_URL = "git@github.com:pingcap/tidb-operator.git"
	def E2E_IMAGE = "localhost:5000/pingcap/tidb-operator-e2e:latest"
	def PROJECT_DIR = "go/src/github.com/pingcap/tidb-operator"

	catchError {
		node('k8s_centos7_build'){
			def WORKSPACE = pwd()

			dir("${PROJECT_DIR}"){
				stage('build tidb-operator binary'){
					git credentialsId: "k8s", url: "${BUILD_URL}", branch: "${TIDB_OPERATOR_BRANCH}"
					GITHASH = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
					sh """
					export GOPATH=${WORKSPACE}/go:$GOPATH
					make
					mkdir -p docker/bin
					mv bin/tidb-* docker/bin

					CGO_ENABLED=0 GOOS=linux GOARCH=amd64 ginkgo build test/e2e
					"""
				}
			}
			stash excludes: "${PROJECT_DIR}/vendor/**", includes: "${PROJECT_DIR}/**", name: "tidb-operator"
		}

		node('k8s-dind') {
			def WORKSPACE = pwd()
			deleteDir()
			unstash 'tidb-operator'

			dir("${PROJECT_DIR}"){
				stage('push tidb-operator images'){
					IMAGE_TAG = "localhost:5000/pingcap/tidb-operator:${GITHASH.take(7)}"
					sh """
					cd docker
					docker build -t ${IMAGE_TAG} .
					docker push ${IMAGE_TAG}
					"""
				}

				stage('start prepare runtime environment'){
					def SRC_FILE_CONTENT = readFile file: "example/tidb-operator.yaml"
					def DST_FILE_CONTENT = SRC_FILE_CONTENT.replaceAll("image: pingcap/tidb-operator:.*", "image: {{ .Image }}")
					DST_FILE_CONTENT = DST_FILE_CONTENT.replaceAll("image: quay.io/coreos/hyperkube:.*", "image: mirantis/hypokube:final")
					writeFile file: 'tidb-operator.yaml.tmpl', text: "${DST_FILE_CONTENT}"
					sh """
					mv tidb-operator.yaml.tmpl test/e2e/docker/tidb-operator.yaml.tmpl
					mkdir -p test/e2e/docker/bin
					mv test/e2e/e2e.test test/e2e/docker/bin
					cd test/e2e/docker
					cat >Dockerfile << __EOF__
FROM pingcap/alpine:3.5

ADD bin/e2e.test /usr/local/bin/e2e.test
ADD tidb-operator.yaml.tmpl /tmp/tidb-operator.yaml.tmpl
ADD data /tmp/data

CMD ["/usr/local/bin/e2e.test", "-ginkgo.v"]
__EOF__
					docker build --tag ${E2E_IMAGE} .
					docker push ${E2E_IMAGE}
					"""
				}

				stage('start run operator e2e test'){
					def SRC_FILE_CONTENT = readFile file: "test/e2e/tidb-operator-e2e.yaml"
					def DST_FILE_CONTENT = SRC_FILE_CONTENT.replaceAll("image:.*", "image: ${E2E_IMAGE}")
					DST_FILE_CONTENT = DST_FILE_CONTENT.replaceAll("operator-image=.*", "operator-image=${IMAGE_TAG}")
					writeFile file: 'tidb-operator-e2e-online.yaml', text: "${DST_FILE_CONTENT}"

					ansiColor('xterm') {
					sh """
					elapseTime=0
					period=5
					threshold=300
					kubectl create -f tidb-operator-e2e-online.yaml
					while true
					do
						sleep \$period
						elapseTime=\$(( elapseTime+\$period ))
						kubectl get po/tidb-operator-e2e -n kube-system 2>/dev/null || continue
						kubectl get po/tidb-operator-e2e -n kube-system|grep Running && break || true
						if [[ \$elapseTime -gt \$threshold ]]
						then
							echo "wait e2e pod timeout, elapseTime: \$elapseTime"
							kubectl delete -f tidb-operator-e2e-online.yaml
							exit 1
						fi
					done
					
					ret=0
					kubectl logs -f tidb-operator-e2e -n kube-system|tee -a result.log
					tail -1 result.log | grep SUCCESS! || ret=\$?
					kubectl delete -f tidb-operator-e2e-online.yaml || true
					rm result.log
					exit \$ret
					"""
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
		"Build Branch: `${TIDB_OPERATOR_BRANCH}`, Githash: `${GITHASH.take(7)}`" + "\n" +
		"${CHANGELOG}" + "\n" +
		"Display URL:" + "\n" +
		"${env.RUN_DISPLAY_URL}"

		if(currentBuild.result != "SUCCESS"){
			slackSend channel: '#cloud_jenkins', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
		} else {
			slackSend channel: '#cloud_jenkins', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
		}
	}
}

return this
