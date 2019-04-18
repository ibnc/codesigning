require 'json'
require 'open3'
require 'open-uri'
require 'pathname'

def find_build_number_for gocd_git_revision, releases_json_download_url
  open(releases_json_download_url) do |f|
    releases        = JSON.parse(f.read)
    matching_builds = releases.reject {|x| x["git_sha"] != gocd_git_revision}
    if (!matching_builds.empty?)
      desired_build = matching_builds.first
      desired_build["go_full_version"]
    end
  end
end

def find_full_version gocd_git_revision
  experimental_releases_json_download_url = "https://download.gocd.org/experimental/releases.json"
  releases_json_download_url              = "https://download.gocd.org/releases.json"
  find_build_number_for(gocd_git_revision, experimental_releases_json_download_url) || find_build_number_for(gocd_git_revision, releases_json_download_url)
end

def find_full_version_from_version_json gocd_git_revision
  version_information = JSON.parse(File.read("src/meta/version.json"))
  if version_information["git_sha"] == gocd_git_revision
    version_information["go_full_version"]
  end
end

desc "task to determine the latest gocd build from enterprise directory"
task :determine_corresponding_gocd_build, [:go_enterprise_dir] do |t, args|
  go_enterprise_dir = args[:go_enterprise_dir]
  raise "GO_ENTERPRISE_DIR not set, it is required to determine the corresponding gocd revision" unless go_enterprise_dir
  gocd_git_revision = ""

  cd go_enterprise_dir do
    stdin, stdout, stderr = Open3.popen3("git submodule status core | awk '{print $1}'")
    error                 = stderr.read
    gocd_git_revision     = stdout.read.strip if (error.empty?)
    raise "Could not get gocd git sha corresponding to enterprise #{go_enterprise_dir}. command failed: #{error}" unless (error.empty?)
    puts "Core is at revision #{gocd_git_revision}"
  end

  target_dir = Pathname.new('target')
  rm_rf target_dir
  mkdir_p target_dir

  if (full_version = find_full_version_from_version_json(gocd_git_revision) || find_full_version(gocd_git_revision))
    File.open("target/gocd_version.txt", 'w') {|file| file.write(full_version)}
  else
    raise "Could not find an entry for #{gocd_git_revision} in releases.json, this usually means fetch_from_build_go_cd for revision #{gocd_git_revision} has not completed."
  end
end

desc "update gocd and addons compatibility map"
task :update_gocd_compatibility_map, [:repo_url] do |t, args|
  repo_url     = args[:repo_url] || 'origin'
  gocd_version = File.read('target/gocd_version.txt')
  addons       = []
  Dir.chdir("src/pkg_for_upload") do
    addons = Dir.glob("*/go-*.jar").collect {|f| File.basename(f)}
  end
  addon_builds_json_file = File.join("..", "gocd_addons_compatibility", "addon_builds.json")
  existing_data          = JSON.parse(File.read(addon_builds_json_file))
  addons_hash            = Hash.new
  addons.each do |addon|
    key                   = addon.match(/go-(.*)-\d+\..*\.jar$/).captures.first
    addons_hash["#{key}"] = addon
  end

  info_about_this_build = {
      "gocd_version" => "#{gocd_version}",
      "addons"       => addons_hash
  }

  original_object = existing_data.find {|info| info === info_about_this_build}

  if original_object == nil
    to_be_written = existing_data << info_about_this_build

    File.open(addon_builds_json_file, "w") do |f|
      f.puts(JSON.pretty_generate(to_be_written))
    end

    cd "../gocd_addons_compatibility" do
      sh ("git add .; git commit -m 'updated addons build info for #{addons.join(',')} against Gocd #{gocd_version}'; git push #{repo_url} master")
    end
  else
    puts "Information about this build already present in the map!"
  end
end

task :determine_version_and_update_map, [:go_enterprise_dir, :repo_url] => [:determine_corresponding_gocd_build, :update_gocd_compatibility_map]