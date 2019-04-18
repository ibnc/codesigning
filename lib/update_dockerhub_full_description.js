let dockerHubAPI = require('docker-hub-api');
const request = require('request');

if (process.argv.length !== 3) {
    throw new Error(`Usage: node lib/update_dockerhub_full_description.js [dockerHubName]`);
}

const org = process.argv[2];

function getRepoName(image_name) {
    if (!image_name)
        return;
    if (image_name.indexOf("agent") > -1) {
        if (image_name === 'gocd-agent-docker-dind') {
            return image_name;
        }
        return `docker-${image_name}`;
    } else if (image_name.indexOf("server") > -1) {
        return image_name;
    }
}

function getDockerHubName(image_name) {
    if (!image_name)
        return;
    if (image_name.indexOf("agent") > -1) {
        return image_name;
    } else if (image_name.indexOf("server") > -1) {
        return image_name.replace("docker-", "");
    }
}

let allImagesViaManifest = [];

["server", "agent"].forEach(type => {
    let manifest_files = require(`../docker-${type}/manifest.json`);
    if (manifest_files.length === 0) {
        console.log("Found no record of any docker images in the manifest.json file");
        return;
    }
    manifest_files.forEach(metadata => {
        allImagesViaManifest.push(metadata.imageName);
    })
});

console.log('Updating the Dockerhub description to the contents of README in each repository.');

dockerHubAPI.login(process.env.DOCKERHUB_USERNAME, process.env.DOCKERHUB_PASSWORD)
    .then((info) => {
        dockerHubAPI.loggedInUser().then((_data) => {
            allImagesViaManifest.forEach((imageName) => {
                const hubName = getDockerHubName(imageName);
                const repoName = getRepoName(imageName);
                dockerHubAPI.repository(org, hubName)
                    .then((data) => {
                            let url = `https://raw.githubusercontent.com/gocd/${repoName}/master/README.md`;
                            request(url, (error, response, body) => {
                                let json = {
                                    full: body
                                };
                                dockerHubAPI.setRepositoryDescription(org, hubName, json)
                                    .then(data => {
                                        console.log("Success: " + data.name);
                                    }, (reason) => {
                                        console.log("Rejected for image: " + imageName + "\n" + reason)
                                    });
                            });
                        },
                        (error) => {
                            console.log(`Rejected: ${imageName}\n Error: ${error}`);
                        });
            });
        });
    });