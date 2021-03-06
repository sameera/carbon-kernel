/*
 *  Copyright (c) 2005-2013, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.deployment;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.base.ServerConfiguration;
import org.wso2.carbon.deployment.spi.Deployer;
import org.wso2.carbon.deployment.exception.CarbonDeploymentException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Deployment Engine of Carbon which manages the deployment/undeployment of artifacts in carbon.
 */

public class DeploymentEngine {

    private static final Log log = LogFactory.getLog(DeploymentEngine.class);

    /**
     * The repository scanner associated with this engine
     */
    private RepositoryScanner repositoryScanner;

    /**
     * Repository directory for this deployment engine
     */
    private File repositoryDirectory = null;

    /**
     * The map which holds the set of registered deployers with this engine
     */
    private Map<ArtifactType, Deployer> deployerMap = new ConcurrentHashMap<ArtifactType, Deployer>();

    /**
     * A map to hold all currently deployed artifacts
     */
    private Map<ArtifactType, ConcurrentHashMap<Object, Artifact>> deployedArtifacts =
            new ConcurrentHashMap<ArtifactType, ConcurrentHashMap<Object, Artifact>>();


    public DeploymentEngine(String repositoryDir) throws CarbonDeploymentException {
        init(repositoryDir);
    }

    /**
     * Configure and prepare the repository associated with this engine.
     *
     * @throws CarbonDeploymentException on error
     */
    private void init(String repositoryDir) throws CarbonDeploymentException {
        repositoryDirectory = new File(repositoryDir);
        if (!repositoryDirectory.exists()) {
            throw new CarbonDeploymentException("Cannot find repository : " + repositoryDirectory);
        }
        repositoryScanner = new RepositoryScanner(this);
    }

    /**
     * Starts the Deployment engine to perform Hot deployment and so on.
     * This will start the repository scanner and scheduler task and load artifacts to
     * the deployment engine
     */
    public void start() {
        //Deploy initial set of artifacts
        repositoryScanner.scan();
        // We need to check and scan the task based on the deployment engine mode of operation
        // Currently there can be two modes
        // 1. Scheduled Mode - where the task runs periodically and trigger deployment
        // 2. Triggered Mode - the deployment has to be triggered externally,
        //    eg : in a worker node we don't need the task to run, but rather when we receive a
        //         cluster msg,  the deployment has to be triggered manually
        //TODO : Need to check whether we need to scan the task when triggered mode is enabled
        startScheduler();
    }


    private void startScheduler() {
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
        SchedulerTask schedulerTask = new SchedulerTask(repositoryScanner);
        String deploymentInterval = ServerConfiguration.getInstance().
                getFirstProperty("Deployment.DeploymentUpdateInterval");
        int interval = 15;
        if (deploymentInterval != null) {
            interval = Integer.parseInt(deploymentInterval);
        }
        scheduledExecutorService.schedule(schedulerTask, interval, TimeUnit.SECONDS);
    }

    /**
     * Add and initialize a new Deployer to deployment engine.
     *
     * @param deployer the deployer instance to register
     */
    public void registerDeployer(Deployer deployer) throws CarbonDeploymentException {

        if (deployer == null) {
            log.error("Failed to add Deployer : Deployer Class Name is null");
            return;
        }

        if (deployer.getLocation() == null) {
            log.error("Failed to add Deployer " + deployer.getClass().getName() +
                      ": missing 'directory' attribute in deployer instance");
            return;
        }
        ArtifactType type = deployer.getArtifactType();
        Deployer existingDeployer = deployerMap.get(type);
        if (existingDeployer == null) {
            deployerMap.put(type, deployer);
            deployer.init();
        }
    }

    /**
     * Removes a deployer from the deployment engine configuration
     *
     * @param deployer the deployer instance to un-register
     */
    public void unRegisterDeployer(Deployer deployer) {
        ArtifactType type = deployer.getArtifactType();
        if (deployer.getArtifactType() == null) {
            log.error("Failed to remove Deployer : missing 'artifactType' attribute");
            return;
        }

        Deployer existingDeployer = deployerMap.get(type);
        if (existingDeployer != null) {
            deployerMap.remove(type);
        }
    }

    /**
     * Retrieve the deployer from the current deployers map, by giving the associated directory
     *
     * @param type the artifact type that the deployer is associated with
     * @return Deployer instance
     */
    public Deployer getDeployer(ArtifactType type) {
        Deployer existingDeployer = deployerMap.get(type);
        return (existingDeployer != null) ? existingDeployer : null;
    }

    /**
     * Return the registered deployers as a Map
     *
     * @return registered deployers
     */
    public Map<ArtifactType, Deployer> getDeployers() {
        return this.deployerMap;
    }


    /**
     * Returns the repository directory that the deployment engine is registered with
     *      Eg: CARBON_HOME/repository/deployment/server
     * @return repository directory
     */
    public File getRepositoryDirectory() {
        return repositoryDirectory;
    }

    /**
     * This will return an artifact for given artifactkey and directory from
     * currently deployed artifacts.
     *
     * @param type type of the artifact
     * @param artifactKey key of an artifact is used to uniquely identify it self within a runtime
     * @return the deployed artifact for given key and type
     */

    public Artifact getDeployedArtifact(ArtifactType type, Object artifactKey) {
        Artifact artifact = null;
        if (deployedArtifacts.get(type) != null) {
            artifact = deployedArtifacts.get(type).get(artifactKey);
        }
        return artifact;
    }

    public Map<ArtifactType, ConcurrentHashMap<Object, Artifact>> getDeployedArtifacts() {
        return deployedArtifacts;
    }

    /**
     * Deploy the artifacts found in the artifacts to be deployed list
     */
    public void deployArtifacts(List<Artifact> artifactsToDeploy) {

        for (Object artifact : artifactsToDeploy) {
            Artifact artifactToDeploy = (Artifact) artifact;
            try {
                Deployer deployer = getDeployer(artifactToDeploy.getType());
                Object artifactKey = deployer.deploy(artifactToDeploy);
                artifactToDeploy.setKey(artifactKey);
                addToDeployedArtifacts(artifactToDeploy);
            } catch (CarbonDeploymentException e) {
                //TODO : Handle faulty artifact deployment
                log.error(e);
            }
        }
    }

    /**
     * Updates the artifacts found in the artifacts to be updated list
     */
    public void updateArtifacts(List<Artifact> artifactsToUpdate) {
        for (Object artifact : artifactsToUpdate) {
            Artifact artifactToUpdate = (Artifact) artifact;
            try {
                Deployer deployer = getDeployer(artifactToUpdate.getType());
                Object artifactKey = deployer.update(artifactToUpdate);
                artifactToUpdate.setKey(artifactKey);
                addToDeployedArtifacts(artifactToUpdate);
            } catch (CarbonDeploymentException e) {
                //TODO : Handle faulty artifact deployment
                log.error(e);
            }
        }
    }

    private void addToDeployedArtifacts(Artifact artifact) {
        ConcurrentHashMap<Object, Artifact> artifactMap = deployedArtifacts.
                get(artifact.getType());
        if (artifactMap == null) {
            artifactMap = new ConcurrentHashMap<Object, Artifact>();
        }
        artifactMap.put(artifact.getKey(), artifact);
        deployedArtifacts.put(artifact.getType(), artifactMap);
    }

    /**
     * Undeploy the artifacts found in the artifact to be undeployed list
     */
    public void undeployArtifacts(List<Artifact> artifactsToUndeploy) {
        if (artifactsToUndeploy.size() > 0) {
            for (Object artifact : artifactsToUndeploy) {
                Artifact artifactToUnDeploy = (Artifact) artifact;
                try {
                    Deployer deployer = getDeployer(artifactToUnDeploy.getType());
                    deployer.undeploy(artifactToUnDeploy.getKey());
                    removeFromDeployedArtifacts(artifactToUnDeploy);
                } catch (CarbonDeploymentException e) {
                    log.error(e);
                }
            }
        }
    }

    private void removeFromDeployedArtifacts(Artifact artifact) {
        Map<Object, Artifact> artifactMap = deployedArtifacts.get(artifact.getType());
        if (artifactMap != null && artifactMap.containsKey(artifact.getKey())) {
            artifactMap.remove(artifact.getKey());
            if (artifactMap.isEmpty()) {
                deployedArtifacts.remove(artifact.getType());
            }
        }
    }
}
