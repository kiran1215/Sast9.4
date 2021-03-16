#!/groovy
@Library('cx-jenkins-pipeline-kit') _

import jenkins.*
import jenkins.model.*
import hudson.*
import hudson.model.*

def ipAddress
def vmName = 'Sast-pi-dev-' + BUILD_NUMBER
def installationLogsClean = "${vmName}_Build_${BUILD_NUMBER}_Installation"
def installationFilePath = "C:\\CI-slave\\checkmarx\\installation\\"
def DOMAIN_NAME = 'rnd.local'


env.AUTO_BRANCH = ""
env.CUR_INSTALLED_VERSION = ""


def setPipelineInfo() {
    //testing using bat command to extract checkmarx installation version
    if ((sastVersion == null || sastVersion == "") && params.installCheckmarx ) {
        if (buildDef == null || buildDef == "") {
            currentBuild.result = 'ABORTED'
            currentBuild.description = "Invalid params, see console output"
            error 'You must specify CxSAST version or at least build definition in build parameters.'
        }

        //verify all requested artifacts are found, and if left empty, to get latest
        sastVersion = kit.getLatestArtifactVersionFromNexusArtifactory("${env.NEXUS_DIR}", buildDef, "CxSetup.exe")

        kit.Info_Msg("Setting Jenkins version parameter to last successful build - version: [${sastVersion}]")
        def build = currentBuild.getRawBuild()
        def newVersionParameter = new StringParameterValue('sastVersion', "$sastVersion", "Build full sastVersion wasn't specified-latest build of ${buildDef} was retrieved")
        ParametersAction paramActions = build.actions.find {
            it instanceof ParametersAction
        }
        build.replaceAction(paramActions.merge(new ParametersAction(newVersionParameter)))
    }

    if (params.templateType.equals('custom')) {
        if (params.templateName.equals('')) {
            currentBuild.result = 'ABORTED'
            currentBuild.description = "Invalid params, see console output"
            error 'When selecting custom template, you must enter template name'
        } else {
            env.TEMPLATE = params.customTemplate
        }
    } else {
        env.TEMPLATE = params.template
    }

    sh 'printenv'
    currentBuild.description = ""
    //only print currentBuild.description after retrieving actually installed version after each installation
}

timeout(60) {
    node('install01') {
        stage('Load Libraries and Scripts') {
            library identifier: 'vars\\SastDSL.groovy@master', retriever: modernSCM(
                    [$class       : 'GitSCMSource',
                     remote       : 'http://tfs2013:8080/tfs/DefaultCollection/DevOps/_git/Dynamic-Ci',
                     credentialsId: '15f8e7b7-6ce7-44c0-b151-84f99ffa7aed'])

            SastDSL.importLibrary 'sastGlobalParams'

            checkout scm
            load 'vars/sastGlobalParams.groovy'

            env.NEXUS_DIR = params.nexusDirectory

            println('Printing params')
            params.each {
                println(it.key + ": " + it.value)
            }
        }

        stage('Init Pipeline') {
            deleteDir()

            setPipelineInfo()

            if (params.abortToEffectParamChanges) {
                currentBuild.result = 'ABORTED'
                currentBuild.description = "aborted by dev"
                error('aborting just to set params')
            }
        }

        stage('Create VM with Base Template') {
            kit.Create_Vm_Terraform(vmName, env.TEMPLATE, ram, cpu, provider, decommissionPeriod, "Auto", params.resourcePool)
            ipAddress = kit.getIpAddress(vmName, provider)
            kit.Create_Jenkins_Slave_On_Master(vmName)
            kit.Start_Jenkins_Slave_On_Windows_Pstools(ipAddress, vmName)
        }

        stage('host name before rename') {
            node(vmName) {
                env.templateHostNameBeforeRename=kit.Command_Execution_Powershell("Invoke-Expression -Command 'hostname'")
            }
        }

        if (params.renameAndJoinToDomain) {

            stage('Reset SQL User Password') {
                node(vmName) {
                    //for some reason password for user is expired, must reset it
                    String UPDATE_PASSWORD_SQL_USER = "ALTER LOGIN ${env.installationSqlUser}  WITH PASSWORD = '${env.installationSqlPassword}'"
                    lab.executeSQLcommand("${ipAddress}\\SQLEXPRESS", "master", UPDATE_PASSWORD_SQL_USER)
                }
            }

            //TODO consider custom templates as well - possibly implement logic to check for existing installation, if yes, need to update DB
            if(params.templateType.equals('upgrade') && params.ssl == false) { //ssl false, because when true, is dealt with by special SSL config methods
                node(vmName) {
                    env.CUR_INSTALLED_VERSION = SastDSL.getInstalledVersion()
                    SastDSL.updateDBHostnameValues(vmName, DOMAIN_NAME, ipAddress, env.CUR_INSTALLED_VERSION)
                }
            }

            stage('Rename VM and join to domain') {
                kit.renameMachine(ipAddress, vmName, vmName)
                lab.joinToDomain(vmName, ipAddress, DOMAIN_NAME)
                sleep(30)
            }

            kit.Start_Jenkins_Slave_On_Windows_Pstools(ipAddress, vmName)
        }

        if (params.ssl) {
            stage('Register vm in cxqulity dns') {
                node(vmName) {
                    kit.Info_Msg("SSL - Add VM to cxquality dns")
                    kit.addDnsRecord("cxquality.com", vmName, ipAddress)
                    bat "ipconfig /flushdns"
                }
            }
        }

        if (params.installCheckmarx) {
            stage('Install Checkmarx') {
                node(vmName) {
                    if (params.templateType.equals('upgrade')) {
                        env.CUR_INSTALLED_VERSION = SastDSL.getInstalledVersion()
                        currentBuild.description += "${env.CUR_INSTALLED_VERSION}"
                        currentBuild.description += "<br>"
                    } else if (params.templateType.equals('custom')){
                        try{
                            env.CUR_INSTALLED_VERSION = SastDSL.getInstalledVersion()
                            currentBuild.description += "${env.CUR_INSTALLED_VERSION}"
                            currentBuild.description += "<br>"
                        } catch (err){
                            println('could not find CxSast version, probably the custom template is clean')
                        }
                    }

                    if (env.CUR_INSTALLED_VERSION.contains('8.9')) {
                        source = kit.getArtifactLinkFromNexusArtifactory("${env.NEXUS_DIR}", sastVersion, "CxSetup.AC_and_Migration.exe")
                        destination = installationFilePath + "CxSetup.AC_and_Migration.exe"
                        kit.downloadArtifactFromNexusArtifactory(source, destination)
                        //CxSetup.AC_and_Migration.exe /quiet /install ACCESSCONTROL=1 ACCEPT_EULA=Y INSTALLSHORTCUTS=1 SQLAUTH=1 SQLSERVER=SQL_SERVER_INSTANCE SQLUSER=SQL_USER SQLPWD=SQL_PASSWORD
                        String ssl = ""
                        if(params.ssl)
                            ssl = "CXSAST_ADDRESS=https://${vmName}.cxquality.com:443"

                        acMigrationScript = """Start-Process -FilePath "${destination}" -ArgumentList "/s ACCESSCONTROL=1 ACCEPT_EULA=Y SQLAUTH=1 SQLSERVER=${installationSqlServer} SQLUSER=${installationSqlUser} SQLPWD=${installationSqlPassword} ${ssl}" -Wait"""
                        //kit.runAcMigration(destination)
                        powershell returnStdout: true, script: "${acMigrationScript}"
                        //kit.Command_Execution_Powershell(acMigrationScript)
                        //TODO copy method and include sql user and password params just like in installCheckmarxSastAIO
                    }

                    source = kit.getArtifactLinkFromNexusArtifactory("${env.NEXUS_DIR}", sastVersion, "CxSetup.exe")
                    destination = installationFilePath + "\\CxSetup.exe"
                    kit.downloadArtifactFromNexusArtifactory(source, destination)

                    //See https://checkmarx.atlassian.net/wiki/spaces/KC/pages/1169686624/CxSAST+CxOSA+CLI+Silent+Install+Uninstall+v9.0.0+and+up
                    String additionalComponent = "BI=0"
                    if (params.installMNO)
                        additionalComponent = "BI=1"

                    kit.installCheckmarxSastAIO(true, destination, installationLicensePath, installationSqlServer, installationSqlUser, installationSqlPassword, additionalComponent)

                    env.CUR_INSTALLED_VERSION = SastDSL.getInstalledVersion()

                    env.AUTO_BRANCH = SastDSL.determineAutoBranch(env.CUR_INSTALLED_VERSION)
                    git branch: "${env.AUTO_BRANCH}", credentialsId: '15f8e7b7-6ce7-44c0-b151-84f99ffa7aed', poll: false, url: 'http://tfs2018app.dm.cx:8080/tfs/DefaultCollection/Automation/_git/Checkmarx-Reactor'

                    if(params.ssl){
                        if(params.templateType.equals('clean')){
                            env.CUR_INSTALLED_VERSION = SastDSL.getInstalledVersion()
                            env.AUTO_BRANCH = SastDSL.determineAutoBranch(env.CUR_INSTALLED_VERSION)
                            SastDSL.configureCleanSSL(env.AUTO_BRANCH, params.installMNO, vmName) //post install is included
                        } else {
                            SastDSL.configureUpgradeSSL(ipAddress, vmName, env.templateHostNameBeforeRename)
                            //post install verification
                            bat """mvn -q clean install -Drun.https.verification=true -Djob.name=${job_name} -Dserver.name=${vmName}"""
                        }
                    } else {
                        bat """mvn -q clean install -Drun.verification=true -Djob.name=${job_name}"""
                    }

                    currentBuild.description += "${env.CUR_INSTALLED_VERSION}"
                }
            }
        } else {
            if (params.ssl) {
                stage('Install Checkmarx') {
                    node(vmName) {
                        SastDSL.configureUpgradeSSL(ipAddress, vmName, env.templateHostNameBeforeRename)
                    }
                }
            }
            else {
                println('No installation selected. This is probably because you selected a template with Checkmarx already installed')
            }
        }

        if (params.additionalUpgrade.equals('true')) { //string because it's not a boolean param
            stage('Additional Upgrade') {
                node(vmName) {

                    //perform initial log in, otherwise license error
                    String firstAdminJsonStr = '{"userName": "admin@cx","password": "Cx123456!","firstName": "admin","lastName": "admin","email": "admin@cx.com" }'

                    String acURL = 'http://localhost/CxRestAPI/auth/Users/FirstAdmin'

                    def response = httpRequest url: acURL, contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: firstAdminJsonStr
                    println("Status: " + response.status)
                    println("Content: " + response.content)

                    String loginURL = 'http://localhost/CxRestAPI/auth/identity/connect/token'

                    String loginStr = 'username=admin@cx&password=Cx123456!&grant_type=password&scope=sast_api access_control_api&client_id=resource_owner_sast_client&client_secret=014DF517-39D1-4453-B7B3-9930C563627C'

                    def loginRes = httpRequest url: loginURL, contentType: 'APPLICATION_FORM', httpMode: 'POST', requestBody: loginStr
                    println("Status: " + loginRes.status)
                    println("Content: " + loginRes.content)

                    //running test to initiate login
//                    git branch: "${env.AUTO_BRANCH}", credentialsId: '15f8e7b7-6ce7-44c0-b151-84f99ffa7aed', poll: false, url: 'http://tfs2018app.dm.cx:8080/tfs/DefaultCollection/Automation/_git/Checkmarx-System-Test'
//                    try{
//                        bat """mvn clean test -Dtest=com.cx.automation.test.ws.rest.project.GetProjectByIdTest -Denv=ci_env -DfailIfNoTests=false -Dmaven.test.failure.ignore=true"""
//                    } catch(err){
//                        println(err)
//                    }

                    env.NEXUS_DIR = params.additionalNexusRepo

                    source = kit.getArtifactLinkFromNexusArtifactory("${env.NEXUS_DIR}", params.additionalSastVersion, "CxSetup.exe")
                    destination = installationFilePath + "\\CxSetup.exe"
                    kit.downloadArtifactFromNexusArtifactory(source, destination)

                    //See https://checkmarx.atlassian.net/wiki/spaces/KC/pages/1169686624/CxSAST+CxOSA+CLI+Silent+Install+Uninstall+v9.0.0+and+up
                    def additionalComponent = "BI=0"
                    if (params.installMNO)
                        additionalComponent = "BI=1"

                    kit.installCheckmarxSastAIO(true, destination, installationLicensePath, installationSqlServer, installationSqlUser, installationSqlPassword, additionalComponent)

                    //Post install verification
                    env.CUR_INSTALLED_VERSION = SastDSL.getInstalledVersion()
                    env.AUTO_BRANCH = SastDSL.determineAutoBranch(env.CUR_INSTALLED_VERSION)
                    git branch: "${env.AUTO_BRANCH}", credentialsId: '15f8e7b7-6ce7-44c0-b151-84f99ffa7aed', poll: false, url: 'http://tfs2018app.dm.cx:8080/tfs/DefaultCollection/Automation/_git/Checkmarx-Reactor'
                    bat """mvn -q clean install -Drun.verification=true -Djob.name=${job_name}"""

                    currentBuild.description += "<br>${env.CUR_INSTALLED_VERSION}"
                }
            }
        }


        if (params.fipsEnabled) {
            node(vmName) {
                lab.executeBatCommand("reg add \"HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Lsa\\FipsAlgorithmPolicy\" /v \"enabled\" /t \"REG_DWORD\" /d \"1\" /f")
            }
        }

        stage('Restart VM') {
            SastDSL.restartVM(ipAddress, vmName)
            kit.Start_Jenkins_Slave_On_Windows_Pstools(ipAddress, vmName)
        }

        stage('Post Actions') {
            logstashSend failBuild: false, maxLines: 1000
            try {
                kit.Info_Msg("unstash installationLogsClean")
                unstash installationLogsClean
                archiveArtifacts '*.zip'
            } catch (Exception e) {
                kit.Warning_Msg("No clean installation logs & tests captures to archive. Exception:\n" + e.toString())
            }

            kit.Info_Msg("Download Summary File")
            try {
                def source = kit.getArtifactLinkFromNexusArtifactory("CxSast", sastVersion, "Summary.html")
                def destination = pwd() + "/Summary.html"
                kit.downloadArtifactFromNexusArtifactory(source, destination)
            } catch (e) {
                kit.Warning_Msg("No Summary.html file to archive. Exception:\n" + e.toString())
            }

            try {
                archiveArtifacts '*.zip, Summary.html'
            } catch (err) {
                println(err)
            }

            //add ip to currentBuild.description
            currentBuild.description += "<br>Machine Name: ${vmName}"
            currentBuild.description += "<br>IP: ${ipAddress}"

            //email to user
            def subject = "Created VM with base template ${env.TEMPLATE} and upon it installed ${sastVersion}"
            def sslUrl = ""
            if (params.ssl)
                sslUrl = ",SSL url: https://${vmName}.${env.SSL_DOMAIN_NAME}/cxwebclient"
            def body = "Machine name: ${vmName}, Machine IP: ${ipAddress}${sslUrl}"
            def emailRecipient = kit.Get_User_Email()

            kit.Send_Email_Private(emailRecipient, subject, body)
        }
    }
}
