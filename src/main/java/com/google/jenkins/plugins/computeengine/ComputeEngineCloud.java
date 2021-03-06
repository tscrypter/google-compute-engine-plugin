/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jenkins.plugins.computeengine;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.api.services.compute.model.Instance;
import com.google.common.collect.ImmutableMap;
import com.google.jenkins.plugins.computeengine.client.ClientFactory;
import com.google.jenkins.plugins.computeengine.client.ComputeClient;
import com.google.jenkins.plugins.credentials.oauth.GoogleOAuth2Credentials;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;

public class ComputeEngineCloud extends AbstractCloudImpl {
  public static final String CLOUD_PREFIX = "gce-";
  public static final String CONFIG_LABEL_KEY = "jenkins_config_name";
  public static final String CLOUD_ID_LABEL_KEY = "jenkins_cloud_id";

  private static final Logger LOGGER = Logger.getLogger(ComputeEngineCloud.class.getName());
  private static final SimpleFormatter sf = new SimpleFormatter();

  public final String projectId;
  public final String credentialsId;

  private String instanceId;
  private List<InstanceConfiguration> configurations;

  private transient volatile ComputeClient client;

  @DataBoundConstructor
  public ComputeEngineCloud(
      String cloudName, String projectId, String credentialsId, String instanceCapStr) {
    super(createCloudId(cloudName), instanceCapStr);

    instanceId = UUID.randomUUID().toString();
    this.credentialsId = credentialsId;
    this.projectId = projectId;
    setConfigurations(null);
  }

  @Deprecated
  public ComputeEngineCloud(
      String cloudName,
      String projectId,
      String credentialsId,
      String instanceCapStr,
      List<InstanceConfiguration> configurations) {
    this(cloudName, projectId, credentialsId, instanceCapStr);

    setConfigurations(configurations);
  }

  private static String createCloudId(String name) {
    return CLOUD_PREFIX + name.trim();
  }

  public static void log(Logger logger, Level level, TaskListener listener, String message) {
    log(logger, level, listener, message, null);
  }

  public static void log(
      Logger logger, Level level, TaskListener listener, String message, Throwable exception) {
    logger.log(level, message, exception);
    if (listener != null) {
      if (exception != null) message += " Exception: " + exception;
      LogRecord lr = new LogRecord(level, message);
      PrintStream printStream = listener.getLogger();
      printStream.print(sf.format(lr));
    }
  }

  public String getCloudName() {
    return name.substring(CLOUD_PREFIX.length());
  }

  @Override
  public String getDisplayName() {
    return getCloudName();
  }

  protected Object readResolve() {
    if (configurations != null) {
      for (InstanceConfiguration configuration : configurations) {
        configuration.cloud = this;
        configuration.readResolve();
        // Apply a label that associates an instance configuration with
        // this cloud provider
        configuration.appendLabel(CLOUD_ID_LABEL_KEY, getInstanceId());

        // Apply a label that identifies the name of this instance configuration
        configuration.appendLabel(CONFIG_LABEL_KEY, configuration.getNamePrefix());
      }
    }
    return this;
  }

  /**
   * Sets unique ID of that cloud instance.
   *
   * <p>This ID allows us to find machines from our cloud in GCP. <b>This value should not change
   * between config reload, or nodes may be lost in GCP side</b>
   */
  @DataBoundSetter
  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  /**
   * Returns unique ID of that cloud instance.
   *
   * <p>This ID allows us to find machines from our cloud in GCP.
   *
   * @return instance unique ID
   */
  public String getInstanceId() {
    return instanceId;
  }

  private ComputeClient createClient() {
    try {
      ClientFactory clientFactory =
          new ClientFactory(Jenkins.get(), new ArrayList<>(), credentialsId);
      return clientFactory.compute();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Exception when creating GCE client", e);
      // TODO: https://github.com/jenkinsci/google-compute-engine-plugin/issues/62
      return null;
    }
  }

  /**
   * Returns GCP client for that cloud.
   *
   * @return GCP client object.
   */
  public ComputeClient getClient() {
    if (client == null) {
      synchronized (this) {
        if (client == null) {
          client = createClient();
        }
      }
    }
    return client;
  }

  /**
   * Returns GCP projectId for that cloud.
   *
   * @return projectId
   */
  public String getProjectId() {
    return projectId;
  }

  /**
   * Set configurations for this cloud.
   *
   * @param configurations configurations to be used
   */
  @DataBoundSetter
  public void setConfigurations(List<InstanceConfiguration> configurations) {
    this.configurations = configurations;
    readResolve();
  }

  /**
   * Returns configurations for this cloud.
   *
   * @return configurations
   */
  public List<InstanceConfiguration> getConfigurations() {
    return configurations;
  }

  /**
   * Adds one configuration.
   *
   * @param configuration configuration to add
   */
  @Deprecated
  public void addConfiguration(InstanceConfiguration configuration) {
    if (configurations == null) {
      this.configurations = new ArrayList<>();
    }
    configurations.add(configuration);
    readResolve();
  }

  @Override
  public Collection<PlannedNode> provision(Label label, int excessWorkload) {
    List<PlannedNode> r = new ArrayList<>();
    try {
      // TODO: retrieve and iterate a list of InstanceConfiguration that match label
      final InstanceConfiguration config = getInstanceConfig(label);
      LOGGER.log(
          Level.INFO,
          "Provisioning node from config "
              + config
              + " for excess workload of "
              + excessWorkload
              + " units of label '"
              + label
              + "'");
      while (excessWorkload > 0) {
        Integer availableCapacity = availableNodeCapacity();
        if (availableCapacity <= 0) {
          LOGGER.warning(
              String.format(
                  "Could not provision new nodes to meet excess workload demand (%d). Cloud provider %s has reached its configured capacity of %d",
                  excessWorkload, getCloudName(), getInstanceCap()));
          break;
        }

        final ComputeEngineInstance node = config.provision(StreamTaskListener.fromStdout());
        Jenkins.get().addNode(node);
        r.add(
            new PlannedNode(
                node.getNodeName(),
                Computer.threadPoolForRemoting.submit(
                    () -> {
                      long startTime = System.currentTimeMillis();
                      LOGGER.log(
                          Level.INFO,
                          String.format(
                              "Waiting %dms for node %s to connect",
                              config.getLaunchTimeoutMillis(), node.getNodeName()));
                      try {
                        Computer c = node.toComputer();
                        if (c != null) {
                          c.connect(false)
                              .get(config.getLaunchTimeoutMillis(), TimeUnit.MILLISECONDS);
                          LOGGER.log(
                              Level.INFO,
                              String.format(
                                  "%dms elapsed waiting for node %s to connect",
                                  System.currentTimeMillis() - startTime, node.getNodeName()));
                        } else {
                          LOGGER.log(
                              Level.WARNING,
                              String.format("No computer for node %s found", node.getNodeName()));
                        }
                      } catch (TimeoutException e) {
                        LOGGER.log(
                            Level.WARNING,
                            String.format(
                                "Timeout waiting for node %s to connect", node.getNodeName()),
                            e);
                      }
                      return null;
                    }),
                node.getNumExecutors()));
        excessWorkload -= node.getNumExecutors();
      }
    } catch (IOException ioe) {
      LOGGER.log(Level.WARNING, "Error provisioning node", ioe);
    } catch (NoConfigurationException nce) {
      LOGGER.log(
          Level.WARNING,
          String.format(
              "An instance configuration could not be found to provision a node for label %s",
              label.getName()),
          nce.getMessage());
    }
    return r;
  }

  /**
   * Determine the number of nodes that may be provisioned for this Cloud.
   *
   * @return
   * @throws IOException
   */
  private synchronized Integer availableNodeCapacity() throws IOException {
    try {
      // We only care about instances that have a label indicating they
      // belong to this cloud
      Map<String, String> filterLabel = ImmutableMap.of(CLOUD_ID_LABEL_KEY, getInstanceId());
      List<Instance> instances = getClient().getInstancesWithLabel(projectId, filterLabel);

      // Don't count instances that are not running (or starting up)
      Iterator it = instances.iterator();
      while (it.hasNext()) {
        Instance o = (Instance) it.next();
        if (!(o.getStatus().equals("PROVISIONING")
            || o.getStatus().equals("STAGING")
            || o.getStatus().equals("RUNNING"))) {
          it.remove();
        }
      }
      Integer capacity = getInstanceCap() - instances.size();
      LOGGER.info(
          String.format("Found capacity for %d nodes in cloud %s", capacity, getCloudName()));
      return (getInstanceCap() - instances.size());
    } catch (IOException ioe) {
      LOGGER.warning(
          String.format(
              "An error occurred counting the number of existing instances in cloud %s: %s",
              getCloudName(), ioe.getMessage()));
      throw ioe;
    }
  }

  @Override
  public boolean canProvision(Label label) {
    try {
      getInstanceConfig(label);
      return true;
    } catch (NoConfigurationException nce) {
      return false;
    }
  }

  /** Gets {@link InstanceConfiguration} that has the matching {@link Label}. */
  public InstanceConfiguration getInstanceConfig(Label label) throws NoConfigurationException {
    if (configurations == null) {
      throw new NoConfigurationException(
          String.format(
              "Cloud %s does not have any defined instance configurations.", this.getCloudName()));
    }

    for (InstanceConfiguration configuration : configurations) {
      if (configuration.getMode() == Node.Mode.NORMAL) {
        if (label == null || label.matches(configuration.getLabelSet())) {
          return configuration;
        }
      } else if (configuration.getMode() == Node.Mode.EXCLUSIVE) {
        if (label != null && label.matches(configuration.getLabelSet())) {
          return configuration;
        }
      }
    }
    throw new NoConfigurationException(
        String.format(
            "Cloud %s does not have any matching instance configurations.", this.getCloudName()));
  }

  /** Gets {@link InstanceConfiguration} that has the matching Description. */
  public InstanceConfiguration getInstanceConfig(String description) {
    for (InstanceConfiguration c : configurations) {
      if (c.getDescription().equals(description)) {
        return c;
      }
    }
    return null;
  }

  public HttpResponse doProvision(@QueryParameter String configuration)
      throws ServletException, IOException {
    checkPermission(PROVISION);
    if (configuration == null) {
      throw HttpResponses.error(SC_BAD_REQUEST, "The 'configuration' query parameter is missing");
    }
    InstanceConfiguration c = getInstanceConfig(configuration);
    if (c == null) {
      throw HttpResponses.error(SC_BAD_REQUEST, "No such Instance Configuration: " + configuration);
    }

    ComputeEngineInstance node = c.provision(StreamTaskListener.fromStdout());
    if (node == null) throw HttpResponses.error(SC_BAD_REQUEST, "Could not provision new node.");
    Jenkins.get().addNode(node);

    return HttpResponses.redirectViaContextPath("/computer/" + node.getNodeName());
  }

  @Extension
  public static class GoogleCloudDescriptor extends Descriptor<Cloud> {

    @Nonnull
    @Override
    public String getDisplayName() {
      return Messages.ComputeEngineCloud_DisplayName();
    }

    public FormValidation doCheckProjectId(@QueryParameter String value) {
      if (value == null || value.isEmpty()) {
        return FormValidation.error("Project ID is required");
      }
      return FormValidation.ok();
    }

    public ListBoxModel doFillCredentialsIdItems(
        @AncestorInPath Jenkins context, @QueryParameter String value) {
      if (context == null || !context.hasPermission(Item.CONFIGURE)) {
        return new StandardListBoxModel();
      }

      List<DomainRequirement> domainRequirements = new ArrayList<DomainRequirement>();
      return new StandardListBoxModel()
          .withEmptySelection()
          .withMatching(
              CredentialsMatchers.instanceOf(GoogleOAuth2Credentials.class),
              CredentialsProvider.lookupCredentials(
                  StandardCredentials.class, context, ACL.SYSTEM, domainRequirements));
    }

    public FormValidation doCheckCredentialsId(
        @AncestorInPath Jenkins context,
        @QueryParameter("projectId") String projectId,
        @QueryParameter String value) {
      if (value.isEmpty()) return FormValidation.error("No credential selected");

      if (projectId.isEmpty())
        return FormValidation.error("Project ID required to validate credential");
      try {
        ClientFactory clientFactory =
            new ClientFactory(context, new ArrayList<DomainRequirement>(), value);
        ComputeClient compute = clientFactory.compute();
        compute.getRegions(projectId);
        return FormValidation.ok(
            "The credential successfully made an API request to Google Compute Engine.");
      } catch (IOException ioe) {
        return FormValidation.error("Could not list regions in project " + projectId);
      }
    }
  }
}
