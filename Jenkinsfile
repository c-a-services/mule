@Library('mule-runtime-library@EE-6933')
def UPSTREAM_PROJECTS_LIST = ["Mule-runtime/metadata-model-api/master",
                              "Mule-runtime/mule-api/master",
                              "Mule-runtime/mule-extensions-api/master",
                              "Mule-runtime/data-weave/master",
                              "Mule-runtime/mule-maven-client/master"]

def additionalArgs = "-Djava.net.preferIPv4Stack=true" +
        " -pl " +
        "!:mule-core-tests," +
        "!:mule-module-extensions-spring-support," +
        "!:mule-module-deployment," +
        "!:mule-tests-performance," +
        "!:mule-module-extensions-xml-support," +
        "!:mule-module-extensions-support," +
        "!:mule-module-artifact," +
        "!:mule-module-spring-config"

Map pipelineParams = ["upstreamProjects"                         : UPSTREAM_PROJECTS_LIST.join(','),
                      "mavenSettingsXmlId"                       : "mule-runtime-maven-settings-MuleSettings",
                      "mavenAdditionalArgs"                      : additionalArgs,
                      "mavenCompileGoal"                         : "clean install -U -DskipTests -DskipITs -Dinvoker.skip=true -Darchetype.test.skip -Dmaven.javadoc.skip",
                      "jacocoExecutionDataFileLocation"          : "target/jacoco.exec",

                      "enableMavenCompileStage"                  : false,
                      "enableMavenTestStage"                     : true,
                      "enableAllureTestReportStage"              : false,
                      "enableMavenDeployStage"                   : false,
                      "enableSonarQubeStage"                     : true,
                      "enableNexusIqStage"                       : false,
                      "enableSlackNotifications"                 : false,
                      "enableSlackFailedTestsNotifications"      : false,
                      "enableEmailNotifications"                 : false,
                      "enableDownloadPrepopulatedMavenRepoFromS3": true
]

runtimeProjectsBuild(pipelineParams)
