/**
* This file contains jobs for wss pipeline.
* In order to create pipeline for a new branch, add it to branches array
*/
def gitn = 'git@gitlab.com:wickesit/legacy-retail/wickes-store-systems.git'
def branches = ['origin/development', 'origin/master', 'origin/feat/paypal']

def getBuildName(def branchName, def jobName) {
    def buildNameSuffix = '${BUILD_NUMBER} (${ENV,var=\"VERSION\"})'
    return "wss-${branchName}-${jobName}-${buildNameSuffix}"
}

/**
* Start of the loop to create pipeline for each branch
*/
branches.each { branchName ->
    def shortName = branchName.replaceAll('origin/','').replaceAll('elopment','').replace('/', '-')
    def tag_name = 'build/${VERSION/%\\0/$BUILD_NUMBER}'
    def build_number = '$BUILD_NUMBER'
    /**
    * Main job is meta-task that executes all downstream projects of pipeline and act as a supervisor for their results
    */
    freeStyleJob("wss-${shortName}-MAIN") {
        label("win2008r2-wss")
        logRotator(-1,5)
        customWorkspace('C:\\jenkins\\workspace\\wss-master-MAIN')
        throttleConcurrentBuilds {
            categories(['wss'])
        }
        parameters {
            stringParam("branch", branchName)
        }
        scm {
            git {
                remote{ url(gitn) }
                branch('${branch}')
            }
        }
        triggers {
            scm("H/5 * * * *")
        }
        wrappers {
            buildName("wss-${shortName}-MAIN-${build_number}")
        }
        steps {
            environmentVariables {
                propertiesFile("config/env_vars.properties")
            }
            conditionalSteps {
                condition {
                    alwaysRun()
                }
                runner("Fail")
                steps {
                    downstreamParameterized {
                        trigger("wss-${shortName}-build") {
                            block {
                                buildStepFailure('UNSTABLE')
                                failure('UNSTABLE')
                                unstable('UNSTABLE')
                            }
                            parameters {
                                predefinedProp('REVISION', '${BUILD_NUMBER}')
                                gitRevision(false)
                            }
                        }
                    }
                }
            }
            conditionalSteps {
                condition {
                    status("SUCCESS", "SUCCESS")
                }
                runner("Fail")
                steps {
                    downstreamParameterized {
                        trigger("wss-${shortName}-ut,wss-${shortName}-aat,wss-${shortName}-pkg") {
                            block {
                                buildStepFailure('UNSTABLE')
                                failure('UNSTABLE')
                                unstable('UNSTABLE')
                            }
                        }
                    }
                }
            }
        }
        publishers {
            postBuildScripts {
                steps {
                    shell("git tag ${tag_name}_wss-${shortName}-MAIN-${build_number}\ngit push --tags")
                }
                markBuildUnstable(false)
                onlyIfBuildFails(false)
                onlyIfBuildSucceeds(true)
            }
            extendedEmail {
                recipientList('$DEFAULT_RECIPIENTS')
                defaultSubject('$DEFAULT_SUBJECT')
                defaultContent('$DEFAULT_CONTENT')
                contentType('text/html')
                triggers {
                    failure()
                    fixed()
                    stillFailing()
                }
            }
        }
    }

    /**
    * Build job produces binaries of Till and BO, also makes a packaging of artifacts for downstream jobs to use
    */
    freeStyleJob("wss-${shortName}-build") {
        label("win2008r2-wss")
        logRotator(-1,3)
        throttleConcurrentBuilds {
            categories(['build'])
        }
        scm {
            git {
                remote { url(gitn) }
                branch(branchName)
                extensions {
                    cleanAfterCheckout()
                    cloneOptions {
                        shallow(true)
                        timeout(20)
                    }
                }
            }
        }
        wrappers {
            def buildNameSuffix = '${BUILD_NUMBER} (#${ENV,var=\"REVISION\"})'
            buildName("wss-${shortName}-build-${buildNameSuffix}")
        }
        steps {
            batchFile("gradlew upd_ver go")
        }
        publishers {
            archiveArtifacts("out/, rakelib/, config/env_vars.properties, Libs/, db/, gradle/, gradlew, gradlew.bat, build.gradle")
        }
    }

    /**
    * Produce .zip archive of our builing artifacts, ready to deploy
    */
    freeStyleJob("wss-${shortName}-pkg") {
        label('win2008r2-wss')
        logRotator(-1,2)
        wrappers {
            buildName(getBuildName(shortName, "pkg"))
        }
        steps {
            shell("rm -rf *")
            copyArtifacts("wss-${shortName}-build") {
                buildSelector {
                    latestSuccessful(true)
                }
            }
            copyArtifacts("build_supplementary") {
                buildSelector {
                    latestSuccessful(true)
                }
            }
            environmentVariables {
                propertiesFile("config/env_vars.properties")
            }
            batchFile("gradlew pkg_zip")
        }
        publishers {
            archiveArtifacts("out/**/*.zip, Libs/gdrive/")
        }
    }

    /**
    * Unit tests, both mstest and NUnit
    */
    freeStyleJob("wss-${shortName}-ut") {
        label("win2008r2-wss")
        logRotator(-1,5)
        throttleConcurrentBuilds {
            categories(['fit'])
        }
        wrappers {
            buildName(getBuildName(shortName, "ut"))
        }
        steps {
            shell("rm -rf *")
            copyArtifacts("wss-${shortName}-build") {
                buildSelector {
                    latestSuccessful(true)
                }
            }
            environmentVariables {
                propertiesFile("config/env_vars.properties")
            }
            batchFile("gradlew ut")
        }
        publishers {
            archiveXUnit {
                msTest {
                    pattern("out/ut/mstestBOResults.trx");
                }
                nUnit {
                    pattern("out/ut/*.xml");
                }
            }
        }
    }

    freeStyleJob("wss-${shortName}-aat") {
        label("win2008r2-wss")
        logRotator(-1,5)
        throttleConcurrentBuilds {
            categories(['fit'])
        }
        wrappers {
            buildName(getBuildName(shortName, "aat"))
        }
        steps {
            shell("rm -rf *")
            copyArtifacts("wss-${shortName}-build") {
                buildSelector {
                    latestSuccessful(true)
                }
            }
            environmentVariables {
                propertiesFile("config/env_vars.properties")
            }
            batchFile ("gradlew aat")
        }
        publishers {
            publishHtml{
                report("out/aat/pickles") {
                    reportName('Pickles HTML Report')
                    reportFiles("Index.html")
                    keepAll(true)
                }
                report("out/aat/specflow") {
                    reportName('SpecFlow HTML Report')
                    keepAll(true)
                }
            }
            archiveXUnit {
                nUnit {
                    pattern 'out/aat/WSS.AAT.Scenarios.*.xml'
                }
                failedThresholds {
                    unstable(0)
                    unstableNew(0)
                    failure(0)
                    failureNew(0)
                }
            }
        }
    }

    /**
    * Google Drive publishing artifacts
    */
    freeStyleJob("wss-${shortName}-gdrive-UAT") {
        label("win2008r2-wss")
        logRotator(-1,5)
        parameters {
            booleanParam("srv8971", false)
            booleanParam("srv8972", false)
            booleanParam("srv8973", false)
            booleanParam("srv8974", false)
        }
        steps {
            shell("rm -rf *")
            copyArtifacts("wss-${shortName}-pkg") {
                buildSelector {
                    latestSuccessful(true)
                }
            }
            batchFile("echo srv8971=%srv8971% > deploy.properties\necho srv8972=%srv8972% >> deploy.properties\necho srv8973=%srv8973% >> deploy.properties\necho srv8974=%srv8974% >> deploy.properties")
            shell('''filename=`ls out/ | grep ^wss`
echo BUILD_ARCHIVE=$filename >> deploy.properties
echo
Libs/gdrive/gdrive-windows-386.exe upload out/$filename -p 0B-dEiT2xitCKdkhTSllOcDF4Vmc
Libs/gdrive/gdrive-windows-386.exe upload deploy.properties -p 0B-dEiT2xitCKdkhTSllOcDF4Vmc''')
        }
    }

    freeStyleJob("wss-${shortName}-gdrive") {
        label("win2008r2-wss")
        logRotator(-1,5)
        steps {
            shell("rm -rf *")
            copyArtifacts("wss-${shortName}-pkg") {
                buildSelector {
                    latestSuccessful(true)
                }
            }
            shell('''
for f in `ls out/`
do
Libs/gdrive/gdrive-windows-386.exe upload out/$f -p 0B5dRGBdUYoRQemNCUHRGMzFaVGc
done''')
        }
    }

    /**
    * Create list view containing all jobs from pipeline
    */
    listView("wss-${shortName}") {
        description("WSS ${branchName} branch")
        jobs {
            name("wss-${shortName}")
            regex("wss-${shortName}-.+")
        }
        columns {
            status()
            weather()
            name()
            lastSuccess()
            lastFailure()
            lastDuration()
            buildButton()
        }
    }
}
/**
* This job makes baseline scripts
*/
freeStyleJob("make_baseline_sql") {
    label("win2008r2-wss")
    logRotator(-1,2)
    parameters {
        stringParam("version", "3.99.0")
    }
    steps {
        shell("rm -rf *")
        copyArtifacts("wss-dev-build") {
            buildSelector {
                latestSuccessful(true)
            }
        }
        copyArtifacts("build_supplementary") {
            buildSelector {
                latestSuccessful(true)
            }
        }
        batchFile("copy scripts\\clear_incremental.rb .")
        shell('ruby clear_incremental.rb $version')
        batchFile("gradlew prep_db")
        batchFile("ScriptRunner\\bin\\ScriptRunnerConsoleUI.exe c=GenerateScripts db=oasys-fitnesse-dbserver;Oasys;;Cyrillic_General_CI_AI sd=Scripts so=PBD")
        batchFile("gradlew fix_baseline")
    }
    publishers {
        archiveArtifacts{
            pattern("scripts/")
            exclude("**/*.rb")
        }
    }
}

freeStyleJob("wss-sonar") {
    label("win2008r2-wss")
    scm {
        git {
            remote { url(gitn) }
            branch('origin/development')
        }
    }
    triggers {
        scm("H/5 * * * *")
    }
    steps {
        batchFile('''cd src
MSBuild.SonarQube.Runner.exe begin /k:"tp:wss" /n:"wss" /v:"1.0"
"C:\\Program Files\\MSBuild\\14.0\\Bin\\MSBuild.exe" "wss-boreceiver.sln" /t:Rebuild /p:VisualStudioVersion=14.0
MSBuild.SonarQube.Runner.exe end''')
    }
}
