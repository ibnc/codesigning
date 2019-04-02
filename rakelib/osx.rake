require 'securerandom'

# make sure deps are installed using
namespace :osx do
  signing_dir = "out/osx"
  osx_source_dir = 'src/osx'
  meta_source_dir = 'src/meta'

  # assumes the following:
  # - File `../signing-keys/codesign.keychain.password.gpg` containing the encrypted keychain passphrase
  # - environment variable `GOCD_GPG_PASSPHRASE` containing the passphrase to decrypt the said key
  desc "setup code signing keys"
  task :setup do
    cd '../signing-keys' do
      open('gpg-passphrase', 'w') {|f| f.write(ENV['GOCD_GPG_PASSPHRASE'])}
      sh("gpg --batch --yes --passphrase-file gpg-passphrase --output codesign.keychain.password codesign.keychain.password.gpg")
    end
  end

  desc "sign osx binaries"
  task :sign => ['setup'] do
    if Dir["#{osx_source_dir}/*.zip"].empty?
      raise "Unable to find any binaries in #{osx_source_dir}"
    end

    rm_rf signing_dir
    mkdir_p signing_dir
    Dir["#{osx_source_dir}/*.zip"].each do |f|
      tmp_dir = SecureRandom.hex
      rm_rf 'tmp/osx'
      mkdir_p 'tmp/osx'
      sh("unzip -q -o '#{f}' -d tmp/osx/#{tmp_dir}")
      home_dir = File.expand_path('~')
      sh(%Q{security unlock-keychain -p \"$(cat #{Dir.pwd}/../signing-keys/codesign.keychain.password)\" '#{home_dir}/Library/Keychains/codesign.keychain-db' && codesign --force --verify --verbose --sign "Developer ID Application: ThoughtWorks (LL62P32G5C)" tmp/osx/#{tmp_dir}/*}) do |ok, res|
        puts 'Locking keychain again'
        sh("security lock-keychain '#{home_dir}/Library/Keychains/codesign.keychain-db'")
        fail 'There was an error performing code OSX signing' unless ok
      end
      Dir["tmp/osx/#{tmp_dir}/**/*"].each do |f|
        File.utime(0, 0, f)
      end
      Dir["tmp/osx/#{tmp_dir}/**/*.*"].each do |f|
        File.utime(0, 0, f)
      end
      cd("tmp/osx/#{tmp_dir}") do
        sh("zip -q -r ../../../#{signing_dir}/#{File.basename(f)} .")
      end
    end

    rm_rf 'tmp'

    generate_metadata_for_single_dir signing_dir, '*.zip', :osx
  end

  desc "upload the osx binaries, after signing the binaries"
  task :upload, [:bucket_url] => :sign do |t, args|
    bucket_url = args[:bucket_url]

    raise "Please specify bucket url" unless bucket_url

    go_full_version = JSON.parse(File.read("#{meta_source_dir}/version.json"))['go_full_version']

    sh("aws s3 sync #{'--no-progress' unless $stdin.tty?} --acl public-read --cache-control 'max-age=31536000' #{signing_dir} s3://#{bucket_url}/binaries/#{go_full_version}/osx")
  end
end
