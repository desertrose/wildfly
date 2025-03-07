/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.messaging.activemq.jms;

import static org.jboss.as.naming.deployment.ContextNames.BindInfo;
import static org.wildfly.extension.messaging.activemq.MessagingServices.getActiveMQServiceName;
import static org.wildfly.extension.messaging.activemq.jms.ConnectionFactoryAttributes.Pooled.REBALANCE_CONNECTIONS_PROP_NAME;
import static org.wildfly.extension.messaging.activemq.jms.JMSQueueService.JMS_QUEUE_PREFIX;
import static org.wildfly.extension.messaging.activemq.jms.JMSTopicService.JMS_TOPIC_PREFIX;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.activemq.artemis.api.core.BroadcastEndpointFactory;
import org.apache.activemq.artemis.api.core.DiscoveryGroupConfiguration;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.UDPBroadcastEndpointFactory;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.jboss.as.connector.metadata.common.CredentialImpl;
import org.jboss.as.connector.metadata.common.SecurityImpl;
import org.jboss.as.connector.services.mdr.AS7MetadataRepository;
import org.jboss.as.connector.services.resourceadapters.ResourceAdapterActivatorService;
import org.jboss.as.connector.services.resourceadapters.deployment.registry.ResourceAdapterDeploymentRegistry;
import org.jboss.as.connector.subsystems.jca.JcaSubsystemConfiguration;
import org.jboss.as.connector.util.ConnectorServices;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.security.CredentialReference;
import org.jboss.as.naming.service.NamingService;
import org.jboss.as.network.SocketBinding;
import org.jboss.as.server.Services;
import org.jboss.dmr.ModelNode;
import org.jboss.jca.common.api.metadata.Defaults;
import org.jboss.jca.common.api.metadata.common.FlushStrategy;
import org.jboss.jca.common.api.metadata.common.Pool;
import org.jboss.jca.common.api.metadata.common.Recovery;
import org.jboss.jca.common.api.metadata.common.Security;
import org.jboss.jca.common.api.metadata.common.TimeOut;
import org.jboss.jca.common.api.metadata.common.TransactionSupportEnum;
import org.jboss.jca.common.api.metadata.common.Validation;
import org.jboss.jca.common.api.metadata.resourceadapter.Activation;
import org.jboss.jca.common.api.metadata.resourceadapter.AdminObject;
import org.jboss.jca.common.api.metadata.resourceadapter.ConnectionDefinition;
import org.jboss.jca.common.api.metadata.spec.Activationspec;
import org.jboss.jca.common.api.metadata.spec.AuthenticationMechanism;
import org.jboss.jca.common.api.metadata.spec.ConfigProperty;
import org.jboss.jca.common.api.metadata.spec.Connector;
import org.jboss.jca.common.api.metadata.spec.CredentialInterfaceEnum;
import org.jboss.jca.common.api.metadata.spec.Icon;
import org.jboss.jca.common.api.metadata.spec.InboundResourceAdapter;
import org.jboss.jca.common.api.metadata.spec.LocalizedXsdString;
import org.jboss.jca.common.api.metadata.spec.MessageListener;
import org.jboss.jca.common.api.metadata.spec.Messageadapter;
import org.jboss.jca.common.api.metadata.spec.OutboundResourceAdapter;
import org.jboss.jca.common.api.metadata.spec.RequiredConfigProperty;
import org.jboss.jca.common.api.metadata.spec.ResourceAdapter;
import org.jboss.jca.common.api.metadata.spec.SecurityPermission;
import org.jboss.jca.common.api.metadata.spec.XsdString;
import org.jboss.jca.common.api.validator.ValidateException;
import org.jboss.jca.common.metadata.common.PoolImpl;
import org.jboss.jca.common.metadata.common.TimeOutImpl;
import org.jboss.jca.common.metadata.common.ValidationImpl;
import org.jboss.jca.common.metadata.common.XaPoolImpl;
import org.jboss.jca.common.metadata.resourceadapter.ActivationImpl;
import org.jboss.jca.common.metadata.resourceadapter.ConnectionDefinitionImpl;
import org.jboss.jca.common.metadata.spec.ActivationSpecImpl;
import org.jboss.jca.common.metadata.spec.AuthenticationMechanismImpl;
import org.jboss.jca.common.metadata.spec.ConfigPropertyImpl;
import org.jboss.jca.common.metadata.spec.ConnectorImpl;
import org.jboss.jca.common.metadata.spec.InboundResourceAdapterImpl;
import org.jboss.jca.common.metadata.spec.MessageAdapterImpl;
import org.jboss.jca.common.metadata.spec.MessageListenerImpl;
import org.jboss.jca.common.metadata.spec.OutboundResourceAdapterImpl;
import org.jboss.jca.common.metadata.spec.RequiredConfigPropertyImpl;
import org.jboss.jca.common.metadata.spec.ResourceAdapterImpl;
import org.jboss.jca.core.api.connectionmanager.ccm.CachedConnectionManager;
import org.jboss.jca.core.api.management.ManagementRepository;
import org.jboss.jca.core.spi.rar.ResourceAdapterRepository;
import org.jboss.jca.core.spi.transaction.TransactionIntegration;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.common.function.ExceptionSupplier;
import org.wildfly.extension.messaging.activemq.ActiveMQActivationService;
import org.wildfly.extension.messaging.activemq.MessagingServices;
import org.wildfly.extension.messaging.activemq.broadcast.CommandDispatcherBroadcastEndpointFactory;
import org.wildfly.extension.messaging.activemq._private.MessagingLogger;
import org.wildfly.security.credential.PasswordCredential;
import org.wildfly.security.credential.source.CredentialSource;
import org.wildfly.security.password.interfaces.ClearPassword;

/**
 * A service which translates a pooled connection factory into a resource adapter driven connection pool
 *
 * @author <a href="mailto:andy.taylor@jboss.com">Andy Taylor</a>
 * @author <a href="mailto:jbertram@redhat.com">Justin Bertram</a>
 * @author Jason T. Greene
 *         Date: 5/13/11
 *         Time: 2:21 PM
 */
public class PooledConnectionFactoryService implements Service<Void> {

    private static final List<LocalizedXsdString> EMPTY_LOCL = Collections.emptyList();
    public static final String CONNECTOR_CLASSNAME = "connectorClassName";
    public static final String CONNECTION_PARAMETERS = "connectionParameters";
    private static final String ACTIVEMQ_ACTIVATION = "org.apache.activemq.artemis.ra.inflow.ActiveMQActivationSpec";
    private static final String ACTIVEMQ_CONN_DEF = "ActiveMQConnectionDefinition";
    private static final String ACTIVEMQ_RESOURCE_ADAPTER = "org.wildfly.extension.messaging.activemq.ActiveMQResourceAdapter";
    private static final String RAMANAGED_CONN_FACTORY = "org.apache.activemq.artemis.ra.ActiveMQRAManagedConnectionFactory";
    private static final String RA_CONN_FACTORY = "org.apache.activemq.artemis.ra.ActiveMQRAConnectionFactory";
    private static final String RA_CONN_FACTORY_IMPL = "org.apache.activemq.artemis.ra.ActiveMQRAConnectionFactoryImpl";
    private static final String JMS_SESSION = "jakarta.jms.Session";
    private static final String ACTIVEMQ_RA_SESSION = "org.apache.activemq.artemis.ra.ActiveMQRASession";
    private static final String BASIC_PASS = "BasicPassword";
    private static final String JMS_QUEUE = "jakarta.jms.Queue";
    private static final String STRING_TYPE = "java.lang.String";
    private static final String INTEGER_TYPE = "java.lang.Integer";
    private static final String LONG_TYPE = "java.lang.Long";
    private static final String SESSION_DEFAULT_TYPE = "SessionDefaultType";
    private static final String TRY_LOCK = "UseTryLock";
    private static final String JMS_MESSAGE_LISTENER = "jakarta.jms.MessageListener";
    private static final String DEFAULT_MAX_RECONNECTS = "5";
    public static final String GROUP_ADDRESS = "discoveryAddress";
    public static final String DISCOVERY_INITIAL_WAIT_TIMEOUT = "discoveryInitialWaitTimeout";
    public static final String GROUP_PORT = "discoveryPort";
    public static final String REFRESH_TIMEOUT = "discoveryRefreshTimeout";
    public static final String DISCOVERY_LOCAL_BIND_ADDRESS = "discoveryLocalBindAddress";
    public static final String JGROUPS_CHANNEL_LOCATOR_CLASS = "jgroupsChannelLocatorClass";
    public static final String JGROUPS_CHANNEL_NAME = "jgroupsChannelName";
    public static final String JGROUPS_CHANNEL_REF_NAME = "jgroupsChannelRefName";
    public static final String IGNORE_JTA = "ignoreJTA";

    private List<String> connectors;
    private String discoveryGroupName;
    private List<PooledConnectionFactoryConfigProperties> adapterParams;
    private String name;
    private Map<String, SocketBinding> socketBindings = new HashMap<String, SocketBinding>();
    private InjectedValue<ActiveMQServer> activeMQServer = new InjectedValue<>();
    private BindInfo bindInfo;
    private List<String> jndiAliases;
    private final boolean pickAnyConnectors;
    private String txSupport;
    private int minPoolSize;
    private int maxPoolSize;
    private String serverName;
    private final String jgroupsChannelName;
    private final boolean createBinderService;
    private final String managedConnectionPoolClassName;
    // can be null. In that case the behaviour is depending on the IronJacamar container setting.
    private final Boolean enlistmentTrace;
    private InjectedValue<ExceptionSupplier<CredentialSource, Exception>> credentialSourceSupplier = new InjectedValue<>();


    public PooledConnectionFactoryService(String name, List<String> connectors, String discoveryGroupName, String serverName, String jgroupsChannelName, List<PooledConnectionFactoryConfigProperties> adapterParams, BindInfo bindInfo, List<String> jndiAliases, String txSupport, int minPoolSize, int maxPoolSize, String managedConnectionPoolClassName, Boolean enlistmentTrace) {
        this.name = name;
        this.connectors = connectors;
        this.discoveryGroupName = discoveryGroupName;
        this.serverName = serverName;
        this.jgroupsChannelName = jgroupsChannelName;
        this.adapterParams = adapterParams;
        this.bindInfo = bindInfo;
        this.jndiAliases = new ArrayList<>(jndiAliases);
        createBinderService = true;
        this.txSupport = txSupport;
        this.minPoolSize = minPoolSize;
        this.maxPoolSize = maxPoolSize;
        this.managedConnectionPoolClassName = managedConnectionPoolClassName;
        this.enlistmentTrace = enlistmentTrace;
        this.pickAnyConnectors = false;
    }

    public PooledConnectionFactoryService(String name, List<String> connectors, String discoveryGroupName, String serverName, String jgroupsChannelName, List<PooledConnectionFactoryConfigProperties> adapterParams, BindInfo bindInfo, String txSupport, int minPoolSize, int maxPoolSize, String managedConnectionPoolClassName, Boolean enlistmentTrace, boolean pickAnyConnectors) {
        this.name = name;
        this.connectors = connectors;
        this.discoveryGroupName = discoveryGroupName;
        this.serverName = serverName;
        this.jgroupsChannelName = jgroupsChannelName;
        this.adapterParams = adapterParams;
        this.bindInfo = bindInfo;
        this.jndiAliases = Collections.emptyList();
        this.createBinderService = false;
        this.txSupport = txSupport;
        this.minPoolSize = minPoolSize;
        this.maxPoolSize = maxPoolSize;
        this.managedConnectionPoolClassName = managedConnectionPoolClassName;
        this.enlistmentTrace = enlistmentTrace;
        this.pickAnyConnectors = pickAnyConnectors;
    }

    static ServiceName getResourceAdapterActivatorsServiceName(String name) {
        return ConnectorServices.RESOURCE_ADAPTER_ACTIVATOR_SERVICE.append(name);
    }

    InjectedValue<ExceptionSupplier<CredentialSource, Exception>> getCredentialSourceSupplierInjector() {
        return credentialSourceSupplier;
    }

    public static PooledConnectionFactoryService installService(ServiceTarget serviceTarget,
                                      String name,
                                      String serverName,
                                      List<String> connectors,
                                      String discoveryGroupName,
                                      String jgroupsChannelName,
                                      List<PooledConnectionFactoryConfigProperties> adapterParams,
                                      BindInfo bindInfo,
                                      String txSupport,
                                      int minPoolSize,
                                      int maxPoolSize,
                                      String managedConnectionPoolClassName,
                                      Boolean enlistmentTrace,
                                      boolean pickAnyConnectors) {

        ServiceName serverServiceName = MessagingServices.getActiveMQServiceName(serverName);
        ServiceName serviceName = JMSServices.getPooledConnectionFactoryBaseServiceName(serverServiceName).append(name);

        PooledConnectionFactoryService service = new PooledConnectionFactoryService(name,
                connectors, discoveryGroupName, serverName, jgroupsChannelName, adapterParams,
                bindInfo, txSupport, minPoolSize, maxPoolSize, managedConnectionPoolClassName, enlistmentTrace, pickAnyConnectors);

        installService0(serviceTarget, serverServiceName, serviceName, service);
        return service;
    }

    public static PooledConnectionFactoryService installService(OperationContext context,
                                      String name,
                                      String serverName,
                                      List<String> connectors,
                                      String discoveryGroupName,
                                      String jgroupsChannelName,
                                      List<PooledConnectionFactoryConfigProperties> adapterParams,
                                      BindInfo bindInfo,
                                      List<String> jndiAliases,
                                      String txSupport,
                                      int minPoolSize,
                                      int maxPoolSize,
                                      String managedConnectionPoolClassName,
                                      Boolean enlistmentTrace,
                                      ModelNode model) throws OperationFailedException {

        ServiceName serverServiceName = MessagingServices.getActiveMQServiceName(serverName);
        ServiceName serviceName = JMSServices.getPooledConnectionFactoryBaseServiceName(serverServiceName).append(name);
        PooledConnectionFactoryService service = new PooledConnectionFactoryService(name,
                connectors, discoveryGroupName, serverName, jgroupsChannelName, adapterParams,
                bindInfo, jndiAliases, txSupport, minPoolSize, maxPoolSize, managedConnectionPoolClassName, enlistmentTrace);

        installService0(context, serverServiceName, serviceName, service, model);
        return service;
    }

    private static void installService0(ServiceTarget serviceTarget, ServiceName serverServiceName, ServiceName serviceName, PooledConnectionFactoryService service) {
        ServiceBuilder serviceBuilder = createServiceBuilder(serviceTarget, serverServiceName, serviceName, service);
        serviceBuilder.install();
    }

    private static void installService0(OperationContext context,
                                        ServiceName serverServiceName,
                                        ServiceName serviceName,
                                        PooledConnectionFactoryService service,
                                        ModelNode model) throws OperationFailedException {
        ServiceBuilder serviceBuilder = createServiceBuilder(context.getServiceTarget(), serverServiceName, serviceName, service);
        ModelNode credentialReference = ConnectionFactoryAttributes.Pooled.CREDENTIAL_REFERENCE.resolveModelAttribute(context, model);
        if (credentialReference.isDefined()) {
            service.getCredentialSourceSupplierInjector().inject(CredentialReference.getCredentialSourceSupplier(context, ConnectionFactoryAttributes.Pooled.CREDENTIAL_REFERENCE, model, serviceBuilder));
        }
        serviceBuilder.install();
    }

    private static ServiceBuilder createServiceBuilder(ServiceTarget serviceTarget, ServiceName serverServiceName, ServiceName serviceName, PooledConnectionFactoryService service) {
        ServiceBuilder serviceBuilder = serviceTarget.addService(serviceName, service);
        serviceBuilder.requires(MessagingServices.getCapabilityServiceName(MessagingServices.LOCAL_TRANSACTION_PROVIDER_CAPABILITY));
        serviceBuilder.addDependency(serverServiceName, ActiveMQServer.class, service.activeMQServer);
        serviceBuilder.requires(ActiveMQActivationService.getServiceName(serverServiceName));
        serviceBuilder.requires(JMSServices.getJmsManagerBaseServiceName(serverServiceName));
        // ensures that Artemis client thread pools are not stopped before any deployment depending on a pooled-connection-factory
        serviceBuilder.requires(MessagingServices.ACTIVEMQ_CLIENT_THREAD_POOL);
        serviceBuilder.setInitialMode(ServiceController.Mode.PASSIVE);
        return serviceBuilder;
    }

    @Override
    public Void getValue() throws IllegalStateException, IllegalArgumentException {
        return null;
    }


    @Override
    public void start(StartContext context) throws StartException {
        ServiceTarget serviceTarget = context.getChildTarget();
        try {
            createService(serviceTarget, context.getController().getServiceContainer());
        }
        catch (Exception e) {
            throw MessagingLogger.ROOT_LOGGER.failedToCreate(e, "resource adapter");
        }

    }

    private void createService(ServiceTarget serviceTarget, ServiceContainer container) throws Exception {
        InputStream is = null;
        InputStream isIj = null;
        // Properties for the resource adapter
        List<ConfigProperty> properties = new ArrayList<ConfigProperty>();
        try {
            StringBuilder connectorClassname = new StringBuilder();
            StringBuilder connectorParams = new StringBuilder();
            // if there is no discovery-group and the connector list is empty,
            // pick the first connector available if pickAnyConnectors is true
            if (discoveryGroupName == null && connectors.isEmpty() && pickAnyConnectors) {
                Set<String> connectorNames = activeMQServer.getValue().getConfiguration().getConnectorConfigurations().keySet();
                if (!connectorNames.isEmpty()) {
                    String connectorName = connectorNames.iterator().next();
                    MessagingLogger.ROOT_LOGGER.connectorForPooledConnectionFactory(name, connectorName);
                    connectors.add(connectorName);
                }
            }
            for (String connector : connectors) {
                TransportConfiguration tc = activeMQServer.getValue().getConfiguration().getConnectorConfigurations().get(connector);
                if(tc == null) {
                    throw MessagingLogger.ROOT_LOGGER.connectorNotDefined(connector);
                }
                if (connectorClassname.length() > 0) {
                    connectorClassname.append(",");
                    connectorParams.append(",");
                }
                connectorClassname.append(tc.getFactoryClassName());
                Map<String, Object> params = tc.getParams();
                boolean multiple = false;
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    if (multiple) {
                        connectorParams.append(";");
                    }
                    connectorParams.append(entry.getKey()).append("=").append(entry.getValue());
                    multiple = true;
                }
            }

            if (connectorClassname.length() > 0) {
                properties.add(simpleProperty15(CONNECTOR_CLASSNAME, STRING_TYPE, connectorClassname.toString()));
            }
            if (connectorParams.length() > 0) {
                properties.add(simpleProperty15(CONNECTION_PARAMETERS, STRING_TYPE, connectorParams.toString()));
            }

            if(discoveryGroupName != null) {
                DiscoveryGroupConfiguration discoveryGroupConfiguration = activeMQServer.getValue().getConfiguration().getDiscoveryGroupConfigurations().get(discoveryGroupName);
                BroadcastEndpointFactory bgCfg = discoveryGroupConfiguration.getBroadcastEndpointFactory();
                if (bgCfg instanceof UDPBroadcastEndpointFactory) {
                    UDPBroadcastEndpointFactory udpCfg = (UDPBroadcastEndpointFactory) bgCfg;
                    properties.add(simpleProperty15(GROUP_ADDRESS, STRING_TYPE, udpCfg.getGroupAddress()));
                    properties.add(simpleProperty15(GROUP_PORT, INTEGER_TYPE, "" + udpCfg.getGroupPort()));
                    properties.add(simpleProperty15(DISCOVERY_LOCAL_BIND_ADDRESS, STRING_TYPE, "" + udpCfg.getLocalBindAddress()));
                } else if (bgCfg instanceof CommandDispatcherBroadcastEndpointFactory) {
                    properties.add(simpleProperty15(JGROUPS_CHANNEL_NAME, STRING_TYPE, jgroupsChannelName));
                    properties.add(simpleProperty15(JGROUPS_CHANNEL_REF_NAME, STRING_TYPE, serverName + "/discovery" + discoveryGroupConfiguration.getName()));

                }
                properties.add(simpleProperty15(DISCOVERY_INITIAL_WAIT_TIMEOUT, LONG_TYPE, "" + discoveryGroupConfiguration.getDiscoveryInitialWaitTimeout()));
                properties.add(simpleProperty15(REFRESH_TIMEOUT, LONG_TYPE, "" + discoveryGroupConfiguration.getRefreshTimeout()));
            }

            boolean hasReconnect = false;
            final List<ConfigProperty> inboundProperties = new ArrayList<>();
            final List<ConfigProperty> outboundProperties = new ArrayList<>();
            final String reconnectName = ConnectionFactoryAttributes.Pooled.RECONNECT_ATTEMPTS_PROP_NAME;
            for (PooledConnectionFactoryConfigProperties adapterParam : adapterParams) {
                hasReconnect |= reconnectName.equals(adapterParam.getName());

                ConfigProperty p = simpleProperty15(adapterParam.getName(), adapterParam.getType(), adapterParam.getValue());
                if (adapterParam.getName().equals(REBALANCE_CONNECTIONS_PROP_NAME)) {
                    boolean rebalanceConnections = Boolean.parseBoolean(adapterParam.getValue());
                    if (rebalanceConnections) {
                        inboundProperties.add(p);
                    }
                } else {
                    if (null == adapterParam.getConfigType()) {
                        properties.add(p);
                    } else switch (adapterParam.getConfigType()) {
                        case INBOUND:
                            inboundProperties.add(p);
                            break;
                        case OUTBOUND:
                            outboundProperties.add(p);
                            break;
                        default:
                            properties.add(p);
                            break;
                    }
                }
            }

            // The default -1, which will hang forever until a server appears
            if (!hasReconnect) {
                properties.add(simpleProperty15(reconnectName, Integer.class.getName(), DEFAULT_MAX_RECONNECTS));
            }

            configureCredential(properties);

            // for backwards compatibility, the RA inbound is configured to prefix the Jakarta Messaging resources if JNDI lookups fail
            // and the destination are inferred from the JNDI name.
            inboundProperties.add(simpleProperty15("queuePrefix", String.class.getName(), JMS_QUEUE_PREFIX));
            inboundProperties.add(simpleProperty15("topicPrefix", String.class.getName(), JMS_TOPIC_PREFIX));

            WildFlyRecoveryRegistry.container = container;

            OutboundResourceAdapter outbound = createOutbound(outboundProperties);
            InboundResourceAdapter inbound = createInbound(inboundProperties);
            ResourceAdapter ra = createResourceAdapter15(properties, outbound, inbound);
            Connector cmd = createConnector15(ra);

            TransactionSupportEnum transactionSupport = getTransactionSupport(txSupport);
            ConnectionDefinition common = createConnDef(transactionSupport, bindInfo.getBindName(), minPoolSize, maxPoolSize, managedConnectionPoolClassName, enlistmentTrace);
            Activation activation = createActivation(common, transactionSupport);

            ResourceAdapterActivatorService activator = new ResourceAdapterActivatorService(cmd, activation,
                    PooledConnectionFactoryService.class.getClassLoader(), name);
            activator.setBindInfo(bindInfo);
            activator.setCreateBinderService(createBinderService);
            activator.addJndiAliases(jndiAliases);

            final ServiceBuilder sb =
                    Services.addServerExecutorDependency(
                        serviceTarget.addService(getResourceAdapterActivatorsServiceName(name), activator),
                            activator.getExecutorServiceInjector())
                    .addDependency(ConnectorServices.IRONJACAMAR_MDR, AS7MetadataRepository.class,
                            activator.getMdrInjector())
                    .addDependency(ConnectorServices.RA_REPOSITORY_SERVICE, ResourceAdapterRepository.class,
                            activator.getRaRepositoryInjector())
                    .addDependency(ConnectorServices.MANAGEMENT_REPOSITORY_SERVICE, ManagementRepository.class,
                            activator.getManagementRepositoryInjector())
                    .addDependency(ConnectorServices.RESOURCE_ADAPTER_REGISTRY_SERVICE,
                            ResourceAdapterDeploymentRegistry.class, activator.getRegistryInjector())
                    .addDependency(ConnectorServices.TRANSACTION_INTEGRATION_SERVICE, TransactionIntegration.class,
                            activator.getTxIntegrationInjector())
                    .addDependency(ConnectorServices.CONNECTOR_CONFIG_SERVICE,
                            JcaSubsystemConfiguration.class, activator.getConfigInjector())
                    .addDependency(ConnectorServices.CCM_SERVICE, CachedConnectionManager.class,
                            activator.getCcmInjector());
            sb.requires(ActiveMQActivationService.getServiceName(getActiveMQServiceName(serverName)));
            sb.requires(NamingService.SERVICE_NAME);
            sb.requires(MessagingServices.getCapabilityServiceName(MessagingServices.LOCAL_TRANSACTION_PROVIDER_CAPABILITY));
            sb.requires(ConnectorServices.BOOTSTRAP_CONTEXT_SERVICE.append("default"));
            sb.setInitialMode(ServiceController.Mode.PASSIVE).install();
            // Mock the deployment service to allow it to start
            serviceTarget.addService(ConnectorServices.RESOURCE_ADAPTER_DEPLOYER_SERVICE_PREFIX.append(name), Service.NULL).install();
        } finally {
            if (is != null)
                is.close();
            if (isIj != null)
                isIj.close();
        }
    }

    /**
     * Configure password from a credential-reference (as an alternative to the password attribute)
     * and add it to the RA properties.
     */
    private void configureCredential(List<ConfigProperty> properties) {
        // if a credential-reference has been defined, get the password property from it
        if (credentialSourceSupplier.getOptionalValue() != null) {
            try {
                CredentialSource credentialSource = credentialSourceSupplier.getValue().get();
                if (credentialSource != null) {
                    char[] password = credentialSource.getCredential(PasswordCredential.class).getPassword(ClearPassword.class).getPassword();
                    if (password != null) {
                        // add the password property
                        properties.add(simpleProperty15("password", String.class.getName(), new String(password)));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static TransactionSupportEnum getTransactionSupport(String txSupport) {
        try {
            return TransactionSupportEnum.valueOf(txSupport);
        } catch (RuntimeException e) {
            return TransactionSupportEnum.LocalTransaction;
        }
    }

    private static Activation createActivation(ConnectionDefinition common, TransactionSupportEnum transactionSupport) {
        List<ConnectionDefinition> definitions = Collections.singletonList(common);
        //fix of WFLY-9762 - JMSConnectionFactoryDefinition annotation with the transactional attribute set to false results in  TransactionSupportEnum.NoTransaction -> it has to be propagated
        boolean ignoreJTA = transactionSupport == TransactionSupportEnum.NoTransaction;
        //attribute is propagated only if it means to ignore JTA transaction; in case of not ignoring, default behavior is used
        Map<String, String> configProperties = ignoreJTA ? Collections.<String, String>singletonMap(IGNORE_JTA, String.valueOf(ignoreJTA)) : Collections.emptyMap();
        return new ActivationImpl(null, null, transactionSupport, definitions, Collections.<AdminObject>emptyList(), configProperties, Collections.<String>emptyList(), null, null);
    }


    private static ConnectionDefinition createConnDef(TransactionSupportEnum transactionSupport, String jndiName, int minPoolSize, int maxPoolSize, String managedConnectionPoolClassName, Boolean enlistmentTrace) throws ValidateException {
        Integer minSize = (minPoolSize == -1) ? null : minPoolSize;
        Integer maxSize = (maxPoolSize == -1) ? null : maxPoolSize;
        boolean prefill = false;
        boolean useStrictMin = false;
        FlushStrategy flushStrategy = FlushStrategy.FAILING_CONNECTION_ONLY;
        Boolean isXA = Boolean.FALSE;
        final Pool pool;
        if (transactionSupport == TransactionSupportEnum.XATransaction) {
            pool = new XaPoolImpl(minSize, Defaults.INITIAL_POOL_SIZE, maxSize, prefill, useStrictMin, flushStrategy, null, Defaults.FAIR,
                    Defaults.IS_SAME_RM_OVERRIDE, Defaults.INTERLEAVING, Defaults.PAD_XID, Defaults.WRAP_XA_RESOURCE, Defaults.NO_TX_SEPARATE_POOL);
            isXA = Boolean.TRUE;
        } else {
            pool = new PoolImpl(minSize, Defaults.INITIAL_POOL_SIZE, maxSize, prefill, useStrictMin, flushStrategy, null, Defaults.FAIR);
        }
        TimeOut timeOut = new TimeOutImpl(null, null, null, null, null) {
        };
        // <security>
        //   <application />
        // </security>
        // => PoolStrategy.POOL_BY_CRI
        Security security = new SecurityImpl(null, null, true);
        // register the XA Connection *without* recovery. ActiveMQ already takes care of the registration with the correct credentials
        // when its ResourceAdapter is started
        Recovery recovery = new Recovery(new CredentialImpl(null, null, null, null), null, Boolean.TRUE);
        Validation validation = new ValidationImpl(Defaults.VALIDATE_ON_MATCH, null, null, false);
        // do no track
        return new ConnectionDefinitionImpl(Collections.<String, String>emptyMap(), RAMANAGED_CONN_FACTORY, jndiName, ACTIVEMQ_CONN_DEF, true, true, true, Defaults.SHARABLE, Defaults.ENLISTMENT, Defaults.CONNECTABLE, false, managedConnectionPoolClassName, enlistmentTrace, pool, timeOut, validation, security, recovery, isXA);
    }

    private static Connector createConnector15(ResourceAdapter ra) {
        return new ConnectorImpl(Connector.Version.V_15, null, str("Red Hat"), str("JMS 1.1 Server"), str("1.0"), null, ra, null, false, EMPTY_LOCL, EMPTY_LOCL, Collections.<Icon>emptyList(), null);
    }

    private ResourceAdapter createResourceAdapter15(List<ConfigProperty> properties, OutboundResourceAdapter outbound, InboundResourceAdapter inbound) {
        return new ResourceAdapterImpl(str(ACTIVEMQ_RESOURCE_ADAPTER), properties, outbound, inbound, Collections.<org.jboss.jca.common.api.metadata.spec.AdminObject>emptyList(), Collections.<SecurityPermission>emptyList(), null);
    }

    private InboundResourceAdapter createInbound(List<ConfigProperty> inboundProps) {
        List<RequiredConfigProperty> destination = Collections.<RequiredConfigProperty>singletonList(new RequiredConfigPropertyImpl(EMPTY_LOCL, str("destination"), null));

        Activationspec activation15 = new ActivationSpecImpl(str(ACTIVEMQ_ACTIVATION), destination, inboundProps, null);
        List<MessageListener> messageListeners = Collections.<MessageListener>singletonList(new MessageListenerImpl(str(JMS_MESSAGE_LISTENER), activation15, null));
        Messageadapter message = new MessageAdapterImpl(messageListeners, null);

        return new InboundResourceAdapterImpl(message, null);
    }

    private static OutboundResourceAdapter createOutbound(List<ConfigProperty> outboundProperties) {
        List<org.jboss.jca.common.api.metadata.spec.ConnectionDefinition> definitions = new ArrayList();
        List<ConfigProperty> props = new ArrayList(outboundProperties);
        props.add(simpleProperty15(SESSION_DEFAULT_TYPE, STRING_TYPE, JMS_QUEUE));
        props.add(simpleProperty15(TRY_LOCK, INTEGER_TYPE, "0"));
        definitions.add(new org.jboss.jca.common.metadata.spec.ConnectionDefinitionImpl(str(RAMANAGED_CONN_FACTORY), props, str(RA_CONN_FACTORY), str(RA_CONN_FACTORY_IMPL), str(JMS_SESSION), str(ACTIVEMQ_RA_SESSION), null));

        AuthenticationMechanism basicPassword = new AuthenticationMechanismImpl(Collections.<LocalizedXsdString>emptyList(), str(BASIC_PASS), CredentialInterfaceEnum.PasswordCredential, null, null);
        return new OutboundResourceAdapterImpl(definitions, TransactionSupportEnum.XATransaction, Collections.singletonList(basicPassword), false, null, null, null);
    }

    private static XsdString str(String str) {
        return new XsdString(str, null);
    }

    private static ConfigProperty simpleProperty15(String name, String type, String value) {
        return new ConfigPropertyImpl(EMPTY_LOCL, str(name), str(type), str(value), null, null, null, null, false, null, null, null, null);
    }


    public void stop(StopContext context) {
        // Service context takes care of this
    }
}
