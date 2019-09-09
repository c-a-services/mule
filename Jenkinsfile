def UPSTREAM_PROJECTS_LIST = ["Mule-runtime/metadata-model-api/master",
                              "Mule-runtime/mule-api/master",
                              "Mule-runtime/mule-extensions-api/master",
                              "Mule-runtime/data-weave/master",
                              "Mule-runtime/mule-maven-client/master"]

Map pipelineParams = [upstreamProjects                   : UPSTREAM_PROJECTS_LIST.join(','),
                      mavenSettingsXmlId                 : "mule-runtime-maven-settings-MuleSettings",
                      mavenAdditionalArgs                : "-Djava.net.preferIPv4Stack=true -X -pl :mule-module-deployment-model",
                      mavenCompileGoal                   : "clean install -U -DskipTests -DskipITs -Dinvoker.skip=true -Darchetype.test.skip -Dmaven.javadoc.skip",

                      enableMavenCompileStage            : false,
                      enableMavenTestStage               : true,
                      enableAllureTestReportStage        : false,
                      enableMavenDeployStage             : false,
                      enableSonarQubeStage               : true,
                      enableNexusIqStage                 : false,
                      enableSlackNotifications           : false,
                      enableSlackFailedTestsNotifications: false,
                      enableEmailNotifications           : false,
]

runtimeProjectsBuild(pipelineParams)
