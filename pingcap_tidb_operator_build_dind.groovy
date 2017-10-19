def replace(file) {
	def SRC_E2E_FILE_CONTENT = readFile file: file
	def DST_E2E_FILE_CONTENT = SRC_E2E_FILE_CONTENT.replaceAll("image: pingcap/pd:.*", "image: localhost:5000/pingcap/pd:v1.0.0")
	DST_E2E_FILE_CONTENT = DST_E2E_FILE_CONTENT.replaceAll("image: pingcap/tidb:.*", "image: localhost:5000/pingcap/tidb:v1.0.0")
	DST_E2E_FILE_CONTENT = DST_E2E_FILE_CONTENT.replaceAll("image: pingcap/tikv:.*", "image: localhost:5000/pingcap/tikv:v1.0.0")
	writeFile file: file, text: "${DST_E2E_FILE_CONTENT}"
}

def call(BUILD_BRANCH) {

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
					git credentialsId: "k8s", url: "${BUILD_URL}", branch: "${BUILD_BRANCH}"
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
					def SRC_FILE_CONTENT = readFile file: "manifests/tidb-operator.yaml"
					def DST_FILE_CONTENT = SRC_FILE_CONTENT.replaceAll("image: pingcap/tidb-operator:.*", "image: {{ .Image }}")
					DST_FILE_CONTENT = DST_FILE_CONTENT.replaceAll("image: quay.io/coreos/hyperkube:.*", "image: mirantis/hypokube:final")
					writeFile file: 'tidb-operator.yaml.tmpl', text: "${DST_FILE_CONTENT}"

					replace("test/e2e/docker/data/e2e-bestfit.yaml")
					replace("test/e2e/docker/data/e2e-decrease.yaml")
					replace("test/e2e/docker/data/e2e-ha.yaml")
					replace("test/e2e/docker/data/e2e-increase.yaml")
					replace("test/e2e/docker/data/e2e-pause.yaml")
					replace("test/e2e/docker/data/e2e-upgrade.yaml")
					replace("test/e2e/docker/data/e2e-volume.yaml")
					replace("test/e2e/docker/data/e2e-delete.yaml")
					replace("test/e2e/docker/data/e2e-retention.yaml")

					sh """
					mv tidb-operator.yaml.tmpl test/e2e/docker/tidb-operator.yaml.tmpl
					mkdir -p test/e2e/docker/bin
					mv test/e2e/e2e.test test/e2e/docker/bin
					cd test/e2e/docker
					cat >Dockerfile << __EOF__
FROM pingcap/alpine-kube:latest

ADD bin/e2e.test /usr/local/bin/e2e.test
ADD tidb-operator.yaml.tmpl /tmp/tidb-operator.yaml.tmpl
ADD data /tmp/data
ADD run_e2e /usr/local/bin/run_e2e
RUN chmod +x /usr/local/bin/run_e2e

CMD ["/usr/local/bin/run_e2e"]
__EOF__
					docker build --tag ${E2E_IMAGE} .
					docker push ${E2E_IMAGE}
					"""
				}

				stage('start run operator e2e test'){
					def SRC_FILE_CONTENT = readFile file: "test/e2e/manifests/tidb-operator-e2e.yaml"
					def DST_FILE_CONTENT = SRC_FILE_CONTENT.replaceAll("image:.*", "image: ${E2E_IMAGE}")
					DST_FILE_CONTENT = DST_FILE_CONTENT.replaceAll("value: localhost:5000/pingcap/tidb-operator:latest", "value: ${IMAGE_TAG}")
					writeFile file: 'tidb-operator-e2e-online.yaml', text: "${DST_FILE_CONTENT}"

					ansiColor('xterm') {
					sh """
					elapseTime=0
					period=5
					threshold=300
					kubectl delete -f tidb-operator-e2e-online.yaml || true
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
							exit 1
						fi
					done

					execType=0
					while true
					do
						if [[ \$execType -eq 0 ]]
						then
							kubectl logs -f tidb-operator-e2e -n kube-system|tee -a result.log
						else
							execType=0
							kubectl logs --tail 1 -f tidb-operator-e2e -n kube-system|tee -a result.log
						fi
						## verify that the log output is exit normally
						tail -5 result.log|egrep 'Test Suite Failed|Test Suite Passed'||execType=\$?
						[[ \$execType -eq 0 ]] && break
					done

					ret=0
					tail -1 result.log | grep 'Test Suite Passed' || ret=\$?
					rm result.log
					exit \$ret
					"""
					}
				}

				stage('upload tidb-operator binary'){

					//upload binary
					sh """
					cp ~/bin/config.cfg ./
					tar zcvf tidb-operator.tar.gz docker/bin/*
					filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key builds/pingcap/operator/${GITHASH}/centos7/tidb-operator.tar.gz --file tidb-operator.tar.gz
					"""

					//update refs
					writeFile file: 'sha1', text: "${GITHASH}"
					sh """
					filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key refs/pingcap/operator/${BUILD_BRANCH}/centos7/sha1 --file sha1
					rm -f sha1 tidb-operator.tar.gz config.cfg
					"""
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
		"`${BUILD_BRANCH} Build`" + "\n" +
		"Elapsed Time: `${DURATION}` Mins" + "\n" +
		"Build Branch: `${BUILD_BRANCH}`, Githash: `${GITHASH.take(7)}`" + "\n" +
		"${CHANGELOG}" + "\n" +
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

return this
