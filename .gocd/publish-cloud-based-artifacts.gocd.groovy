GoCD.script {
  pipelines {
    pipeline('publish-cloud-based-artifacts') {
      environmentVariables = [
        AWS_ACCESS_KEY_ID  : 'AKIAILVYUJN2SXOPFB3Q',
        GOCD_STABLE_RELEASE: 'true',
        GIT_USER           : 'gocd-ci-user',
        version            : '',
        revision           : ''
      ]
      group = 'internal'
      labelTemplate = '${COUNT}'
      lockBehavior = 'none'
      secureEnvironmentVariables = [
        GIT_PASSWORD         : 'AES:VamvCdi7OX38zp33L7SJbw==:lm7xodTUI06gb39yj/qhX6zmxlkFuCjUx0+HHV5kn+ynJ2PNqfOMu1LmQio0u+Tj',
        AWS_SECRET_ACCESS_KEY: 'AES:fhmIGPfd5Jshr38vce0wCA==:iVSSo8qnXsc2QSp6m8dNDNqoAnvvvU5jnZHNnh9NWSzj/nO1CqDJewBDovhpSNlP'
      ]
      materials {
        git('DockerGocdServer') {
          branch = 'master'
          shallowClone = false
          url = 'https://git.gocd.io/git/gocd/docker-gocd-server'
          blacklist = ["Dockerfile"]
          destination = 'docker-gocd-server'
          autoUpdate = true
        }
        git('DockerGocdServerCentOS7') {
          branch = 'master'
          shallowClone = false
          url = 'https://git.gocd.io/git/gocd/docker-gocd-server-centos-7'
          blacklist = ["Dockerfile"]
          destination = 'docker-gocd-server-centos-7'
          autoUpdate = true
        }
        git('DockerGocdAgent') {
          branch = 'master'
          shallowClone = false
          url = 'https://git.gocd.io/git/gocd/docker-gocd-agent'
          destination = 'docker-gocd-agent'
          autoUpdate = true
        }
        git('GocdCloud') {
          branch = 'master'
          shallowClone = false
          url = 'https://git.gocd.io/git/gocd/gocd-cloud'
          destination = 'gocd-cloud'
          autoUpdate = true
        }
        git('GocdChocolatey') {
          branch = 'master'
          shallowClone = true
          url = 'https://git.gocd.io/git/gocd/gocd-chocolatey'
          destination = 'gocd-chocolatey'
          autoUpdate = true
        }
        git('CodeSigning') {
          branch = 'master'
          shallowClone = false
          url = 'https://git.gocd.io/git/gocd/codesigning'
          destination = 'codesigning'
          autoUpdate = true
        }
        git('GoCD') {
          branch = 'master'
          shallowClone = false
          url = 'https://git.gocd.io/git/gocd/gocd'
          destination = 'gocd'
          autoUpdate = true
        }
        dependency('PromoteToStable') {
          pipeline = 'PublishStableRelease'
          stage = 'publish-latest-json'
        }
      }
      stages {
        stage('publish') {
          artifactCleanupProhibited = false
          cleanWorkingDir = true
          fetchMaterials = true
          approval {
            type = 'success'
          }
          jobs {
            job('publish-all-docker-images') {
              elasticProfileId = 'ecs-gocd-dev-build-dind'
              secureEnvironmentVariables = [
                DOCKERHUB_TOKEN: 'AES:KlfOlLUAQMzP/2CZId73YQ==:s8fSRyucqVPMft5PpLPb9bEKc95iN3X5n1f6DK+i/8ZwIFNtw23L5m1y1Qs/RkNoYE34QNrrj1Rk7+4Bphz+yg=='
              ]
              tasks {
                fetchArtifact {
                  job = 'docker-server'
                  pipeline = 'installers/code-sign/PublishStableRelease'
                  runIf = 'passed'
                  source = 'docker-server'
                  stage = 'docker'
                  destination = "codesigning"
                }
                fetchArtifact {
                  job = 'docker-agent'
                  pipeline = 'installers/code-sign/PublishStableRelease'
                  runIf = 'passed'
                  source = 'docker-agent'
                  stage = 'docker'
                  destination = "codesigning"
                }
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = "bundle exec rake docker:publish_docker_images"
                  workingDir = 'codesigning'
                }
                fetchArtifact {
                  job = 'dist'
                  pipeline = 'installers/code-sign/PublishStableRelease'
                  runIf = 'passed'
                  source = 'dist'
                  stage = 'dist'
                  destination = "gocd"
                }
                bash {
                  commandString = 'git config --global user.email "godev+gocd-ci-user@thoughtworks.com" && git config -l'
                  runIf = 'passed'
                  workingDir = "codesigning"
                }
                bash {
                  commandString = './gradlew --parallel --max-workers 4 docker:assemble -PskipDockerBuild -PdockerbuildServerZipLocation=\$(readlink -f zip/go-server-*.zip) -PdockerbuildAgentZipLocation=\$(readlink -f zip/go-agent-*.zip -PdockerGitPush="I_REALLY_WANT_TO_DO_THIS"'
                  workingDir = 'gocd'
                }
              }
            }
            job('publish-server-amis') {
              elasticProfileId = 'ecs-gocd-dev-build'
              runInstanceCount = '1'
              environmentVariables = [
                REGION                     : 'us-east-2',
                EXTRA_AMI_REGION_TO_COPY_TO: 'us-east-1'
              ]
              tasks {
                exec {
                  commandLine = ['bash', '-c', 'wget -q https://releases.hashicorp.com/packer/0.12.3/packer_0.12.3_linux_amd64.zip && unzip packer_0.12.3_linux_amd64.zip']
                  runIf = 'passed'
                  workingDir = "gocd-cloud"
                }
                fetchArtifact {
                  file = true
                  job = 'dist'
                  pipeline = 'installers/code-sign/PublishStableRelease'
                  runIf = 'passed'
                  source = 'dist/meta/version.json'
                  stage = 'dist'
                  destination = ""
                }
                exec {
                  commandLine = ['bash', '-c', 'export GOCD_VERSION=$(jq -r ".go_version" ../version.json) && ./packer build -var gocd_version=${GOCD_VERSION} go-server-packer.json']
                  runIf = 'passed'
                  workingDir = "gocd-cloud"
                }
              }
            }
            job('publish-demo-amis') {
              elasticProfileId = 'ecs-gocd-dev-build'
              runInstanceCount = '1'
              environmentVariables = [
                REGION                     : 'us-east-2',
                EXTRA_AMI_REGION_TO_COPY_TO: 'us-east-1'
              ]
              tasks {
                exec {
                  commandLine = ['bash', '-c', 'wget -q https://releases.hashicorp.com/packer/0.12.3/packer_0.12.3_linux_amd64.zip && unzip packer_0.12.3_linux_amd64.zip']
                  runIf = 'passed'
                  workingDir = "gocd-cloud"
                }
                fetchArtifact {
                  file = true
                  job = 'dist'
                  pipeline = 'installers/code-sign/PublishStableRelease'
                  runIf = 'passed'
                  source = 'dist/meta/version.json'
                  stage = 'dist'
                  destination = ""
                }
                exec {
                  commandLine = ['bash', '-c', 'export GOCD_VERSION=$(jq -r ".go_version" ../version.json) && ./packer build -var gocd_version=${GOCD_VERSION} go-server-demo-packer.json']
                  runIf = 'passed'
                  workingDir = "gocd-cloud"
                }
              }
            }
            job('choco-server') {
              elasticProfileId = 'window-dev-build'
              runInstanceCount = '1'
              environmentVariables = [
                version : '',
                revision: ''
              ]
              secureEnvironmentVariables = [
                apiKey: 'AES:eYt+yWlVJRsuVSK2yWlF7A==:yoASfHRZnEvfyFH/YfVccrvq749J2lZxbgGraHZGXsGqZXg5+gUCHgdmUoTk02+O'
              ]
              tasks {
                fetchArtifact {
                  file = true
                  job = 'dist'
                  pipeline = 'installers/code-sign/PublishStableRelease'
                  runIf = 'passed'
                  source = 'dist/meta/version.json'
                  stage = 'dist'
                  destination = "gocd-chocolatey"
                }
                exec {
                  commandLine = ['powershell', '-ExecutionPolicy',
                                 'ByPass',
                                 '-File',
                                 '.\\createPackage.ps1',
                                 'server']
                  runIf = 'passed'
                  workingDir = "gocd-chocolatey"
                }
                exec {
                  commandLine = ['powershell', 'choco',
                                 'push',
                                 'gocd-server\\gocdserver.$env:version.nupkg',
                                 '-k',
                                 '$env:apiKey']
                  runIf = 'passed'
                  workingDir = "gocd-chocolatey"
                }
              }
            }
            job('choco-agent') {
              elasticProfileId = 'window-dev-build'
              runInstanceCount = '1'
              environmentVariables = [
                version : '',
                revision: ''
              ]
              secureEnvironmentVariables = [
                apiKey: 'AES:BMTjlSq9a2D01RjVhg0iFQ==:MauWWNlRnP9ByoojvFMAKCimEYWNGdGoip5to7iHF+Em2xrh4SyItmufeGUpq9vU'
              ]
              tasks {
                fetchArtifact {
                  file = true
                  job = 'dist'
                  pipeline = 'installers/code-sign/PublishStableRelease'
                  runIf = 'passed'
                  source = 'dist/meta/version.json'
                  stage = 'dist'
                  destination = "gocd-chocolatey"
                }
                exec {
                  commandLine = ['powershell', '-ExecutionPolicy',
                                 'ByPass',
                                 '-File',
                                 '.\\createPackage.ps1',
                                 'agent']
                  runIf = 'passed'
                  workingDir = "gocd-chocolatey"
                }
                exec {
                  commandLine = ['powershell', 'choco',
                                 'push',
                                 'gocd-agent\\gocdagent.$env:version.nupkg',
                                 '-k',
                                 '$env:apiKey']
                  runIf = 'passed'
                  workingDir = "gocd-chocolatey"
                }
              }
            }
          }
        }
        stage('post-publish') {
          artifactCleanupProhibited = false
          cleanWorkingDir = false
          fetchMaterials = true
          approval {
            type = 'success'
          }
          environmentVariables = [
            'EXPERIMENTAL_DOWNLOAD_BUCKET': 'downloadgocdio-experimentaldownloadss3-dakr8wkhi2bo/experimental',
            'STABLE_DOWNLOAD_BUCKET'      : 'downloadgocdio-downloadgocdios3-192sau789jtkh'
          ]
          jobs {
            job('empty_exp_bucket') {
              elasticProfileId = 'ecs-gocd-dev-build'
              runInstanceCount = '1'
              secureEnvironmentVariables = [
                AWS_ACCESS_KEY_ID    : 'AES:+yL/4p2Vh1oiVqkMirOOCw==:eoR5rhgQg3yKpKkDLLdliOlhyjpUts8yk9NfPqB8+eo=',
                AWS_SECRET_ACCESS_KEY: 'AES:HOzGi5HE4ykrhl9LSNMfJg==:zE66pCSyjrQZjr+mzrYcyFrmIliz/T2wdNm0r+4ttYdUQCA73pT5sPEZ8HuKgxfU'
              ]
              tasks {
                exec {
                  commandLine = ['bash', '-c', 'bundle install && bundle exec rake empty_experimental_bucket']
                  runIf = 'passed'
                  workingDir = "codesigning"
                }
              }
            }
            job('update_amis') {
              elasticProfileId = 'ecs-gocd-dev-build'
              runInstanceCount = '1'
              environmentVariables = [
                DOCKERHUB_ORG: 'gocd'
              ]
              secureEnvironmentVariables = [
                AWS_ACCESS_KEY_ID    : 'AES:+yL/4p2Vh1oiVqkMirOOCw==:eoR5rhgQg3yKpKkDLLdliOlhyjpUts8yk9NfPqB8+eo=',
                AWS_SECRET_ACCESS_KEY: 'AES:HOzGi5HE4ykrhl9LSNMfJg==:zE66pCSyjrQZjr+mzrYcyFrmIliz/T2wdNm0r+4ttYdUQCA73pT5sPEZ8HuKgxfU',
                DOCKERHUB_USERNAME   : 'AES:C6gaOdyi+SDGkkvUHni6zw==:I2kqDgvf9GiwD7zzT1UWjQ==',
                DOCKERHUB_PASSWORD   : 'AES:B2dXEmk4/HMqgLITXECK2A==:dfe+7OkQVOss4fFcXbACy1ZMqW8kVWvt8jyMmgzMDb8='
              ]
              tasks {
                fetchArtifact {
                  file = true
                  job = 'dist'
                  pipeline = 'installers/code-sign/PublishStableRelease'
                  runIf = 'passed'
                  source = 'dist/meta/version.json'
                  stage = 'dist'
                  destination = "codesigning"
                }
                exec {
                  commandLine = ['bash', '-c', 'bundle install && bundle exec rake update_cloud_images']
                  runIf = 'passed'
                  workingDir = "codesigning"
                }
              }
            }
            job('docker_cleanup') {
              elasticProfileId = 'ecs-gocd-dev-build'
              runInstanceCount = '1'
              environmentVariables = [
                DOCKERHUB_ORG: 'gocdexperimental'
              ]
              secureEnvironmentVariables = [
                DOCKERHUB_USERNAME: 'AES:C6gaOdyi+SDGkkvUHni6zw==:I2kqDgvf9GiwD7zzT1UWjQ==',
                DOCKERHUB_PASSWORD: 'AES:B2dXEmk4/HMqgLITXECK2A==:dfe+7OkQVOss4fFcXbACy1ZMqW8kVWvt8jyMmgzMDb8='
              ]
              tasks {
                exec {
                  commandLine = ['bash', '-c', 'bundle install && bundle exec rake cleanup_docker']
                  runIf = 'passed'
                  workingDir = "codesigning"
                }
              }
            }
            job('update_dockerhub_full_description') {
              elasticProfileId = 'ecs-gocd-dev-build'
              runInstanceCount = '1'
              environmentVariables = [
                DOCKERHUB_ORG: 'gocdexperimental'
              ]
              secureEnvironmentVariables = [
                DOCKERHUB_USERNAME: 'AES:C6gaOdyi+SDGkkvUHni6zw==:I2kqDgvf9GiwD7zzT1UWjQ==',
                DOCKERHUB_PASSWORD: 'AES:B2dXEmk4/HMqgLITXECK2A==:dfe+7OkQVOss4fFcXbACy1ZMqW8kVWvt8jyMmgzMDb8='
              ]
              tasks {
                exec {
                  commandLine = ['npm', 'install']
                  workingDir = "codesigning"
                }
                exec {
                  commandLine = ['bash', '-c', 'node lib/update_dockerhub_full_description.js gocd']
                  runIf = 'passed'
                  workingDir = "codesigning"
                }
              }
            }
          }
        }
      }
    }
  }
}

