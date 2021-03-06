
GoCD.script {
  pipelines {
    pipeline('updated_post_release_github_activities') {
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
          pipeline = 'promote-stable-release'
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
                  pipeline = 'installers/code-sign/promote-stable-release'
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
                  commandLine = ['node', 'draft_new_release.js']
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

