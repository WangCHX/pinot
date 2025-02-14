/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.helix;

import com.google.common.base.Preconditions;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.NotificationContext;
import org.apache.helix.ZNRecord;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.Message;
import org.apache.helix.model.ResourceConfig;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.apache.helix.participant.statemachine.StateModel;
import org.apache.helix.participant.statemachine.StateModelFactory;
import org.apache.helix.participant.statemachine.StateModelInfo;
import org.apache.helix.participant.statemachine.Transition;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.pinot.common.exception.HttpErrorStatusException;
import org.apache.pinot.common.utils.SimpleHttpResponse;
import org.apache.pinot.common.utils.ZkStarter;
import org.apache.pinot.common.utils.config.TagNameUtils;
import org.apache.pinot.common.utils.http.HttpClient;
import org.apache.pinot.controller.BaseControllerStarter;
import org.apache.pinot.controller.ControllerConf;
import org.apache.pinot.controller.ControllerStarter;
import org.apache.pinot.controller.helix.core.PinotHelixResourceManager;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.env.PinotConfiguration;
import org.apache.pinot.spi.utils.CommonConstants.Helix;
import org.apache.pinot.spi.utils.CommonConstants.Server;
import org.apache.pinot.spi.utils.NetUtils;
import org.apache.pinot.spi.utils.builder.ControllerRequestURLBuilder;
import org.apache.pinot.spi.utils.builder.TableNameBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import static org.testng.Assert.assertNotNull;


public abstract class ControllerTest {
  protected static final String LOCAL_HOST = "localhost";
  protected static final int DEFAULT_CONTROLLER_PORT = 18998;
  protected static final String DEFAULT_DATA_DIR =
      new File(FileUtils.getTempDirectoryPath(), "test-controller-" + System.currentTimeMillis()).getAbsolutePath();
  protected static final String BROKER_INSTANCE_ID_PREFIX = "Broker_localhost_";
  protected static final String SERVER_INSTANCE_ID_PREFIX = "Server_localhost_";
  protected static final String MINION_INSTANCE_ID_PREFIX = "Minion_localhost_";

  protected static int _controllerPort;
  protected static String _controllerBaseApiUrl;
  protected static ControllerRequestURLBuilder _controllerRequestURLBuilder;

  protected static HttpClient _httpClient = null;
  protected static ControllerRequestClient _controllerRequestClient = null;

  protected final List<HelixManager> _fakeInstanceHelixManagers = new ArrayList<>();
  protected String _controllerDataDir;

  protected BaseControllerStarter _controllerStarter;
  protected PinotHelixResourceManager _helixResourceManager;
  protected HelixManager _helixManager;
  protected HelixAdmin _helixAdmin;
  protected HelixDataAccessor _helixDataAccessor;
  protected ZkHelixPropertyStore<ZNRecord> _propertyStore;

  private ZkStarter.ZookeeperInstance _zookeeperInstance;

  protected String getHelixClusterName() {
    return getClass().getSimpleName();
  }

  /** HttpClient is lazy evaluated, static object, only instantiate when first use.
   *
   * <p>This is because {@code ControllerTest} has HTTP utils that depends on the TLSUtils to install the security
   * context first before the HttpClient can be initialized. However, because we have static usages of the HTTPClient,
   * it is not possible to create normal member variable, thus the workaround.
   */
  protected static HttpClient getHttpClient() {
    if (_httpClient == null) {
      _httpClient = HttpClient.getInstance();
    }
    return _httpClient;
  }

  /** ControllerRequestClient is lazy evaluated, static object, only instantiate when first use.
   *
   * <p>This is because {@code ControllerTest} has HTTP utils that depends on the TLSUtils to install the security
   * context first before the ControllerRequestClient can be initialized. However, because we have static usages of the
   * ControllerRequestClient, it is not possible to create normal member variable, thus the workaround.
   */
  protected static ControllerRequestClient getControllerRequestClient() {
    if (_controllerRequestClient == null) {
      _controllerRequestClient = new ControllerRequestClient(_controllerRequestURLBuilder, getHttpClient());
    }
    return _controllerRequestClient;
  }

  protected void startZk() {
    _zookeeperInstance = ZkStarter.startLocalZkServer();
  }

  protected void startZk(int port) {
    _zookeeperInstance = ZkStarter.startLocalZkServer(port);
  }

  protected void stopZk() {
    try {
      ZkStarter.stopLocalZkServer(_zookeeperInstance);
    } catch (Exception e) {
      // Swallow exceptions
    }
  }

  protected String getZkUrl() {
    return _zookeeperInstance.getZkUrl();
  }

  public Map<String, Object> getDefaultControllerConfiguration() {
    Map<String, Object> properties = new HashMap<>();

    properties.put(ControllerConf.CONTROLLER_HOST, LOCAL_HOST);
    properties.put(ControllerConf.CONTROLLER_PORT, NetUtils.findOpenPort(DEFAULT_CONTROLLER_PORT));
    properties.put(ControllerConf.DATA_DIR, DEFAULT_DATA_DIR);
    properties.put(ControllerConf.ZK_STR, getZkUrl());
    properties.put(ControllerConf.HELIX_CLUSTER_NAME, getHelixClusterName());
    // Enable groovy on the controller
    properties.put(ControllerConf.DISABLE_GROOVY, false);
    return properties;
  }

  protected void startController()
      throws Exception {
    startController(getDefaultControllerConfiguration());
  }

  protected void startController(Map<String, Object> properties)
      throws Exception {
    Preconditions.checkState(_controllerStarter == null);

    ControllerConf config = new ControllerConf(properties);

    String controllerScheme = "http";
    if (StringUtils.isNotBlank(config.getControllerVipProtocol())) {
      controllerScheme = config.getControllerVipProtocol();
    }

    _controllerPort = DEFAULT_CONTROLLER_PORT;
    if (StringUtils.isNotBlank(config.getControllerPort())) {
      _controllerPort = Integer.parseInt(config.getControllerPort());
    }

    _controllerBaseApiUrl = controllerScheme + "://localhost:" + _controllerPort;
    _controllerRequestURLBuilder = ControllerRequestURLBuilder.baseUrl(_controllerBaseApiUrl);
    _controllerDataDir = config.getDataDir();

    _controllerStarter = getControllerStarter();
    _controllerStarter.init(new PinotConfiguration(properties));
    _controllerStarter.start();
    _helixResourceManager = _controllerStarter.getHelixResourceManager();
    _helixManager = _controllerStarter.getHelixControllerManager();
    _helixDataAccessor = _helixManager.getHelixDataAccessor();
    ConfigAccessor configAccessor = _helixManager.getConfigAccessor();
    // HelixResourceManager is null in Helix only mode, while HelixManager is null in Pinot only mode.
    HelixConfigScope scope =
        new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).forCluster(getHelixClusterName())
            .build();
    switch (_controllerStarter.getControllerMode()) {
      case DUAL:
      case PINOT_ONLY:
        _helixAdmin = _helixResourceManager.getHelixAdmin();
        _propertyStore = _helixResourceManager.getPropertyStore();

        // TODO: Enable periodic rebalance per 10 seconds as a temporary work-around for the Helix issue:
        //       https://github.com/apache/helix/issues/331. Remove this after Helix fixing the issue.
        configAccessor.set(scope, ClusterConfig.ClusterConfigProperty.REBALANCE_TIMER_PERIOD.name(), "10000");
        break;
      case HELIX_ONLY:
        _helixAdmin = _helixManager.getClusterManagmentTool();
        _propertyStore = _helixManager.getHelixPropertyStore();
        break;
      default:
        break;
    }
    // Enable case-insensitive for test cases.
    configAccessor.set(scope, Helix.ENABLE_CASE_INSENSITIVE_KEY, Boolean.toString(true));
    // Set hyperloglog log2m value to 12.
    configAccessor.set(scope, Helix.DEFAULT_HYPERLOGLOG_LOG2M_KEY, Integer.toString(12));
  }

  protected ControllerStarter getControllerStarter() {
    return new ControllerStarter();
  }

  protected int getControllerPort() {
    return _controllerPort;
  }

  protected void stopController() {
    _controllerStarter.stop();
    _controllerStarter = null;
    FileUtils.deleteQuietly(new File(_controllerDataDir));
  }

  protected void addFakeBrokerInstancesToAutoJoinHelixCluster(int numInstances, boolean isSingleTenant)
      throws Exception {
    for (int i = 0; i < numInstances; i++) {
      addFakeBrokerInstanceToAutoJoinHelixCluster(BROKER_INSTANCE_ID_PREFIX + i, isSingleTenant);
    }
  }

  protected void addFakeBrokerInstanceToAutoJoinHelixCluster(String instanceId, boolean isSingleTenant)
      throws Exception {
    HelixManager helixManager =
        HelixManagerFactory.getZKHelixManager(getHelixClusterName(), instanceId, InstanceType.PARTICIPANT, getZkUrl());
    helixManager.getStateMachineEngine()
        .registerStateModelFactory(FakeBrokerResourceOnlineOfflineStateModelFactory.STATE_MODEL_DEF,
            FakeBrokerResourceOnlineOfflineStateModelFactory.FACTORY_INSTANCE);
    helixManager.connect();
    HelixAdmin helixAdmin = helixManager.getClusterManagmentTool();
    if (isSingleTenant) {
      helixAdmin.addInstanceTag(getHelixClusterName(), instanceId, TagNameUtils.getBrokerTagForTenant(null));
    } else {
      helixAdmin.addInstanceTag(getHelixClusterName(), instanceId, Helix.UNTAGGED_BROKER_INSTANCE);
    }
    _fakeInstanceHelixManagers.add(helixManager);
  }

  public static class FakeBrokerResourceOnlineOfflineStateModelFactory extends StateModelFactory<StateModel> {
    private static final String STATE_MODEL_DEF = "BrokerResourceOnlineOfflineStateModel";
    private static final FakeBrokerResourceOnlineOfflineStateModelFactory FACTORY_INSTANCE =
        new FakeBrokerResourceOnlineOfflineStateModelFactory();
    private static final FakeBrokerResourceOnlineOfflineStateModel STATE_MODEL_INSTANCE =
        new FakeBrokerResourceOnlineOfflineStateModel();

    private FakeBrokerResourceOnlineOfflineStateModelFactory() {
    }

    @Override
    public StateModel createNewStateModel(String resourceName, String partitionName) {
      return STATE_MODEL_INSTANCE;
    }

    @SuppressWarnings("unused")
    @StateModelInfo(states = "{'OFFLINE', 'ONLINE', 'DROPPED'}", initialState = "OFFLINE")
    public static class FakeBrokerResourceOnlineOfflineStateModel extends StateModel {
      private static final Logger LOGGER = LoggerFactory.getLogger(FakeBrokerResourceOnlineOfflineStateModel.class);

      private FakeBrokerResourceOnlineOfflineStateModel() {
      }

      @Transition(from = "OFFLINE", to = "ONLINE")
      public void onBecomeOnlineFromOffline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOnlineFromOffline(): {}", message);
      }

      @Transition(from = "OFFLINE", to = "DROPPED")
      public void onBecomeDroppedFromOffline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeDroppedFromOffline(): {}", message);
      }

      @Transition(from = "ONLINE", to = "OFFLINE")
      public void onBecomeOfflineFromOnline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOfflineFromOnline(): {}", message);
      }

      @Transition(from = "ONLINE", to = "DROPPED")
      public void onBecomeDroppedFromOnline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeDroppedFromOnline(): {}", message);
      }

      @Transition(from = "ERROR", to = "OFFLINE")
      public void onBecomeOfflineFromError(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOfflineFromError(): {}", message);
      }
    }
  }

  protected void addFakeServerInstancesToAutoJoinHelixCluster(int numInstances, boolean isSingleTenant)
      throws Exception {
    addFakeServerInstancesToAutoJoinHelixCluster(numInstances, isSingleTenant, Server.DEFAULT_ADMIN_API_PORT);
  }

  protected void addFakeServerInstancesToAutoJoinHelixCluster(int numInstances, boolean isSingleTenant,
      int baseAdminPort)
      throws Exception {
    for (int i = 0; i < numInstances; i++) {
      addFakeServerInstanceToAutoJoinHelixCluster(SERVER_INSTANCE_ID_PREFIX + i, isSingleTenant, baseAdminPort + i);
    }
  }

  protected void addFakeServerInstanceToAutoJoinHelixCluster(String instanceId, boolean isSingleTenant)
      throws Exception {
    addFakeServerInstanceToAutoJoinHelixCluster(instanceId, isSingleTenant, Server.DEFAULT_ADMIN_API_PORT);
  }

  protected void addFakeServerInstanceToAutoJoinHelixCluster(String instanceId, boolean isSingleTenant, int adminPort)
      throws Exception {
    HelixManager helixManager =
        HelixManagerFactory.getZKHelixManager(getHelixClusterName(), instanceId, InstanceType.PARTICIPANT, getZkUrl());
    helixManager.getStateMachineEngine()
        .registerStateModelFactory(FakeSegmentOnlineOfflineStateModelFactory.STATE_MODEL_DEF,
            FakeSegmentOnlineOfflineStateModelFactory.FACTORY_INSTANCE);
    helixManager.connect();
    HelixAdmin helixAdmin = helixManager.getClusterManagmentTool();
    if (isSingleTenant) {
      helixAdmin.addInstanceTag(getHelixClusterName(), instanceId, TagNameUtils.getOfflineTagForTenant(null));
      helixAdmin.addInstanceTag(getHelixClusterName(), instanceId, TagNameUtils.getRealtimeTagForTenant(null));
    } else {
      helixAdmin.addInstanceTag(getHelixClusterName(), instanceId, Helix.UNTAGGED_SERVER_INSTANCE);
    }
    HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.PARTICIPANT,
        getHelixClusterName()).forParticipant(instanceId).build();
    helixAdmin.setConfig(configScope,
        Collections.singletonMap(Helix.Instance.ADMIN_PORT_KEY, Integer.toString(adminPort)));
    _fakeInstanceHelixManagers.add(helixManager);
  }

  public static class FakeSegmentOnlineOfflineStateModelFactory extends StateModelFactory<StateModel> {
    private static final String STATE_MODEL_DEF = "SegmentOnlineOfflineStateModel";
    private static final FakeSegmentOnlineOfflineStateModelFactory FACTORY_INSTANCE =
        new FakeSegmentOnlineOfflineStateModelFactory();
    private static final FakeSegmentOnlineOfflineStateModel STATE_MODEL_INSTANCE =
        new FakeSegmentOnlineOfflineStateModel();

    private FakeSegmentOnlineOfflineStateModelFactory() {
    }

    @Override
    public StateModel createNewStateModel(String resourceName, String partitionName) {
      return STATE_MODEL_INSTANCE;
    }

    @SuppressWarnings("unused")
    @StateModelInfo(states = "{'OFFLINE', 'ONLINE', 'CONSUMING', 'DROPPED'}", initialState = "OFFLINE")
    public static class FakeSegmentOnlineOfflineStateModel extends StateModel {
      private static final Logger LOGGER = LoggerFactory.getLogger(FakeSegmentOnlineOfflineStateModel.class);

      private FakeSegmentOnlineOfflineStateModel() {
      }

      @Transition(from = "OFFLINE", to = "ONLINE")
      public void onBecomeOnlineFromOffline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOnlineFromOffline(): {}", message);
      }

      @Transition(from = "OFFLINE", to = "CONSUMING")
      public void onBecomeConsumingFromOffline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeConsumingFromOffline(): {}", message);
      }

      @Transition(from = "OFFLINE", to = "DROPPED")
      public void onBecomeDroppedFromOffline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeDroppedFromOffline(): {}", message);
      }

      @Transition(from = "ONLINE", to = "OFFLINE")
      public void onBecomeOfflineFromOnline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOfflineFromOnline(): {}", message);
      }

      @Transition(from = "ONLINE", to = "DROPPED")
      public void onBecomeDroppedFromOnline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeDroppedFromOnline(): {}", message);
      }

      @Transition(from = "CONSUMING", to = "OFFLINE")
      public void onBecomeOfflineFromConsuming(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOfflineFromConsuming(): {}", message);
      }

      @Transition(from = "CONSUMING", to = "ONLINE")
      public void onBecomeOnlineFromConsuming(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOnlineFromConsuming(): {}", message);
      }

      @Transition(from = "CONSUMING", to = "DROPPED")
      public void onBecomeDroppedFromConsuming(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeDroppedFromConsuming(): {}", message);
      }

      @Transition(from = "ERROR", to = "OFFLINE")
      public void onBecomeOfflineFromError(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOfflineFromError(): {}", message);
      }
    }
  }

  protected void addFakeMinionInstancesToAutoJoinHelixCluster(int numInstances)
      throws Exception {
    for (int i = 0; i < numInstances; i++) {
      addFakeMinionInstanceToAutoJoinHelixCluster(MINION_INSTANCE_ID_PREFIX + i);
    }
  }

  protected void addFakeMinionInstanceToAutoJoinHelixCluster(String instanceId)
      throws Exception {
    HelixManager helixManager =
        HelixManagerFactory.getZKHelixManager(getHelixClusterName(), instanceId, InstanceType.PARTICIPANT, getZkUrl());
    helixManager.getStateMachineEngine()
        .registerStateModelFactory(FakeMinionResourceOnlineOfflineStateModelFactory.STATE_MODEL_DEF,
            FakeMinionResourceOnlineOfflineStateModelFactory.FACTORY_INSTANCE);
    helixManager.connect();
    HelixAdmin helixAdmin = helixManager.getClusterManagmentTool();
    helixAdmin.addInstanceTag(getHelixClusterName(), instanceId, Helix.UNTAGGED_MINION_INSTANCE);
    _fakeInstanceHelixManagers.add(helixManager);
  }

  public static class FakeMinionResourceOnlineOfflineStateModelFactory extends StateModelFactory<StateModel> {
    private static final String STATE_MODEL_DEF = "MinionResourceOnlineOfflineStateModel";
    private static final FakeMinionResourceOnlineOfflineStateModelFactory FACTORY_INSTANCE =
        new FakeMinionResourceOnlineOfflineStateModelFactory();
    private static final FakeMinionResourceOnlineOfflineStateModel STATE_MODEL_INSTANCE =
        new FakeMinionResourceOnlineOfflineStateModel();

    private FakeMinionResourceOnlineOfflineStateModelFactory() {
    }

    @Override
    public StateModel createNewStateModel(String resourceName, String partitionName) {
      return STATE_MODEL_INSTANCE;
    }

    @SuppressWarnings("unused")
    @StateModelInfo(states = "{'OFFLINE', 'ONLINE', 'DROPPED'}", initialState = "OFFLINE")
    public static class FakeMinionResourceOnlineOfflineStateModel extends StateModel {
      private static final Logger LOGGER = LoggerFactory.getLogger(FakeMinionResourceOnlineOfflineStateModel.class);

      private FakeMinionResourceOnlineOfflineStateModel() {
      }

      @Transition(from = "OFFLINE", to = "ONLINE")
      public void onBecomeOnlineFromOffline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOnlineFromOffline(): {}", message);
      }

      @Transition(from = "OFFLINE", to = "DROPPED")
      public void onBecomeDroppedFromOffline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeDroppedFromOffline(): {}", message);
      }

      @Transition(from = "ONLINE", to = "OFFLINE")
      public void onBecomeOfflineFromOnline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOfflineFromOnline(): {}", message);
      }

      @Transition(from = "ONLINE", to = "DROPPED")
      public void onBecomeDroppedFromOnline(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeDroppedFromOnline(): {}", message);
      }

      @Transition(from = "ERROR", to = "OFFLINE")
      public void onBecomeOfflineFromError(Message message, NotificationContext context) {
        LOGGER.debug("onBecomeOfflineFromError(): {}", message);
      }
    }
  }

  protected void stopFakeInstances() {
    for (HelixManager helixManager : _fakeInstanceHelixManagers) {
      helixManager.disconnect();
    }
    _fakeInstanceHelixManagers.clear();
  }

  protected void stopFakeInstance(String instanceId) {
    for (HelixManager helixManager : _fakeInstanceHelixManagers) {
      if (helixManager.getInstanceName().equalsIgnoreCase(instanceId)) {
        helixManager.disconnect();
        _fakeInstanceHelixManagers.remove(helixManager);
        return;
      }
    }
  }

  /**
   * Add a schema to the controller.
   */
  protected void addSchema(Schema schema)
      throws IOException {
    getControllerRequestClient().addSchema(schema);
  }

  protected Schema getSchema(String schemaName) {
    Schema schema = _helixResourceManager.getSchema(schemaName);
    assertNotNull(schema);
    return schema;
  }

  protected void deleteSchema(String schemaName)
      throws IOException {
    getControllerRequestClient().deleteSchema(schemaName);
  }

  protected void addTableConfig(TableConfig tableConfig)
      throws IOException {
    getControllerRequestClient().addTableConfig(tableConfig);
  }

  protected void updateTableConfig(TableConfig tableConfig)
      throws IOException {
    getControllerRequestClient().updateTableConfig(tableConfig);
  }

  protected TableConfig getOfflineTableConfig(String tableName) {
    TableConfig offlineTableConfig = _helixResourceManager.getOfflineTableConfig(tableName);
    Assert.assertNotNull(offlineTableConfig);
    return offlineTableConfig;
  }

  protected TableConfig getRealtimeTableConfig(String tableName) {
    TableConfig realtimeTableConfig = _helixResourceManager.getRealtimeTableConfig(tableName);
    Assert.assertNotNull(realtimeTableConfig);
    return realtimeTableConfig;
  }

  protected void dropOfflineTable(String tableName)
      throws IOException {
    getControllerRequestClient().deleteTable(TableNameBuilder.OFFLINE.tableNameWithType(tableName));
  }

  protected void dropRealtimeTable(String tableName)
      throws IOException {
    getControllerRequestClient().deleteTable(TableNameBuilder.REALTIME.tableNameWithType(tableName));
  }

  protected void dropAllSegments(String tableName, TableType tableType)
      throws IOException {
    getControllerRequestClient().deleteSegments(tableName, tableType);
  }

  protected long getTableSize(String tableName)
      throws IOException {
    return getControllerRequestClient().getTableSize(tableName);
  }

  protected void reloadOfflineTable(String tableName)
      throws IOException {
    reloadOfflineTable(tableName, false);
  }

  protected void reloadOfflineTable(String tableName, boolean forceDownload)
      throws IOException {
    getControllerRequestClient().reloadTable(tableName, TableType.OFFLINE, forceDownload);
  }

  protected void reloadOfflineSegment(String tableName, String segmentName, boolean forceDownload)
      throws IOException {
    getControllerRequestClient().reloadSegment(tableName, segmentName, forceDownload);
  }

  protected void reloadRealtimeTable(String tableName)
      throws IOException {
    getControllerRequestClient().reloadTable(tableName, TableType.REALTIME, false);
  }

  protected void createBrokerTenant(String tenantName, int numBrokers)
      throws IOException {
    getControllerRequestClient().createBrokerTenant(tenantName, numBrokers);
  }

  protected void updateBrokerTenant(String tenantName, int numBrokers)
      throws IOException {
    getControllerRequestClient().updateBrokerTenant(tenantName, numBrokers);
  }

  protected void createServerTenant(String tenantName, int numOfflineServers, int numRealtimeServers)
      throws IOException {
    getControllerRequestClient().createServerTenant(tenantName, numOfflineServers, numRealtimeServers);
  }

  protected void updateServerTenant(String tenantName, int numOfflineServers, int numRealtimeServers)
      throws IOException {
    getControllerRequestClient().updateServerTenant(tenantName, numOfflineServers, numRealtimeServers);
  }

  public void enableResourceConfigForLeadControllerResource(boolean enable) {
    ConfigAccessor configAccessor = _helixManager.getConfigAccessor();
    ResourceConfig resourceConfig =
        configAccessor.getResourceConfig(getHelixClusterName(), Helix.LEAD_CONTROLLER_RESOURCE_NAME);
    if (Boolean.parseBoolean(resourceConfig.getSimpleConfig(Helix.LEAD_CONTROLLER_RESOURCE_ENABLED_KEY)) != enable) {
      resourceConfig.putSimpleConfig(Helix.LEAD_CONTROLLER_RESOURCE_ENABLED_KEY, Boolean.toString(enable));
      configAccessor.setResourceConfig(getHelixClusterName(), Helix.LEAD_CONTROLLER_RESOURCE_NAME, resourceConfig);
    }
  }

  public static String sendGetRequest(String urlString)
      throws IOException {
    return sendGetRequest(urlString, null);
  }

  public static String sendGetRequest(String urlString, Map<String, String> headers)
      throws IOException {
    try {
      SimpleHttpResponse resp =
          HttpClient.wrapAndThrowHttpException(getHttpClient().sendGetRequest(new URL(urlString).toURI(), headers));
      return constructResponse(resp);
    } catch (URISyntaxException | HttpErrorStatusException e) {
      throw new IOException(e);
    }
  }

  public static String sendGetRequestRaw(String urlString)
      throws IOException {
    return IOUtils.toString(new URL(urlString).openStream());
  }

  public static String sendPostRequest(String urlString, String payload)
      throws IOException {
    return sendPostRequest(urlString, payload, Collections.emptyMap());
  }

  public static String sendPostRequest(String urlString, String payload, Map<String, String> headers)
      throws IOException {
    try {
      SimpleHttpResponse resp = HttpClient.wrapAndThrowHttpException(
          getHttpClient().sendJsonPostRequest(new URL(urlString).toURI(), payload, headers));
      return constructResponse(resp);
    } catch (URISyntaxException | HttpErrorStatusException e) {
      throw new IOException(e);
    }
  }

  public static String sendPostRequestRaw(String urlString, String payload, Map<String, String> headers)
      throws IOException {
    try {
      EntityBuilder builder = EntityBuilder.create();
      builder.setText(payload);
      SimpleHttpResponse resp = HttpClient.wrapAndThrowHttpException(
          getHttpClient().sendPostRequest(new URL(urlString).toURI(), builder.build(), headers));
      return constructResponse(resp);
    } catch (URISyntaxException | HttpErrorStatusException e) {
      throw new IOException(e);
    }
  }

  public static String sendPutRequest(String urlString)
      throws IOException {
    return sendPutRequest(urlString, null);
  }

  public static String sendPutRequest(String urlString, String payload)
      throws IOException {
    return sendPutRequest(urlString, payload, Collections.emptyMap());
  }

  public static String sendPutRequest(String urlString, String payload, Map<String, String> headers)
      throws IOException {
    try {
      SimpleHttpResponse resp = HttpClient.wrapAndThrowHttpException(
          getHttpClient().sendJsonPutRequest(new URL(urlString).toURI(), payload, headers));
      return constructResponse(resp);
    } catch (URISyntaxException | HttpErrorStatusException e) {
      throw new IOException(e);
    }
  }

  public static String sendDeleteRequest(String urlString)
      throws IOException {
    return sendDeleteRequest(urlString, Collections.emptyMap());
  }

  public static String sendDeleteRequest(String urlString, Map<String, String> headers)
      throws IOException {
    try {
      SimpleHttpResponse resp =
          HttpClient.wrapAndThrowHttpException(getHttpClient().sendDeleteRequest(new URL(urlString).toURI(), headers));
      return constructResponse(resp);
    } catch (URISyntaxException | HttpErrorStatusException e) {
      throw new IOException(e);
    }
  }

  private static String constructResponse(SimpleHttpResponse resp) {
    return resp.getResponse();
  }

  public static SimpleHttpResponse sendMultipartPostRequest(String url, String body)
      throws IOException {
    return sendMultipartPostRequest(url, body, Collections.emptyMap());
  }

  public static SimpleHttpResponse sendMultipartPostRequest(String url, String body, Map<String, String> headers)
      throws IOException {
    return getHttpClient().sendMultipartPostRequest(url, body, headers);
  }

  public static SimpleHttpResponse sendMultipartPutRequest(String url, String body)
      throws IOException {
    return sendMultipartPutRequest(url, body, null);
  }

  public static SimpleHttpResponse sendMultipartPutRequest(String url, String body, Map<String, String> headers)
      throws IOException {
    return getHttpClient().sendMultipartPutRequest(url, body, headers);
  }
}
