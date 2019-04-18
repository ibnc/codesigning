
GoCD.script {
  pipelines {
    pipeline('post-release-github-activities') {
      group = 'internal'
      labelTemplate = '${COUNT}'
      lockBehavior = 'none'
      materials {
        git('codesigning') {
          branch = 'master'
          destination = 'codesigning'
          shallowClone = false
          url = 'https://git.gocd.io/git/gocd/codesigning'
        }
        dependency('PromoteToStable') {
          pipeline = 'PublishStableRelease'
          stage = 'publish-latest-json'
        }
      }
      stages {
        stage('draft-release') {
          artifactCleanupProhibited = false
          cleanWorkingDir = false
          fetchMaterials = true
          approval {
          }
          jobs {
            job('draft-release') {
              elasticProfileId = 'ecs-gocd-dev-build'
              runInstanceCount = '1'
              secureEnvironmentVariables = [
                GITHUB_TOKEN: 'AES:Q4IFE6x/f+VCqxpR01+daA==:/fXVmWSK2TBd6cP4I9diey57vcDVVCgHYmh74CjksPprlknmJ0G9OEfdPbjy6uj8',
              ]
              timeout = 0
              tasks {
                fetchArtifact {
                  destination = 'codesigning'
                  file = true
                  job = 'dist'
                  pipeline = 'installers/code-sign/PublishStableRelease'
                  runIf = 'passed'
                  source = 'dist/meta/version.json'
                  stage = 'dist'
                }
                exec {
                  commandLine = ['npm', 'install']
                  runIf = 'passed'
                  workingDir = 'codesigning'
                }
                exec {
                  commandLine = ['node', 'lib/draft_new_release.js']
                  runIf = 'passed'
                  workingDir = 'codesigning'
                }
              }
            }
          }
        }
      }
    }
  }
}

