/*
 * Copyright 2019 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

def fetchArtifactTask = { String osType ->
  return new FetchArtifactTask(false, {
    pipeline = 'installers'
    stage = 'dist'
    job = 'dist'
    source = "dist/${osType}"
    destination = "codesigning/src"
  })
}

def cleanTasks = {
  return [
    new ExecTask({
      commandLine = ['git', 'clean', '-dffx']
      workingDir = 'codesigning'
    })
  ]

}
def createRakeTask = { String osType ->
  return new ExecTask({
    commandLine = ["rake", "--trace", "${osType}:sign"]
    workingDir = 'codesigning'
  })
}

def publishArtifactTask = { String osType ->
  return new BuildArtifact('build', {
    source = "codesigning/out"
    destination = "out"
  })
}

def getArtifact = { String source1 ->
  return new FetchArtifactTask(false, {
    pipeline = 'code-sign'
    file = true
    stage = 'aggregate-jsons'
    job = 'aggregate-jsons'
    source = "out/latest.json"
    destination = "codesigning"
  })
}

def secureEnvironmentVariableForGoCD = [
  GOCD_GPG_PASSPHRASE  : 'AES:7lAutKoRKMuSnh3Sbg9DeQ==:8fhND9w/8AWw6dJhmWpTcCdKSsEcOzriQNiKFZD6XtN+sJvZ65NH/QFXRNiy192+SSTKsbhOrFmw+kAKt5+MH1Erd6H54zJjpSgvJUmsJaQ=',
  AWS_ACCESS_KEY_ID    : 'AES:+yL/4p2Vh1oiVqkMirOOCw==:eoR5rhgQg3yKpKkDLLdliOlhyjpUts8yk9NfPqB8+eo=',
  AWS_SECRET_ACCESS_KEY: 'AES:HOzGi5HE4ykrhl9LSNMfJg==:zE66pCSyjrQZjr+mzrYcyFrmIliz/T2wdNm0r+4ttYdUQCA73pT5sPEZ8HuKgxfU'
]

def secureEnvironmentVariableForUpdateChannel = [
  GOCD_GPG_PASSPHRASE  : 'AES:7lAutKoRKMuSnh3Sbg9DeQ==:8fhND9w/8AWw6dJhmWpTcCdKSsEcOzriQNiKFZD6XtN+sJvZ65NH/QFXRNiy192+SSTKsbhOrFmw+kAKt5+MH1Erd6H54zJjpSgvJUmsJaQ=',
  AWS_ACCESS_KEY_ID    : 'AES:wBgBJL7+OUIbB6lL2oyzhw==:5v8jnATbtSknqet+hOKcWa1Hm8NvhA1wYjDpO91E3Sc=',
  AWS_SECRET_ACCESS_KEY: 'AES:1eT6nKFMzFIPPUme1Eg95A==:oxzxhe77cedWi3ZN/uPJKpRWAfEmzzUwrF2gZHzohnrTTyJ6jsQNPtlUFuDWashS'
]

def secureEnvironmentVariableForAddons = [
  AWS_ACCESS_KEY_ID    : 'AES:JjvuR5shoE8QbY3oLpr/Fw==:KG7+G3mKB//jLALlMgH6qNUubBkvdnBlNjjrxfdaJ5M=',
  AWS_SECRET_ACCESS_KEY: 'AES:eJrbqOkaHcpvvQCtGA/8wQ==:o3siVHlQIiS666ZOObVy1mH732+q7ZzmE4GQlm1bClnhXULIXXMz/NHwuwD9w+2C'
]

GoCD.script {
  environments {
    environment('internal') {
      pipelines = ['code-sign', 'upload-addons', 'PublishStableRelease']
    }
  }

  pipelines {
    pipeline('code-sign') { thisPipeline ->
      group = 'go-cd'
      environmentVariables = [
        'STABLE_DOWNLOAD_BUCKET'      : 'downloadgocdio-downloadgocdios3-192sau789jtkh',
        'EXPERIMENTAL_DOWNLOAD_BUCKET': 'downloadgocdio-experimentaldownloadss3-dakr8wkhi2bo/experimental',
        'UPDATE_CHECK_BUCKET'         : 'updategocdio-updategocdios3-1ujj23u8hpqdl'
      ]

      materials() {
        git('codesigning') {
          url = 'https://github.com/gocd/codesigning'
          destination = "codesigning"
        }
        svn('signing-keys') {
          url = "https://github.com/gocd-private/signing-keys/trunk"
          username = "gocd-ci-user"
          encryptedPassword = "AES:taOvOCaXsoVwzIi+xIGLdA==:GSfhZ6KKt6MXKp/wdYYoyBQKKzbTiyDa+35kDgkEIOF75s9lzerGInbqbUM7nUKc"
          destination = "signing-keys"
        }
        dependency('installers') {
          pipeline = 'installers'
          stage = 'docker'
        }
        dependency('regression') {
          pipeline = 'regression'
          stage = 'regression-linux'
        }
        dependency('regression-SPAs') {
          pipeline = 'regression-SPAs'
          stage = 'Firefox'
        }
        dependency('acceptance-linux') {
          pipeline = 'acceptance-linux'
          stage = 'RunAcceptanceSpecs-plugins'
        }
      }

      stages {
        stage('sign-and-upload') {
          cleanWorkingDir = true
          //credentials for gocd experimental builds
          secureEnvironmentVariables = secureEnvironmentVariableForGoCD

          jobs {
            job('rpm') {
              elasticProfileId = 'ecs-gocd-dev-build'
              tasks {
                addAll(cleanTasks())
                add(fetchArtifactTask('rpm'))
                add(fetchArtifactTask('meta'))
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace rpm:sign rpm:upload[${EXPERIMENTAL_DOWNLOAD_BUCKET}] yum:createrepo[${EXPERIMENTAL_DOWNLOAD_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
            job('deb') {
              elasticProfileId = 'ubuntu-16.04'
              tasks {
                addAll(cleanTasks())
                add(fetchArtifactTask('deb'))
                add(fetchArtifactTask('meta'))
                bash {
                  commandString = 'rake --trace deb:sign[${EXPERIMENTAL_DOWNLOAD_BUCKET}] deb:upload[${EXPERIMENTAL_DOWNLOAD_BUCKET}] apt:createrepo[${EXPERIMENTAL_DOWNLOAD_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
            job('zip') {
              elasticProfileId = 'ecs-gocd-dev-build'
              tasks {
                add(fetchArtifactTask('zip'))
                add(fetchArtifactTask('meta'))
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace zip:sign zip:upload[${EXPERIMENTAL_DOWNLOAD_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
            job('win') {
              elasticProfileId = 'window-dev-build'
              tasks {
                addAll(cleanTasks())
                add(fetchArtifactTask('win'))
                add(fetchArtifactTask('meta'))
                exec {
                  commandLine = ['rake', '--trace', 'win:sign', 'win:upload[%EXPERIMENTAL_DOWNLOAD_BUCKET%]']
                  workingDir = 'codesigning'
                }
              }
            }
            job('osx') {
              elasticProfileId = 'ecs-gocd-dev-build'
              tasks {
                add(fetchArtifactTask('osx'))
                add(fetchArtifactTask('meta'))
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace osx:sign_as_zip osx:upload[${EXPERIMENTAL_DOWNLOAD_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
//            job('osx') {
//              resources = ['mac', 'signer']
//              tasks {
//                addAll(cleanTasks())
//                add(fetchArtifactTask('osx'))
//                add(fetchArtifactTask('meta'))
//                bash {
//                  commandString = 'rake --trace osx:sign osx:upload[${EXPERIMENTAL_DOWNLOAD_BUCKET}]'
//                  workingDir = 'codesigning'
//                }
//              }
//            }
            job('upload-docker-image') {
              elasticProfileId = 'ecs-gocd-dev-build-dind'
              secureEnvironmentVariables = [
                DOCKERHUB_TOKEN   : 'AES:KlfOlLUAQMzP/2CZId73YQ==:s8fSRyucqVPMft5PpLPb9bEKc95iN3X5n1f6DK+i/8ZwIFNtw23L5m1y1Qs/RkNoYE34QNrrj1Rk7+4Bphz+yg==',
                DOCKERHUB_USERNAME: 'AES:C6gaOdyi+SDGkkvUHni6zw==:I2kqDgvf9GiwD7zzT1UWjQ==',
                DOCKERHUB_PASSWORD: 'AES:B2dXEmk4/HMqgLITXECK2A==:dfe+7OkQVOss4fFcXbACy1ZMqW8kVWvt8jyMmgzMDb8='
              ]
              tasks {
                fetchArtifact {
                  job = 'docker-server'
                  pipeline = 'installers'
                  runIf = 'passed'
                  source = 'docker-server'
                  stage = 'docker'
                  destination = "codesigning"
                }
                fetchArtifact {
                  job = 'docker-agent'
                  pipeline = 'installers'
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
                  commandString = "bundle exec rake docker:upload_experimental_docker_images"
                  workingDir = 'codesigning'
                }
                exec {
                  commandLine = ['npm', 'install']
                  workingDir = "codesigning"
                }
                bash {
                  commandString = 'node lib/update_dockerhub_full_description.js gocdexperimental'
                  runIf = 'passed'
                  workingDir = "codesigning"
                }
              }
            }
          }
        }

        stage('aggregate-jsons') {
          //credentials for gocd experimental builds
          secureEnvironmentVariables = secureEnvironmentVariableForGoCD
          jobs {
            job('aggregate-jsons') {
              elasticProfileId = 'ecs-gocd-dev-build'
              artifacts {
                build {
                  destination = 'out'
                  source = 'codesigning/out/latest.json'
                }
              }
              tasks {
                addAll(cleanTasks())
                add(fetchArtifactTask('meta'))
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace metadata:aggregate_jsons[${EXPERIMENTAL_DOWNLOAD_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
          }
        }

        stage('metadata') {
          //credentials for gocd update channel
          secureEnvironmentVariables = secureEnvironmentVariableForUpdateChannel

          jobs {
            job('generate') {
              elasticProfileId = 'ecs-gocd-dev-build'
              tasks {
                addAll(cleanTasks())
                add(fetchArtifactTask('meta'))
                add(getArtifact())
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace metadata:generate[${UPDATE_CHECK_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
          }
        }
      }
    }

    pipeline('upload-addons') {
      group = 'enterprise'

      environmentVariables = [
        'GO_ENTERPRISE_DIR'         : '../go-enterprise',
        'GO_SERVER_URL'             : 'https://build.gocd.org/go',
        'BUILD_MAP_USER'            : 'gocd-ci-user',
        'ADDONS_EXPERIMENTAL_BUCKET': 'mini-apps-extensionsexperimentaldownloadss3-hare386lt2d9/addons/experimental'
      ]

      secureEnvironmentVariables = [
        'BUILD_MAP_PASSWORD': 'AES:cpJ+mtdIjY3h+5HzVn+oJA==:roxy5Nz2hHz3COmBNHySpqcM4JVgDHPCm45CoSCwSUIuGgq+PQcm3ajV0ZlSmPoX',
        'CREDENTIALS'       : 'AES:4op4bMtqy6OohX5gw/KHPw==:yFDFPlzijIHPvT5B1/vHOyEWE4oMcwQ5Rc5zcCJ0QE0='
      ]

      materials() {
        git('codesigning') {
          url = 'https://github.com/gocd/codesigning'
          destination = "codesigning"
        }
        git('enterprise') {
          url = 'https://gocd:cz44DJpf2muap@git.gocd.io/git/gocd-private/enterprise'
          destination = "go-enterprise"
          shallowClone = "true"
          blacklist = ["**/*.*", "**/*"]
        }
        git('gocd_addons_compatibility') {
          url = 'https://gocd:cz44DJpf2muap@git.gocd.io/git/gocd-private/gocd_addons_compatibility'
          destination = "gocd_addons_compatibility"
          shallowClone = "true"
          blacklist = ["**/*.*", "**/*"]
        }
        dependency('go-packages') {
          pipeline = 'go-packages'
          stage = 'fetch_from_build_go_cd'
        }
        dependency('go-addon-build') {
          pipeline = 'go-addon-build'
          stage = 'build-addons'
        }
        dependency('installers') {
          pipeline = 'installers'
          stage = 'dist'
        }
        dependency('regression-pg-gauge') {
          pipeline = 'regression-pg-gauge'
          stage = 'regression-selenium'
        }
      }

      stages {
        stage('upload-addons') {
          //credentials for gocd addons experimental builds
          secureEnvironmentVariables = secureEnvironmentVariableForAddons

          jobs {
            job('upload') {
              elasticProfileId = 'ecs-gocd-dev-build'
              artifacts {
                build {
                  destination = 'addon_builds'
                  source = 'gocd_addons_compatibility/addon_builds.json'
                }
              }
              tasks {
                add(fetchArtifactTask('meta'))
                fetchArtifact {
                  pipeline = 'go-addon-build/go-packages'
                  stage = 'build-addons'
                  job = 'postgresql'
                  source = "postgresql-addon"
                  destination = "codesigning/src/pkg_for_upload"
                }
                fetchArtifact {
                  pipeline = 'go-addon-build/go-packages'
                  stage = 'build-addons'
                  job = 'business-continuity'
                  source = "business-continuity-addon"
                  destination = "codesigning/src/pkg_for_upload"
                }
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'export REPO_URL=https://${BUILD_MAP_USER}:${BUILD_MAP_PASSWORD}@github.com/gocd-private/gocd_addons_compatibility.git && bundle exec rake --trace determine_version_and_update_map[${GO_ENTERPRISE_DIR},${REPO_URL}]'
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'export CORRESPONDING_GOCD_VERSION=$(cat target/gocd_version.txt) && bundle exec rake --trace fetch_and_upload_addons[${ADDONS_EXPERIMENTAL_BUCKET},${CORRESPONDING_GOCD_VERSION}]'
                  workingDir = 'codesigning'
                }
              }
            }
          }
        }
      }
    }

    pipeline('PublishStableRelease') {
      group = 'go-cd'

      environmentVariables = [
        'STABLE_DOWNLOAD_BUCKET'      : 'downloadgocdio-downloadgocdios3-192sau789jtkh',
        'EXPERIMENTAL_DOWNLOAD_BUCKET': 'downloadgocdio-experimentaldownloadss3-dakr8wkhi2bo/experimental',
        'UPDATE_CHECK_BUCKET'         : 'updategocdio-updategocdios3-1ujj23u8hpqdl',
        'ADDONS_EXPERIMENTAL_BUCKET'  : 'mini-apps-extensionsexperimentaldownloadss3-hare386lt2d9/addons/experimental',
        'ADDONS_STABLE_BUCKET'        : 'mini-apps-extensionsdownloadss3-11t0jfofrxhyd/addons',
        'REALLY_REALLY_UPLOAD'        : ''
      ]

      materials() {
        git('codesigning') {
          url = 'https://github.com/gocd/codesigning'
          destination = "codesigning"
          blacklist = ["**/*.*", "**/*"]
        }
        git('enterprise') {
          url = 'https://gocd:cz44DJpf2muap@git.gocd.io/git/gocd-private/enterprise'
          destination = "go-enterprise"
          shallowClone = "true"
          blacklist = ["**/*.*", "**/*"]
        }
        svn('signing-keys') {
          url = "https://github.com/gocd-private/signing-keys/trunk"
          username = "gocd-ci-user"
          encryptedPassword = "AES:taOvOCaXsoVwzIi+xIGLdA==:GSfhZ6KKt6MXKp/wdYYoyBQKKzbTiyDa+35kDgkEIOF75s9lzerGInbqbUM7nUKc"
          destination = "signing-keys"
        }
        dependency('code-sign') {
          pipeline = 'code-sign'
          stage = 'metadata'
        }
        dependency('upload-addons') {
          pipeline = 'upload-addons'
          stage = 'upload-addons'
        }
        dependency('go-packages') {
          pipeline = 'go-packages'
          stage = 'fetch_from_build_go_cd'
        }
        dependency('go-addon-build') {
          pipeline = 'go-addon-build'
          stage = 'build-addons'
        }
        dependency('verify-usage-data-reporting') {
          pipeline = 'verify-usage-data-reporting'
          stage = 'for-build.gocd.org'
        }
      }

      stages {
        stage('promote-binaries') {
          approval {
            type = 'manual'
          }
          //credentials for gocd experimental/stable builds
          secureEnvironmentVariables = secureEnvironmentVariableForGoCD
          jobs {
            job('promote-binaries') {
              elasticProfileId = 'ecs-gocd-dev-build'
              tasks {
                bash {
                  commandString = '-c if [ "${REALLY_REALLY_UPLOAD}" != \'YES_I_REALLY_REALLY_WANT_TO_UPLOAD\' ]; then echo "REALLY_REALLY_UPLOAD environment variable should be overridden while triggering."; exit 1; fi'
                }
                fetchDirectory {
                  pipeline = 'installers/code-sign'
                  stage = 'dist'
                  job = 'dist'
                  source = "dist/meta"
                  destination = "codesigning/src"
                }
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace promote:copy_binaries_from_experimental_to_stable[${EXPERIMENTAL_DOWNLOAD_BUCKET},${STABLE_DOWNLOAD_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
          }
        }

        stage('create-repositories') {
          //credentials for gocd stable builds
          secureEnvironmentVariables = secureEnvironmentVariableForGoCD
          jobs {
            job('apt') {
              elasticProfileId = 'ubuntu-16.04'
              tasks {
                fetchDirectory {
                  pipeline = 'installers/code-sign'
                  stage = 'dist'
                  job = 'dist'
                  source = "dist/meta"
                  destination = "codesigning/src"
                }
                bash {
                  commandString = 'rake --trace apt:createrepo[${STABLE_DOWNLOAD_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }

            job('yum') {
              elasticProfileId = 'ecs-gocd-dev-build'
              tasks {
                fetchDirectory {
                  pipeline = 'installers/code-sign'
                  stage = 'dist'
                  job = 'dist'
                  source = "dist/meta"
                  destination = "codesigning/src"
                }
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace yum:createrepo[${STABLE_DOWNLOAD_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
          }
        }

        stage('promote-addons') {
          //credentials for gocd addons experimental/stable builds
          secureEnvironmentVariables = secureEnvironmentVariableForAddons
          jobs {
            job('promote-addons') {
              elasticProfileId = 'ecs-gocd-dev-build'
              tasks {
                fetchDirectory {
                  pipeline = 'installers/code-sign'
                  stage = 'dist'
                  job = 'dist'
                  source = "dist/meta"
                  destination = "codesigning/src"
                }
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace promote:copy_addon_from_experimental_to_stable[${ADDONS_EXPERIMENTAL_BUCKET},${ADDONS_STABLE_BUCKET}]'
                  workingDir = 'codesigning'
                }

                bash {
                  commandString = 'bundle exec rake --trace promote:promote_addons_metadata[${ADDONS_EXPERIMENTAL_BUCKET},${ADDONS_STABLE_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
          }
        }

        stage('publish-stable-releases-json') {
          //credentials for gocd stable builds
          secureEnvironmentVariables = secureEnvironmentVariableForGoCD
          jobs {
            job('publish') {
              elasticProfileId = 'ecs-gocd-dev-build'
              tasks {
                fetchDirectory {
                  pipeline = 'installers/code-sign'
                  stage = 'dist'
                  job = 'dist'
                  source = "dist/meta"
                  destination = "codesigning/src"
                }
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace metadata:releases_json[${STABLE_DOWNLOAD_BUCKET}]'
                  workingDir = 'codesigning'
                }
              }
            }
          }
        }

        stage('publish-latest-json') {
          //credentials for gocd update channel
          secureEnvironmentVariables = secureEnvironmentVariableForUpdateChannel
          jobs {
            job('publish') {
              elasticProfileId = 'ecs-gocd-dev-build'
              tasks {
                fetchDirectory {
                  pipeline = 'installers/code-sign'
                  stage = 'dist'
                  job = 'dist'
                  source = "dist/meta"
                  destination = "codesigning/src"
                }
                bash {
                  commandString = "bundle install --jobs 4 --path .bundle --clean"
                  workingDir = 'codesigning'
                }
                bash {
                  commandString = 'bundle exec rake --trace promote:update_check_json[${UPDATE_CHECK_BUCKET}]'
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
