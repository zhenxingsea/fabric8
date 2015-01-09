/*
 * Copyright 2005-2014 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.arquillian.kubernetes;

import io.fabric8.arquillian.kubernetes.await.CompositeCondition;
import io.fabric8.arquillian.kubernetes.await.SessionPodsAreReady;
import io.fabric8.arquillian.kubernetes.await.SessionServicesAreReady;
import io.fabric8.arquillian.kubernetes.await.WaitStrategy;
import io.fabric8.arquillian.kubernetes.event.Start;
import io.fabric8.arquillian.kubernetes.event.Stop;
import io.fabric8.arquillian.kubernetes.log.Logger;
import io.fabric8.arquillian.utils.Util;
import io.fabric8.kubernetes.api.Controller;
import io.fabric8.kubernetes.api.KubernetesClient;
import io.fabric8.kubernetes.api.Config;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodTemplate;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.utils.MultiException;
import org.jboss.arquillian.core.api.annotation.Observes;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import static io.fabric8.arquillian.utils.Util.cleanupSession;
import static io.fabric8.arquillian.utils.Util.displaySessionStatus;
import static io.fabric8.arquillian.utils.Util.readAsString;
import static io.fabric8.kubernetes.api.KubernetesHelper.getEntities;
import static io.fabric8.kubernetes.api.KubernetesHelper.loadJson;
import static io.fabric8.arquillian.kubernetes.Constants.ARQ_KEY;

public class SessionListener {
    private Thread shutdownHook;

    public void start(final @Observes Start event, final KubernetesClient client, Controller controller, Configuration configuration) throws Exception {
        final Logger log = event.getSession().getLogger();
        try {
            URL configUrl = configuration.getConfigUrl();
            List<String> dependencies = !configuration.getDependencies().isEmpty() ? configuration.getDependencies() : Util.getMavenDependencies(event.getSession());
            List<Config> kubeConfigs = new LinkedList<>();

            for (String dependency : dependencies) {
                log.info("Found dependency: " + dependency);
                Object kubeCfg = loadJson(readAsString(new URL(dependency)));
                if (kubeCfg instanceof Config) {
                    kubeConfigs.add((Config) kubeCfg);
                }
            }

            if (configUrl != null) {
                log.status("Applying kubernetes configuration from: " + configuration.getConfigUrl());
                kubeConfigs.add((Config) loadJson(readAsString(configuration.getConfigUrl())));
            }
            applyConfiguration(client, controller, configuration, event.getSession(), kubeConfigs);
            displaySessionStatus(client, event.getSession());
        } catch (Exception e) {
            try {
                cleanupSession(client, event.getSession());
            } catch (MultiException me) {
                throw e;
            }
            throw new RuntimeException(e);
        }

        // TODO should we allow this to be disabled via a system property or something?
        Thread shutdownHook = new Thread("Cleanup session") {
            @Override
            public void run() {
                log.warn("shutdown hook cleaning up the integration test!");
                try {
                    cleanupSession(client, event.getSession());
                } catch (MultiException e) {
                    log.warn(e.getMessage());
                }
            }
        };
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }


    public void stop(@Observes Stop event, KubernetesClient client) throws Exception {
        cleanupSession(client, event.getSession());
        if (shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
    }


    private boolean applyConfiguration(KubernetesClient client, Controller controller, Configuration configuration, Session session, List<Config> kubeConfigs) throws Exception {
        Logger log = session.getLogger();
        Set<Callable<Boolean>> conditions = new HashSet<>();
        Callable<Boolean> sessionPodsReady = new SessionPodsAreReady(client, session);
        Callable<Boolean> servicesReady = new SessionServicesAreReady(client, session, configuration.isWaitForConenction());

        List<Object> entities = new ArrayList<>();
        for (Config c : kubeConfigs) {
            entities.addAll(getEntities(c));
        }

        //Ensure services are processed first.
        Collections.sort(entities, new Comparator<Object>() {
            @Override
            public int compare(Object left, Object right) {
                if (left instanceof Service) {
                    return -1;
                } else if (right instanceof Service) {
                    return 1;
                } else {
                    return 0;
                }
            }
        });

        for (Object entity : entities) {
            if (entity instanceof Pod) {
                Pod pod = (Pod) entity;
                if (pod.getLabels() == null) {
                    pod.setLabels(new HashMap<String, String>());
                }
                pod.getLabels().put(ARQ_KEY, session.getId());
                log.status("Applying pod:" + pod.getId());
                controller.applyPod(pod, session.getId());
                conditions.add(sessionPodsReady);
            } else if (entity instanceof Service) {
                Service service = (Service) entity;
                if (service.getLabels() == null) {
                    service.setLabels(new HashMap<String, String>());
                }
                service.getLabels().put(ARQ_KEY, session.getId());
                log.status("Applying service:" + service.getId());
                controller.applyService(service, session.getId());
                conditions.add(servicesReady);
            } else if (entity instanceof ReplicationController) {
                ReplicationController replicationController = (ReplicationController) entity;
                PodTemplate podTemplate = replicationController.getDesiredState().getPodTemplate();
                if (podTemplate.getLabels() == null) {
                    podTemplate.setLabels(new HashMap<String, String>());
                }
                replicationController.getDesiredState().getPodTemplate().getLabels().put(ARQ_KEY, session.getId());
                if (replicationController.getLabels() == null) {
                    replicationController.setLabels(new HashMap<String, String>());
                }
                replicationController.getLabels().put(ARQ_KEY, session.getId());
                log.status("Applying replication controller:" + replicationController.getId());
                controller.applyReplicationController(replicationController, session.getId());
                conditions.add(sessionPodsReady);
            }
        }


        //Wait until conditions are meet.
        if (!conditions.isEmpty()) {
            Callable<Boolean> compositeCondition = new CompositeCondition(conditions);
            WaitStrategy waitStrategy = new WaitStrategy(compositeCondition, configuration.getTimeout(), configuration.getPollInterval());
            if (!waitStrategy.await()) {
                log.error("Timed out waiting for pods/services!");
                return false;
            } else {
                log.status("All pods/services are currently 'running'!");
            }
        } else {
            log.warn("No pods/services/replication controllers defined in the configuration!");
        }

        return true;
    }

}
